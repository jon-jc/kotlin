package com.roam.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import com.roam.network.AuthSession
import com.roam.network.SessionStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Device-bound credentials. Only authenticated ciphertext is written outside Android Keystore. */
class AndroidSessionStore(context: Context, projectUrl: String, allowLocalHttp: Boolean = false) :
    SessionStore {
    private val app = context.applicationContext
    private val project = canonicalProject(projectUrl, allowLocalHttp)
    private val binding =
        "roam-session-v1\n${app.packageName}\n$project".toByteArray(Charsets.UTF_8)
    private val projectId =
        MessageDigest.getInstance("SHA-256").digest(binding).joinToString("") { "%02x".format(it) }
    private val directory = File(app.noBackupFilesDir, "sessions")
    internal val encryptedFile = File(directory, "$projectId.session")
    internal val keyAlias = "${app.packageName}.session.v1.$projectId"
    private val atomic = AtomicFile(encryptedFile)

    override suspend fun read(): AuthSession? = locked {
        if (!sessionFiles().any(File::exists)) return@locked null
        try {
            val encrypted = readEncrypted()
            require(encrypted.size >= HEADER_BYTES + TAG_BYTES)
            val envelope = ByteBuffer.wrap(encrypted)
            require(envelope.int == MAGIC && envelope.int == VERSION)
            val iv = ByteArray(IV_BYTES).also(envelope::get)
            val ciphertext = ByteArray(envelope.remaining()).also(envelope::get)
            val key =
                keyStore().getKey(keyAlias, null) as? SecretKey
                    ?: throw IOException("Saved sign-in is no longer available")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(binding)
            val plaintext = cipher.doFinal(ciphertext)
            try {
                decode(plaintext)
            } finally {
                plaintext.fill(0)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            clearLocked()
            null
        }
    }

    override suspend fun write(session: AuthSession?) {
        locked {
            if (session == null) {
                clearLocked()
                return@locked
            }
            val plaintext = encode(session)
            var output: FileOutputStream? = null
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                // Android Keystore supplies a fresh random IV; callers cannot supply encryption
                // IVs.
                cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
                cipher.updateAAD(binding)
                val ciphertext = cipher.doFinal(plaintext)
                val iv = cipher.iv
                require(iv.size == IV_BYTES)
                val encrypted =
                    ByteBuffer.allocate(HEADER_BYTES + ciphertext.size)
                        .putInt(MAGIC)
                        .putInt(VERSION)
                        .put(iv)
                        .put(ciphertext)
                        .array()
                require(encrypted.size <= MAX_FILE_BYTES)
                output = atomic.startWrite()
                output.write(encrypted)
                output.fd.sync()
                atomic.finishWrite(output)
                output = null
                syncDirectory(directory)
                // AtomicFile reports some commit failures only to the platform log.
                if (!readEncrypted().contentEquals(encrypted)) throw IOException("Commit failed")
            } catch (cancelled: CancellationException) {
                output?.let(atomic::failWrite)
                throw cancelled
            } catch (_: Exception) {
                output?.let(atomic::failWrite)
                clearLocked()
                throw IOException("Your sign-in could not be saved securely. Please sign in again.")
            } finally {
                plaintext.fill(0)
            }
        }
    }

    private suspend fun <T> locked(block: () -> T): T =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                if (!directory.exists()) {
                    if (!directory.mkdirs() && !directory.isDirectory) {
                        throw IOException("Secure sign-in storage is unavailable")
                    }
                    syncDirectory(app.noBackupFilesDir)
                }
                // The process mutex also prevents overlapping JVM file locks from separate
                // instances.
                RandomAccessFile(File(directory, "$projectId.lock"), "rw").use { file ->
                    file.channel.use { channel -> channel.lock().use { block() } }
                }
            }
        }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun encryptionKey(): SecretKey {
        val keys = keyStore()
        (keys.getKey(keyAlias, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun readEncrypted(): ByteArray =
        atomic.openRead().use { input ->
            val size = input.channel.size()
            require(size in 1..MAX_FILE_BYTES.toLong())
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count <= 0) throw IOException("Incomplete saved sign-in")
                offset += count
            }
            if (input.read() != -1) throw IOException("Saved sign-in exceeded the supported size")
            bytes
        }

    private fun clearLocked() {
        var failed = false
        // Destroying the key also makes leftover or replayed ciphertext unusable after logout.
        try {
            val keys = keyStore()
            keys.deleteEntry(keyAlias)
            if (keys.containsAlias(keyAlias)) failed = true
        } catch (_: Exception) {
            failed = true
        }
        atomic.delete()
        for (file in sessionFiles()) {
            if (file.exists() && !file.delete()) failed = true
        }
        try {
            syncDirectory(directory)
        } catch (_: Exception) {
            failed = true
        }
        if (failed) throw IOException("Saved sign-in could not be fully removed. Please try again.")
    }

    private fun sessionFiles() =
        listOf(encryptedFile, File(encryptedFile.path + ".bak"), File(encryptedFile.path + ".new"))

    private fun encode(session: AuthSession): ByteArray {
        require(runCatching { UUID.fromString(session.sessionId) }.isSuccess)
        val fields =
            listOf(session.userId, session.accessToken, session.refreshToken, session.sessionId)
                .map { it.toByteArray(Charsets.UTF_8) }
        try {
            require(fields[0].size <= MAX_USER_BYTES && fields[3].size <= MAX_USER_BYTES)
            require(fields.subList(1, 3).all { it.size <= MAX_TOKEN_BYTES })
            val buffer = ByteBuffer.allocate(fields.sumOf { it.size + 4 } + 8)
            for (field in fields) buffer.putInt(field.size).put(field)
            buffer.putLong(session.expiresAt)
            return buffer.array()
        } finally {
            fields.forEach { it.fill(0) }
        }
    }

    private fun decode(plaintext: ByteArray): AuthSession {
        require(plaintext.size <= MAX_PAYLOAD_BYTES)
        val buffer = ByteBuffer.wrap(plaintext)
        fun field(maximum: Int): String {
            require(buffer.remaining() >= 4)
            val size = buffer.int
            require(size in 1..maximum && size <= buffer.remaining())
            val bytes = ByteArray(size).also(buffer::get)
            return try {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            } finally {
                bytes.fill(0)
            }
        }
        val userId = field(MAX_USER_BYTES)
        val accessToken = field(MAX_TOKEN_BYTES)
        val refreshToken = field(MAX_TOKEN_BYTES)
        val sessionId = field(MAX_USER_BYTES)
        require(runCatching { UUID.fromString(sessionId) }.isSuccess)
        require(buffer.remaining() == 8)
        return AuthSession(userId, accessToken, refreshToken, buffer.long, sessionId)
    }

    private fun syncDirectory(file: File) {
        if (!file.isDirectory) throw IOException("Secure sign-in storage is unavailable")
        val descriptor = Os.open(file.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    companion object {
        private val processMutex = Mutex()
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val MAGIC = 0x524F414D
        private const val VERSION = 1
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val HEADER_BYTES = 8 + IV_BYTES
        private const val MAX_USER_BYTES = 128
        private const val MAX_TOKEN_BYTES = 64 * 1024
        private const val MAX_PAYLOAD_BYTES = 2 * MAX_TOKEN_BYTES + 2 * MAX_USER_BYTES + 24
        private const val MAX_FILE_BYTES = HEADER_BYTES + MAX_PAYLOAD_BYTES + TAG_BYTES

        private fun canonicalProject(value: String, allowLocalHttp: Boolean): String {
            require(value.length in 1..2048) { "A valid sign-in project URL is required" }
            val uri = URI(value).normalize()
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)
            require(
                host != null &&
                    uri.rawUserInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null
            ) {
                "A valid sign-in project URL is required"
            }
            val local = host in setOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]", "::1")
            require(scheme == "https" || (scheme == "http" && allowLocalHttp && local)) {
                "Sign-in project URLs require HTTPS"
            }
            require(uri.port == -1 || uri.port in 1..65535) { "Invalid sign-in project port" }
            val port = if (uri.port == -1) if (scheme == "https") 443 else 80 else uri.port
            return "$scheme://$host:$port${uri.rawPath.orEmpty().trimEnd('/')}"
        }
    }
}

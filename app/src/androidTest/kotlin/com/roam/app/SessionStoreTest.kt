package com.roam.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roam.network.AuthSession
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val stores = mutableListOf<AndroidSessionStore>()
    private val project = "https://test-${UUID.randomUUID()}.supabase.co"
    private val session =
        AuthSession(
            UUID.randomUUID().toString(),
            "access-token-private",
            "refresh-token-private",
            2000000000,
            sessionId = UUID.randomUUID().toString(),
        )

    @After fun cleanup() = runBlocking { stores.forEach { it.write(null) } }

    @Test
    fun writeReadAndReopenPersistOnlyCiphertext() = runBlocking {
        val store = store()
        assertNull(store.read())
        store.write(session)
        assertEquals(session, store.read())
        assertEquals(session, store().read())
        assertEquals(session.sessionId, store().read()!!.sessionId)
        assertEquals(session, store(project + "/").read())
        val content = store.encryptedFile.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(content.contains(session.accessToken))
        assertFalse(content.contains(session.refreshToken))
        assertFalse(content.contains(session.userId))
        assertTrue(
            store.encryptedFile.canonicalPath.startsWith(
                context.noBackupFilesDir.canonicalPath + File.separator
            )
        )
    }

    @Test
    fun repeatedWritesUseFreshIvAndKeepLatestSession() = runBlocking {
        val store = store()
        store.write(session)
        val first = store.encryptedFile.readBytes()
        store.write(session)
        val second = store.encryptedFile.readBytes()
        assertFalse(first.copyOfRange(8, 20).contentEquals(second.copyOfRange(8, 20)))
        val refreshed =
            session.copy(accessToken = "refreshed-access", refreshToken = "rotated-refresh")
        store.write(refreshed)
        assertEquals(refreshed, store().read())
    }

    @Test
    fun tamperingClearsTheCiphertextAndKey() = runBlocking {
        val store = store()
        store.write(session)
        val modified = store.encryptedFile.readBytes()
        modified[modified.lastIndex] = (modified.last().toInt() xor 1).toByte()
        store.encryptedFile.writeBytes(modified)
        assertNull(store().read())
        assertErased(store)
        store.write(session)
        assertEquals(session, store.read())
    }

    @Test
    fun differentProjectsHaveIndependentSessionsAndRejectCopiedCiphertext() = runBlocking {
        val first = store()
        val second = store("https://other-${UUID.randomUUID()}.supabase.co")
        first.write(session)
        assertNull(second.read())
        val other =
            session.copy(userId = UUID.randomUUID().toString(), accessToken = "other-access")
        second.write(other)
        assertEquals(session, first.read())
        assertEquals(other, second.read())
        second.encryptedFile.writeBytes(first.encryptedFile.readBytes())
        assertNull(second.read())
        assertErased(second)
        assertEquals(session, first.read())
    }

    @Test
    fun losingTheKeystoreKeyInvalidatesSavedCredentials() = runBlocking {
        val store = store()
        store.write(session)
        keys().deleteEntry(store.keyAlias)
        assertNull(store().read())
        assertErased(store)
    }

    @Test
    fun deletionErasesAtomicSidecarsAndPreventsCiphertextReplay() = runBlocking {
        val store = store()
        store.write(session)
        val previous = store.encryptedFile.readBytes()
        File(store.encryptedFile.path + ".bak").writeBytes(previous)
        File(store.encryptedFile.path + ".new").writeBytes(previous)
        store.write(null)
        assertErased(store)
        assertNull(store().read())
        store.encryptedFile.writeBytes(previous)
        assertNull(store.read())
        assertErased(store)
        store.write(session.copy(accessToken = "signed-in-again"))
        store.encryptedFile.writeBytes(previous)
        assertNull(store.read())
        assertErased(store)
    }

    @Test
    fun oversizedAndUnsupportedFilesFailClosed() = runBlocking {
        val store = store()
        store.write(session)
        store.encryptedFile.writeBytes(ByteArray(200000))
        assertNull(store.read())
        assertErased(store)
        store.write(session)
        val unsupported = store.encryptedFile.readBytes()
        unsupported[7] = 2
        store.encryptedFile.writeBytes(unsupported)
        assertNull(store.read())
        assertErased(store)
    }

    private fun store(url: String = project) = AndroidSessionStore(context, url).also(stores::add)

    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun assertErased(store: AndroidSessionStore) {
        assertFalse(store.encryptedFile.exists())
        assertFalse(File(store.encryptedFile.path + ".bak").exists())
        assertFalse(File(store.encryptedFile.path + ".new").exists())
        assertFalse(keys().containsAlias(store.keyAlias))
    }
}

package com.roam.network

import java.io.IOException
import java.time.Clock
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class AuthSession(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val sessionId: String = UUID.randomUUID().toString(),
) {
    init {
        require(runCatching { UUID.fromString(userId) }.isSuccess)
        require(accessToken.isNotBlank() && accessToken.length <= 16384)
        require(refreshToken.isNotBlank() && refreshToken.length <= 16384)
        require(expiresAt > 0)
    }

    override fun toString() = "AuthSession(redacted)"
}

interface SessionStore {
    suspend fun read(): AuthSession?

    suspend fun write(session: AuthSession?)
}

class AuthException(message: String) : IOException(message)

/** Serializes refresh rotation and ensures logout cannot be undone by a late auth response. */
class SupabaseAuth(
    supabaseUrl: String,
    private val publishableKey: String,
    private val store: SessionStore,
    private val http: JsonHttp = JsonHttp(),
    private val clock: Clock = Clock.systemUTC(),
    allowLocalHttp: Boolean = false,
) {
    private val base = JsonHttp.baseUrl(supabaseUrl, allowLocalHttp)
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private val current = MutableStateFlow<AuthSession?>(null)
    private var generation = 0L
    val sessions: StateFlow<AuthSession?> = current.asStateFlow()

    init {
        require(publishableKey.startsWith("sb_publishable_")) {
            "Only a Supabase publishable key belongs in the Android app"
        }
    }

    suspend fun restore() = mutex.withLock { current.value = store.read() }

    suspend fun sendCode(email: String) {
        val clean = validateEmail(email)
        try {
            http.request(
                endpoint("otp"),
                "POST",
                http.json.encodeToString(OtpRequest(clean)),
                publicHeaders(),
            )
        } catch (e: HttpFailure) {
            throw AuthException(
                if (e.status == 429) "Please wait before requesting another code."
                else "We couldn't send a code. Check your email address and try again."
            )
        }
    }

    suspend fun verifyCode(email: String, code: String) {
        val clean = validateEmail(email)
        require(code.matches(Regex("[0-9]{6,10}"))) { "Enter the code from your email." }
        val attempt = mutex.withLock { generation }
        val response =
            try {
                http.request(
                    endpoint("verify"),
                    "POST",
                    http.json.encodeToString(VerifyRequest(clean, code)),
                    publicHeaders(),
                )
            } catch (e: HttpFailure) {
                throw AuthException(
                    if (e.status == 429) "Too many attempts. Please wait and try again."
                    else "That code is invalid or expired. Request a new code."
                )
            }
        val session = decode(response)
        mutex.withLock {
            if (attempt != generation)
                throw AuthException("Sign-in was cancelled. Please try again.")
            withContext(NonCancellable) {
                store.write(session)
                generation++
                current.value = session
            }
        }
    }

    suspend fun accessToken(rejectedToken: String? = null): String =
        refreshMutex.withLock {
            val (old, attempt) =
                mutex.withLock {
                    (current.value ?: throw AuthException("Please sign in to continue.")) to
                        generation
                }
            val stillValid = old.expiresAt > clock.instant().epochSecond + 45
            if (stillValid && (rejectedToken == null || rejectedToken != old.accessToken))
                return@withLock old.accessToken
            val response =
                try {
                    http.request(
                        endpoint("token")
                            .newBuilder()
                            .addQueryParameter("grant_type", "refresh_token")
                            .build(),
                        "POST",
                        http.json.encodeToString(RefreshRequest(old.refreshToken)),
                        publicHeaders(),
                    )
                } catch (e: HttpFailure) {
                    if (e.status in setOf(400, 401, 403, 422)) {
                        mutex.withLock {
                            if (attempt == generation) {
                                clearSession()
                            }
                        }
                        throw AuthException("Your session expired. Please sign in again.")
                    }
                    throw AuthException("Your session couldn't be refreshed. Please try again.")
                }
            val refreshed = decode(response).copy(sessionId = old.sessionId)
            mutex.withLock {
                if (attempt != generation)
                    throw AuthException("Your session changed. Please sign in again.")
                if (refreshed.userId != old.userId) {
                    clearSession()
                    throw AuthException("Your session changed. Please sign in again.")
                }
                withContext(NonCancellable) {
                    store.write(refreshed)
                    current.value = refreshed
                }
            }
            refreshed.accessToken
        }

    suspend fun signOut() {
        val previous =
            mutex.withLock {
                val old = current.value
                clearSession()
                old
            }
        if (previous != null) {
            try {
                http.request(
                    endpoint("logout").newBuilder().addQueryParameter("scope", "local").build(),
                    "POST",
                    "{}",
                    publicHeaders() + ("Authorization" to "Bearer ${previous.accessToken}"),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                /* Local credentials remain erased when revocation is offline. */
            }
        }
    }

    private suspend fun clearSession() =
        withContext(NonCancellable) {
            generation++
            try {
                store.write(null)
            } finally {
                current.value = null
            }
        }

    private fun decode(text: String): AuthSession {
        val token =
            try {
                http.json.decodeFromString<TokenResponse>(text)
            } catch (_: kotlinx.serialization.SerializationException) {
                throw AuthException("The sign-in service returned an invalid session.")
            }
        require(token.tokenType.equals("bearer", true))
        require(token.expiresIn in 1..604800)
        return AuthSession(
            token.user.id,
            token.accessToken,
            token.refreshToken,
            clock.instant().epochSecond + token.expiresIn,
        )
    }

    private fun endpoint(path: String) = base.newBuilder().addPathSegments("auth/v1/$path").build()

    private fun publicHeaders() = mapOf("apikey" to publishableKey)

    private fun validateEmail(email: String): String =
        email.trim().lowercase(Locale.ROOT).also {
            require(
                it.length in 3..254 &&
                    it.contains('@') &&
                    it.none(Char::isWhitespace) &&
                    it.none(Char::isISOControl)
            ) {
                "Enter a valid email address."
            }
        }
}

@Serializable
private data class OtpRequest(
    val email: String,
    @SerialName("create_user") val createUser: Boolean = true,
)

@Serializable
private data class VerifyRequest(val email: String, val token: String, val type: String = "email")

@Serializable
private data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable private data class AuthUser(val id: String)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("token_type") val tokenType: String,
    val user: AuthUser,
)

package com.roam.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roam.network.AuthException
import com.roam.network.SupabaseAuth
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SessionIdentity(val userId: String, val sessionId: String)

interface AuthActions {
    val sessions: Flow<SessionIdentity?>

    suspend fun restore()

    suspend fun sendCode(email: String)

    suspend fun verifyCode(email: String, code: String)

    suspend fun signOut()
}

class SupabaseAuthActions(private val auth: SupabaseAuth) : AuthActions {
    override val sessions =
        auth.sessions.map { it?.let { SessionIdentity(it.userId, it.sessionId) } }

    override suspend fun restore() = auth.restore()

    override suspend fun sendCode(email: String) = auth.sendCode(email)

    override suspend fun verifyCode(email: String, code: String) = auth.verifyCode(email, code)

    override suspend fun signOut() = auth.signOut()
}

data class AuthState(
    val restoring: Boolean = true,
    val session: SessionIdentity? = null,
    val email: String = "",
    val codeRequested: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/** UI state contains no access token, refresh token, or one-time code. */
class AuthViewModel(private val auth: AuthActions, private val saved: SavedStateHandle) :
    ViewModel() {
    private val current =
        MutableStateFlow(
            AuthState(
                email = saved.get<String>("email").orEmpty(),
                codeRequested = saved.get<Boolean>("codeRequested") ?: false,
            )
        )
    val state: StateFlow<AuthState> = current.asStateFlow()
    private var restoring: Job? = null

    init {
        viewModelScope.launch {
            auth.sessions.distinctUntilChanged().collect { session ->
                current.update { previous ->
                    if (session != null || previous.session != null) {
                        saved.remove<String>("email")
                        saved.remove<Boolean>("codeRequested")
                        previous.copy(
                            session = session,
                            email = "",
                            codeRequested = false,
                            error = null,
                        )
                    } else previous.copy(session = null)
                }
            }
        }
        restore()
    }

    fun restore() {
        if (restoring?.isActive == true) return
        current.update { it.copy(restoring = true, error = null) }
        restoring =
            viewModelScope.launch {
                try {
                    auth.restore()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    current.update {
                        it.copy(error = "We couldn't open your saved sign-in. Please try again.")
                    }
                } finally {
                    current.update { it.copy(restoring = false) }
                }
            }
    }

    fun email(value: String) {
        if (current.value.busy || current.value.codeRequested) return
        val email = value.take(254)
        saved["email"] = email
        current.update { it.copy(email = email, error = null) }
    }

    fun changeEmail() {
        if (current.value.busy) return
        saved["codeRequested"] = false
        current.update { it.copy(codeRequested = false, error = null) }
    }

    fun sendCode() {
        val email = current.value.email.trim().lowercase(Locale.ROOT)
        if (
            email.length !in 3..254 ||
                !email.contains('@') ||
                email.any { it.isWhitespace() || it.isISOControl() }
        ) {
            current.update { it.copy(error = "Enter a valid email address.") }
            return
        }
        operation {
            auth.sendCode(email)
            saved["email"] = email
            saved["codeRequested"] = true
            current.update { it.copy(email = email, codeRequested = true) }
        }
    }

    fun verifyCode(code: String) {
        if (!current.value.codeRequested) return
        if (!code.matches(Regex("[0-9]{6,10}"))) {
            current.update { it.copy(error = "Enter the code from your email.") }
            return
        }
        val email = current.value.email
        operation { auth.verifyCode(email, code) }
    }

    fun signOut() = operation { auth.signOut() }

    private fun operation(block: suspend () -> Unit) {
        if (current.value.busy || current.value.restoring) return
        current.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                current.update { it.copy(error = e.message) }
            } catch (_: Exception) {
                current.update {
                    it.copy(
                        error = "We couldn't complete sign-in. Check your connection and try again."
                    )
                }
            } finally {
                current.update { it.copy(busy = false) }
            }
        }
    }
}

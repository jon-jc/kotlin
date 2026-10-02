package com.roam.app

import androidx.lifecycle.SavedStateHandle
import com.roam.network.AuthException
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var auth: FakeAuth
    private lateinit var handle: SavedStateHandle
    private lateinit var model: AuthViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        auth = FakeAuth()
        handle = SavedStateHandle()
        model = AuthViewModel(auth, handle)
        dispatcher.scheduler.runCurrent()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `one-time code is never persisted and successful sign-in removes email draft`() =
        runTest(dispatcher) {
            model.email("  TRAVELER@example.com ")
            model.sendCode()
            runCurrent()
            assertTrue(model.state.value.codeRequested)
            assertEquals("traveler@example.com", model.state.value.email)
            model.verifyCode("123456")
            runCurrent()
            assertEquals(auth.identity, model.state.value.session)
            assertTrue(handle.keys().isEmpty())
            assertEquals("", model.state.value.email)
            assertFalse(model.state.value.codeRequested)
        }

    @Test
    fun `rapid send taps issue only one email request`() =
        runTest(dispatcher) {
            auth.sendGate = CompletableDeferred()
            model.email("traveler@example.com")
            repeat(10) { model.sendCode() }
            runCurrent()
            assertEquals(1, auth.sendCount)
            assertTrue(model.state.value.busy)
            auth.sendGate!!.complete(Unit)
            runCurrent()
            assertTrue(model.state.value.codeRequested)
            assertFalse(model.state.value.busy)
        }

    @Test
    fun `invalid code never reaches authentication service`() =
        runTest(dispatcher) {
            model.email("traveler@example.com")
            model.sendCode()
            runCurrent()
            model.verifyCode("123")
            runCurrent()
            assertEquals(0, auth.verifyCount)
            assertEquals("Enter the code from your email.", model.state.value.error)
        }

    @Test
    fun `logout removes the active session and sign-in draft`() =
        runTest(dispatcher) {
            auth.sessions.value = auth.identity
            runCurrent()
            model.signOut()
            runCurrent()
            assertNull(model.state.value.session)
            assertEquals(1, auth.signOutCount)
            assertTrue(handle.keys().isEmpty())
        }

    @Test
    fun `provider failures do not expose exception internals`() =
        runTest(dispatcher) {
            auth.sendFailure = IOException("sensitive upstream details")
            model.email("traveler@example.com")
            model.sendCode()
            runCurrent()
            assertFalse(model.state.value.error.orEmpty().contains("sensitive"))
            assertFalse(model.state.value.busy)
            assertFalse(model.state.value.codeRequested)
            auth.sendFailure = AuthException("Please wait before requesting another code.")
            model.sendCode()
            runCurrent()
            assertEquals("Please wait before requesting another code.", model.state.value.error)
        }

    private class FakeAuth : AuthActions {
        val identity = SessionIdentity("user-a", "login-a")
        override val sessions = MutableStateFlow<SessionIdentity?>(null)
        var sendCount = 0
        var verifyCount = 0
        var signOutCount = 0
        var sendGate: CompletableDeferred<Unit>? = null
        var sendFailure: Exception? = null

        override suspend fun restore() = Unit

        override suspend fun sendCode(email: String) {
            sendCount++
            sendFailure?.let { throw it }
            sendGate?.await()
        }

        override suspend fun verifyCode(email: String, code: String) {
            verifyCount++
            sessions.value = identity
        }

        override suspend fun signOut() {
            signOutCount++
            sessions.value = null
        }
    }
}

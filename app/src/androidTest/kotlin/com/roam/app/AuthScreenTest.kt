package com.roam.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.roam.network.AuthException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AuthScreenTest {
    @get:Rule val compose = createComposeRule()
    private val actions = ControlledAuth()
    private val saved = SavedStateHandle()
    private val models = ViewModelStore()
    private lateinit var model: AuthViewModel

    @Before
    fun setup() {
        compose.runOnUiThread {
            model =
                ViewModelProvider(
                    models,
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            AuthViewModel(actions, saved) as T
                    },
                )[AuthViewModel::class.java]
        }
        compose.waitUntil(5_000) { !model.state.value.restoring }
    }

    @After
    fun tearDown() {
        compose.runOnUiThread { models.clear() }
    }

    @Test
    fun deliveredCodeSignsInWithTheRequestedEmail() {
        showScreen()
        requestCode("Traveler@Example.com")
        compose
            .onNodeWithText("Enter the sign-in code sent to traveler@example.com.")
            .assertExists()
        codeField().performTextInput("654321")
        click("Open my passport")

        compose.waitUntil(5_000) { model.state.value.session != null }
        assertEquals(listOf("traveler@example.com"), actions.deliveries.toList())
        assertEquals(listOf("traveler@example.com" to "654321"), actions.attempts.toList())
        assertEquals(SessionIdentity("traveler", "new-login"), model.state.value.session)
        assertFalse(model.state.value.codeRequested)
        assertEquals("", model.state.value.email)
    }

    @Test
    fun incompleteCodeShowsCorrectionWithoutCallingProvider() {
        showScreen()
        requestCode()
        codeField().performTextInput("123")
        click("Open my passport")

        compose
            .onNodeWithText("Enter the code from your email.")
            .performScrollTo()
            .assertIsDisplayed()
        assertTrue(actions.attempts.isEmpty())
        assertNull(model.state.value.session)
        codeField().assertEditableText("123")
    }

    @Test
    fun rejectedCodeKeepsTheSignInScreenAndAcceptsACorrectedCode() {
        showScreen()
        requestCode()
        codeField().performTextInput("123456")
        click("Open my passport")

        compose
            .onNodeWithText("That code is invalid or expired. Request a new code.")
            .performScrollTo()
            .assertIsDisplayed()
        assertNull(model.state.value.session)
        codeField().assertEditableText("123456")
        codeField().performTextReplacement("654321")
        click("Open my passport")

        compose.waitUntil(5_000) { model.state.value.session != null }
        assertEquals(
            listOf("traveler@example.com" to "123456", "traveler@example.com" to "654321"),
            actions.attempts.toList(),
        )
    }

    @Test
    fun changingEmailDropsThePreviousCode() {
        showScreen()
        requestCode()
        codeField().performTextInput("654321")
        click("Change email")
        emailField().performTextReplacement("new@example.com")
        click("Send sign-in code")

        compose.onNodeWithText("Enter the sign-in code sent to new@example.com.").assertExists()
        codeField().assertEditableText("")
        assertEquals(listOf("traveler@example.com", "new@example.com"), actions.deliveries.toList())
        assertTrue(actions.attempts.isEmpty())
    }

    @Test
    fun restoringTheScreenKeepsTheDeliveryStepButDropsTheOneTimeCode() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val state by model.state.collectAsState()
            RoamTheme { AuthScreen(state, model) }
        }
        requestCode()
        codeField().performTextInput("654321")
        codeField().assertEditableText("654321")

        restoration.emulateSavedInstanceStateRestore()

        compose
            .onNodeWithText("Enter the sign-in code sent to traveler@example.com.")
            .assertExists()
        codeField().assertEditableText("")
        assertTrue(actions.attempts.isEmpty())
        compose.runOnIdle {
            assertEquals(setOf("email", "codeRequested"), saved.keys())
            assertEquals("traveler@example.com", saved.get<String>("email"))
            assertEquals(true, saved.get<Boolean>("codeRequested"))
        }
    }

    private fun showScreen() {
        compose.setContent {
            val state by model.state.collectAsState()
            RoamTheme { AuthScreen(state, model) }
        }
    }

    private fun requestCode(email: String = "traveler@example.com") {
        emailField().performTextInput(email)
        click("Send sign-in code")
        codeField().assertExists()
    }

    private fun emailField() = compose.onNode(hasSetTextAction() and hasText("Email address"))

    private fun codeField() = compose.onNode(hasSetTextAction() and hasText("Sign-in code"))

    private fun click(label: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
    }

    private fun SemanticsNodeInteraction.assertEditableText(value: String) =
        assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value))
        )

    private class ControlledAuth : AuthActions {
        override val sessions = MutableStateFlow<SessionIdentity?>(null)
        val deliveries = ConcurrentLinkedQueue<String>()
        val attempts = ConcurrentLinkedQueue<Pair<String, String>>()

        override suspend fun restore() = Unit

        override suspend fun sendCode(email: String) {
            deliveries.add(email)
        }

        override suspend fun verifyCode(email: String, code: String) {
            attempts.add(email to code)
            if (code != "654321") {
                throw AuthException("That code is invalid or expired. Request a new code.")
            }
            sessions.value = SessionIdentity("traveler", "new-login")
        }

        override suspend fun signOut() {
            sessions.value = null
        }
    }
}

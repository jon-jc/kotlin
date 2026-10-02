package com.roam.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SessionNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun logoutClearsAccountViewModelAndNextLoginGetsAnEmptyDraft() {
        val identity =
            mutableStateOf<SessionIdentity?>(SessionIdentity("first-user", "first-login"))
        val models = ConcurrentLinkedQueue<SessionProbe>()
        compose.setContent {
            RoamTheme {
                SessionNavigation(
                    AuthState(restoring = false, session = identity.value),
                    login = { Text("Signed out") },
                ) { entry, session ->
                    val model: SessionProbe =
                        viewModel(
                            viewModelStoreOwner = entry,
                            factory =
                                viewModelFactory {
                                    initializer {
                                        SessionProbe(createSavedStateHandle()).also(models::add)
                                    }
                                },
                        )
                    val draft by model.draft.collectAsState()
                    Column {
                        Text(session.userId)
                        Text(draft)
                        Button({ model.save("private draft for ${session.userId}") }) {
                            Text("Remember draft")
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("first-user").assertIsDisplayed()
        compose.onNodeWithText("Remember draft").performClick()
        compose.onNodeWithText("private draft for first-user").assertIsDisplayed()
        val first = models.first()
        compose.runOnIdle { identity.value = null }
        compose.onNodeWithText("Signed out").assertIsDisplayed()
        compose.waitUntil(5_000) { first.cleared }
        compose.runOnIdle { identity.value = SessionIdentity("second-user", "second-login") }
        compose.onNodeWithText("second-user").assertIsDisplayed()
        compose.onNodeWithText("Empty draft").assertIsDisplayed()
        compose.onNodeWithText("private draft for first-user").assertDoesNotExist()
        assertEquals(2, models.size)
    }

    @Test
    fun newLoginForSameUserDoesNotRecoverTheOldSessionDraft() {
        val identity = mutableStateOf(SessionIdentity("same-user", "old-login"))
        val models = ConcurrentLinkedQueue<SessionProbe>()
        compose.setContent {
            RoamTheme {
                SessionNavigation(
                    AuthState(restoring = false, session = identity.value),
                    login = { Text("Signed out") },
                ) { entry, _ ->
                    val model: SessionProbe =
                        viewModel(
                            viewModelStoreOwner = entry,
                            factory =
                                viewModelFactory {
                                    initializer {
                                        SessionProbe(createSavedStateHandle()).also(models::add)
                                    }
                                },
                        )
                    val draft by model.draft.collectAsState()
                    Column {
                        Text(draft)
                        Button({ model.save("Previous login draft") }) { Text("Remember draft") }
                    }
                }
            }
        }
        compose.onNodeWithText("Remember draft").performClick()
        val first = models.first()
        compose.runOnIdle { identity.value = SessionIdentity("same-user", "new-login") }
        compose.onNodeWithText("Empty draft").assertIsDisplayed()
        compose.onNodeWithText("Previous login draft").assertDoesNotExist()
        compose.waitUntil(5_000) { first.cleared }
        assertEquals(2, models.size)
    }

    class SessionProbe(private val handle: SavedStateHandle) : ViewModel() {
        val draft = handle.getStateFlow("draft", "Empty draft")
        @Volatile var cleared = false

        fun save(value: String) {
            handle["draft"] = value
        }

        override fun onCleared() {
            cleared = true
        }
    }
}

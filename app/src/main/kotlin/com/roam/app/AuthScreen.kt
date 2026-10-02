package com.roam.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun AuthScreen(state: AuthState, model: AuthViewModel) {
    var code by remember(state.email, state.codeRequested) { mutableStateOf("") }
    Box(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Icon(
                Icons.Outlined.Explore,
                null,
                Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            PageHeading(
                "YOUR WORLD, CONNECTED",
                if (state.codeRequested) "One small step.\nA world ahead."
                else "Good to\nsee you here.",
                if (state.codeRequested) "Enter the sign-in code sent to ${state.email}."
                else "Sign in to keep your stays, travel credit, and passport together.",
            )
            SurfaceCard {
                if (!state.codeRequested) {
                    OutlinedTextField(
                        state.email,
                        model::email,
                        Modifier.fillMaxWidth(),
                        label = { Text("Email address") },
                        singleLine = true,
                        enabled = !state.busy,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Send,
                            ),
                        keyboardActions = KeyboardActions(onSend = { model.sendCode() }),
                    )
                    PrimaryButton("Send sign-in code", model::sendCode, busy = state.busy)
                    Text(
                        "A code in your inbox. No password to remember.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    OutlinedTextField(
                        code,
                        { code = it.filter(Char::isDigit).take(10) },
                        Modifier.fillMaxWidth(),
                        label = { Text("Sign-in code") },
                        singleLine = true,
                        enabled = !state.busy,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done,
                            ),
                        keyboardActions = KeyboardActions(onDone = { model.verifyCode(code) }),
                    )
                    PrimaryButton("Open my passport", { model.verifyCode(code) }, busy = state.busy)
                    Column {
                        TextButton(model::sendCode, enabled = !state.busy) {
                            Text("Send a new code")
                        }
                        TextButton(model::changeEmail, enabled = !state.busy) {
                            Text("Change email")
                        }
                    }
                }
                ErrorMessage(state.error)
            }
        }
    }
}

@Composable
fun ConnectionUnavailable() {
    Box(
        Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Icon(
                Icons.Outlined.CloudOff,
                null,
                Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            PageHeading(
                "A SMALL DETOUR",
                "Roam isn't\nconnected yet.",
                "This version isn't connected to the booking service. Please use a configured release to sign in and make reservations.",
            )
        }
    }
}

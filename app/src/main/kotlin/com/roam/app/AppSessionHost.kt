package com.roam.app

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.roam.network.PaymentOutcome
import com.roam.network.RemoteCommerceGateway
import com.roam.network.SupabaseAuth
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult

@Composable
fun AppSessionHost(
    auth: SupabaseAuth,
    configuration: ConnectedConfiguration,
    authModel: AuthViewModel,
) {
    val authState by authModel.state.collectAsStateWithLifecycle()
    SessionNavigation(authState, login = { AuthScreen(authState, authModel) }) { entry, identity ->
        val payment: StripePaymentCoordinator = viewModel(viewModelStoreOwner = entry)
        val model: RoamViewModel =
            viewModel(
                viewModelStoreOwner = entry,
                factory =
                    viewModelFactory {
                        initializer {
                            RoamViewModel(
                                RemoteCommerceGateway(
                                    configuration.apiUrl,
                                    auth,
                                    identity.sessionId,
                                    payment,
                                    allowLocalHttp = configuration.allowLocalHttp,
                                ),
                                createSavedStateHandle(),
                            )
                        }
                    },
            )
        val sheet =
            PaymentSheet.Builder { result ->
                    payment.complete(
                        when (result) {
                            is PaymentSheetResult.Completed -> PaymentOutcome.Completed
                            is PaymentSheetResult.Canceled -> PaymentOutcome.Canceled
                            is PaymentSheetResult.Failed -> PaymentOutcome.Failed
                        }
                    )
                }
                .build()
        val launch =
            remember(sheet) {
                { secret: String ->
                    sheet.presentWithPaymentIntent(
                        secret,
                        PaymentSheet.Configuration.Builder("Roam").build(),
                    )
                }
            }
        DisposableEffect(payment, launch) {
            payment.attach(launch)
            onDispose { payment.detach(launch) }
        }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refreshAccount() }
        RoamApp(model, onSignOut = authModel::signOut, sessionError = authState.error)
    }
}

/** Navigation entries own all account-specific view models and saved state. */
@Composable
internal fun SessionNavigation(
    state: AuthState,
    login: @Composable () -> Unit,
    session: @Composable (NavBackStackEntry, SessionIdentity) -> Unit,
) {
    val navigator = rememberNavController()
    val currentEntry by navigator.currentBackStackEntryAsState()
    if (state.restoring) {
        SessionLoading()
        return
    }
    NavHost(navigator, startDestination = "login") {
        composable("login") { if (state.session == null) login() else SessionLoading() }
        composable(
            "session/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val identity = state.session
            // A restored or exiting entry must never display a different account's UI.
            if (identity == null || entry.arguments?.getString("sessionId") != identity.sessionId) {
                SessionLoading()
            } else session(entry, identity)
        }
    }
    LaunchedEffect(state.session?.sessionId, currentEntry) {
        val sessionId = state.session?.sessionId
        val entry = navigator.currentBackStackEntry ?: return@LaunchedEffect
        val alreadyCorrect =
            if (sessionId == null) entry.destination.route == "login"
            else
                entry.destination.route == "session/{sessionId}" &&
                    entry.arguments?.getString("sessionId") == sessionId
        if (!alreadyCorrect) {
            navigator.navigate(
                if (sessionId == null) "login" else "session/${Uri.encode(sessionId)}"
            ) {
                popUpTo(navigator.graph.id) {
                    inclusive = false
                    saveState = false
                }
                launchSingleTop = true
                restoreState = false
            }
        }
    }
}

@Composable
private fun SessionLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

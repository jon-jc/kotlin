package com.roam.app

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent as AndroidIntent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.roam.core.CommerceService
import com.roam.core.ComparisonGateway
import com.roam.core.ComparisonLinks
import com.roam.core.UnavailableComparisonGateway
import com.roam.data.RoamDatabase
import com.roam.data.RoomAccountStore
import com.roam.network.JsonHttp
import com.roam.network.RemoteComparisonGateway
import com.roam.network.SupabaseAuth
import com.stripe.android.PaymentConfiguration
import java.net.URI

class RoamApplication : Application() {
    val container by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    val commerce by lazy { CommerceService(RoomAccountStore(RoamDatabase.create(application))) }
    val configuration =
        if (BuildConfig.ROAM_CONNECTED && !BuildConfig.ROAM_COMPARISON_ONLY)
            ConnectedConfiguration.fromBuildConfig()
        else null
    val comparison: ComparisonGateway by lazy {
        val endpoint = BuildConfig.ROAM_COMPARISON_API_URL
        if (endpoint.isBlank()) UnavailableComparisonGateway()
        else
            runCatching {
                    JsonHttp.baseUrl(endpoint, BuildConfig.DEBUG)
                    RemoteComparisonGateway(endpoint, allowLocalHttp = BuildConfig.DEBUG)
                }
                .getOrElse { UnavailableComparisonGateway() }
    }
    val auth: SupabaseAuth? by lazy {
        configuration?.let {
            SupabaseAuth(
                it.supabaseUrl,
                it.supabasePublishableKey,
                AndroidSessionStore(application, it.supabaseUrl, it.allowLocalHttp),
                allowLocalHttp = it.allowLocalHttp,
            )
        }
    }
}

data class ConnectedConfiguration(
    val apiUrl: String,
    val supabaseUrl: String,
    val supabasePublishableKey: String,
    val stripePublishableKey: String,
    val allowLocalHttp: Boolean = false,
) {
    companion object {
        fun fromBuildConfig(): ConnectedConfiguration? =
            runCatching {
                    requireServiceUrl(BuildConfig.ROAM_API_URL, BuildConfig.DEBUG)
                    requireServiceUrl(BuildConfig.SUPABASE_URL, BuildConfig.DEBUG)
                    require(BuildConfig.SUPABASE_PUBLISHABLE_KEY.startsWith("sb_publishable_"))
                    require(
                        BuildConfig.STRIPE_PUBLISHABLE_KEY.startsWith("pk_live_") ||
                            BuildConfig.STRIPE_PUBLISHABLE_KEY.startsWith("pk_test_")
                    )
                    ConnectedConfiguration(
                        BuildConfig.ROAM_API_URL,
                        BuildConfig.SUPABASE_URL,
                        BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                        BuildConfig.STRIPE_PUBLISHABLE_KEY,
                        BuildConfig.DEBUG,
                    )
                }
                .getOrNull()

        private fun requireServiceUrl(value: String, allowLocalHttp: Boolean) {
            val uri = URI(value)
            val local =
                uri.scheme == "http" &&
                    allowLocalHttp &&
                    uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
            require(
                (uri.scheme == "https" || local) &&
                    !uri.host.isNullOrBlank() &&
                    uri.userInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RoamApplication).container
        container.configuration?.let {
            PaymentConfiguration.init(applicationContext, it.stripePublishableKey)
        }
        setContent {
            RoamTheme {
                var comparing by rememberSaveable { mutableStateOf(BuildConfig.ROAM_CONNECTED) }
                var externalError by remember { mutableStateOf<String?>(null) }
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(externalError) {
                    externalError?.let { snackbar.showSnackbar(it) }
                    externalError = null
                }
                val comparisonModel: ComparisonViewModel =
                    viewModel(
                        factory =
                            viewModelFactory {
                                initializer {
                                    ComparisonViewModel(
                                        container.comparison,
                                        createSavedStateHandle(),
                                    )
                                }
                            }
                    )
                val openUrl: (String) -> Unit = { raw ->
                    val safe = ComparisonLinks.safeBookingUrl(raw)
                    if (safe == null)
                        externalError =
                            "This provider link is unavailable. Refresh the offers and try again."
                    else {
                        try {
                            startActivity(
                                AndroidIntent(AndroidIntent.ACTION_VIEW, Uri.parse(safe))
                                    .addCategory(AndroidIntent.CATEGORY_BROWSABLE)
                            )
                        } catch (_: ActivityNotFoundException) {
                            externalError = "No browser is available to open this provider."
                        } catch (_: SecurityException) {
                            externalError =
                                "This device couldn't open the provider. Please try another browser."
                        }
                    }
                }
                BackHandler(comparing && !BuildConfig.ROAM_CONNECTED) { comparing = false }
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { outerInsets ->
                    Box(
                        Modifier.fillMaxSize().padding(outerInsets).consumeWindowInsets(outerInsets)
                    ) {
                        if (comparing || BuildConfig.ROAM_COMPARISON_ONLY) {
                            ComparisonScreen(
                                comparisonModel,
                                onOpenUrl = openUrl,
                                onBack =
                                    if (BuildConfig.ROAM_COMPARISON_ONLY) null
                                    else ({ comparing = false }),
                                backLabel =
                                    if (BuildConfig.ROAM_CONNECTED) "Your passport"
                                    else "Back to Explore",
                                onAirbnbSearch = { openUrl("https://www.airbnb.com/") },
                            )
                        } else if (!BuildConfig.ROAM_CONNECTED) {
                            val model: RoamViewModel =
                                viewModel(
                                    factory =
                                        viewModelFactory {
                                            initializer {
                                                RoamViewModel(
                                                    container.commerce,
                                                    createSavedStateHandle(),
                                                )
                                            }
                                        }
                                )
                            RoamApp(model, onCompare = { comparing = true })
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                TextButton(onClick = { comparing = true }) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                                    Text("Compare stays")
                                }
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    val configuration = container.configuration
                                    val auth = container.auth
                                    if (configuration == null || auth == null)
                                        ConnectionUnavailable()
                                    else {
                                        val model: AuthViewModel =
                                            viewModel(
                                                factory =
                                                    viewModelFactory {
                                                        initializer {
                                                            AuthViewModel(
                                                                SupabaseAuthActions(auth),
                                                                createSavedStateHandle(),
                                                            )
                                                        }
                                                    }
                                            )
                                        AppSessionHost(
                                            auth,
                                            configuration,
                                            model,
                                            onCompare = { comparing = true },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

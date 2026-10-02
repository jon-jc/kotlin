package com.roam.app

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.roam.core.CommerceService
import com.roam.data.RoamDatabase
import com.roam.data.RoomAccountStore
import com.roam.network.SupabaseAuth
import com.stripe.android.PaymentConfiguration
import java.net.URI

class RoamApplication : Application() {
    val container by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    val commerce by lazy { CommerceService(RoomAccountStore(RoamDatabase.create(application))) }
    val configuration =
        if (BuildConfig.ROAM_CONNECTED) ConnectedConfiguration.fromBuildConfig() else null
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
                if (!BuildConfig.ROAM_CONNECTED) {
                    val model: RoamViewModel =
                        viewModel(
                            factory =
                                viewModelFactory {
                                    initializer {
                                        RoamViewModel(container.commerce, createSavedStateHandle())
                                    }
                                }
                        )
                    RoamApp(model)
                } else {
                    val configuration = container.configuration
                    val auth = container.auth
                    if (configuration == null || auth == null) ConnectionUnavailable()
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
                        AppSessionHost(auth, configuration, model)
                    }
                }
            }
        }
    }
}

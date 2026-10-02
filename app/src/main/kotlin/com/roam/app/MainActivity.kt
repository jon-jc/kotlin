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

class RoamApplication : Application() {
    val container by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    private val database = RoamDatabase.create(application)
    val commerce = CommerceService(RoomAccountStore(database))
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RoamApplication).container
        setContent {
            RoamTheme {
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
            }
        }
    }
}

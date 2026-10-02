package com.roam.app

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.roam.core.CommerceService
import com.roam.data.RoomAccountStore
import com.roam.data.RoamDatabase

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
        setContent { MaterialTheme { Text("Roam · Your world, connected.") } }
    }
}

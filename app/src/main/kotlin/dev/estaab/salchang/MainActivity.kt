package dev.estaab.salchang

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.estaab.salchang.ui.SalchangNavHost
import dev.estaab.salchang.ui.SalchangTheme

/**
 * Single activity hosting the Compose navigation graph. Session view models are scoped to this
 * activity (see `SessionScreen`) so a connection survives navigating back to the host list.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app: SalchangApp = application as SalchangApp
        setContent {
            SalchangTheme { SalchangNavHost(app) }
        }
    }
}

package dev.estaab.salchang.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.estaab.salchang.SalchangApp

/** Route strings of the single-activity navigation graph. */
object Routes {
    const val HOSTS: String = "hosts"
    const val HOST_NEW: String = "hosts/new"
    const val HOST_EDIT: String = "hosts/{hostId}/edit"
    const val SESSION: String = "session/{hostId}"
    const val KEYS: String = "keys"

    const val ARG_HOST_ID: String = "hostId"

    fun hostEdit(hostId: String): String = "hosts/$hostId/edit"
    fun session(hostId: String): String = "session/$hostId"
}

@Composable
fun SalchangNavHost(app: SalchangApp) {
    val navController: NavHostController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOSTS) {
        composable(Routes.HOSTS) {
            HostsScreen(
                repository = app.hostRepository,
                onOpenSession = { id -> navController.navigate(Routes.session(id)) },
                onAddHost = { navController.navigate(Routes.HOST_NEW) },
                onEditHost = { id -> navController.navigate(Routes.hostEdit(id)) },
                onOpenKeys = { navController.navigate(Routes.KEYS) },
            )
        }
        composable(Routes.HOST_NEW) {
            HostEditScreen(
                hostId = null,
                repository = app.hostRepository,
                keyStore = app.keyStore,
                onDone = { navController.popBackStack() },
            )
        }
        composable(
            Routes.HOST_EDIT,
            arguments = listOf(navArgument(Routes.ARG_HOST_ID) { type = NavType.StringType }),
        ) { entry ->
            HostEditScreen(
                hostId = entry.arguments?.getString(Routes.ARG_HOST_ID),
                repository = app.hostRepository,
                keyStore = app.keyStore,
                onDone = { navController.popBackStack() },
            )
        }
        composable(Routes.KEYS) {
            KeysScreen(keyStore = app.keyStore, onBack = { navController.popBackStack() })
        }
        composable(
            Routes.SESSION,
            arguments = listOf(navArgument(Routes.ARG_HOST_ID) { type = NavType.StringType }),
        ) { entry ->
            val hostId: String = entry.arguments?.getString(Routes.ARG_HOST_ID) ?: return@composable
            SessionScreen(hostId = hostId, onBack = { navController.popBackStack() })
        }
    }
}

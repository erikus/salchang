package dev.estaab.salchang.session

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.estaab.salchang.SalchangApp
import dev.estaab.salchang.data.HostProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val CLIPBOARD_LABEL: String = "salchang"

/**
 * Owns the [SessionController] for one host. Scoped to the activity (see `MainActivity`) so the
 * connection survives navigating away from the session screen and configuration changes.
 */
class SessionViewModel(application: Application, val hostId: String) : AndroidViewModel(application) {

    private val _controller: MutableStateFlow<SessionController?> = MutableStateFlow(null)
    /** Null until the host profile has been loaded (or forever if it does not exist). */
    val controller: StateFlow<SessionController?> = _controller.asStateFlow()

    private val _missingProfile: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val missingProfile: StateFlow<Boolean> = _missingProfile.asStateFlow()

    init {
        viewModelScope.launch {
            val app: SalchangApp = getApplication()
            val profile: HostProfile? = app.hostRepository.get(hostId)
            if (profile == null) {
                _missingProfile.value = true
                return@launch
            }
            val controller = SessionController(
                profile = profile,
                keyStore = app.keyStore,
                knownHostsFactory = { prompt -> app.knownHosts(prompt) },
                copyToClipboard = { text -> copyToClipboard(text) },
                loadProbeScript = { loadAsset(PROBE_ASSET_NAME) },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
            _controller.value = controller
            controller.connect()
        }
    }

    /** Reads an app asset fully; blocking, so call it off the main thread. */
    private fun loadAsset(name: String): ByteArray {
        val app: Application = getApplication()
        return app.assets.open(name).use { it.readBytes() }
    }

    private fun copyToClipboard(text: String) {
        val app: Application = getApplication()
        val clipboard: ClipboardManager? = app.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, text))
    }

    override fun onCleared() {
        _controller.value?.destroy()
        _controller.value = null
    }
}

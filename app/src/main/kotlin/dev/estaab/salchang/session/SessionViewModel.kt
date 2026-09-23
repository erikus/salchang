package dev.estaab.salchang.session

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import dev.estaab.salchang.SalchangApp
import dev.estaab.salchang.data.HostProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    /**
     * Whether the app is visible, for the controller's background handling. Process-wide rather
     * than per activity so a session kept alive from the host list behaves the same;
     * `ProcessLifecycleOwner` also debounces the stop that a configuration change causes.
     */
    private val foreground: StateFlow<Boolean> = ProcessLifecycleOwner.get().lifecycle.let { lifecycle ->
        lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

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
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                foreground = foreground,
            )
            _controller.value = controller
            controller.connect()
        }
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

package dev.estaab.salchang.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.estaab.salchang.data.DEFAULT_SSH_PORT
import dev.estaab.salchang.data.DEFAULT_TMUX_BINARY
import dev.estaab.salchang.data.DEFAULT_TMUX_SESSION
import dev.estaab.salchang.data.HostProfile
import dev.estaab.salchang.data.HostRepository
import dev.estaab.salchang.session.SessionPrompt
import dev.estaab.salchang.ssh.HostKeyPrompt
import dev.estaab.salchang.ssh.KnownHosts
import dev.estaab.salchang.ssh.RemoteCommandFailedException
import dev.estaab.salchang.ssh.SshControlTransport
import dev.estaab.salchang.ssh.TmuxAttachTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val FORM_PADDING = 16.dp
private val FIELD_SPACING = 12.dp
private val BROWSE_BUTTON_SPACING = 8.dp
private val BROWSE_LIST_MAX_HEIGHT = 360.dp
private val BROWSE_STATUS_PADDING = 16.dp

private const val MIN_PORT: Int = 1
private const val MAX_PORT: Int = 65535

private const val TMUX_SESSION_HELP: String = "Group or session name passed to new-session -t, e.g. S"
private const val BROWSE_BUTTON_LABEL: String = "Browse…"
private const val BROWSE_SUMMARY_SEPARATOR: String = " · "

/** What the "Browse" dialog of [HostEditScreen] is showing. */
private sealed interface BrowseState {
    /** The one-off SSH command is running. */
    data object Loading : BrowseState

    /** `list-sessions` succeeded; [targets] may be empty. */
    data class Targets(val targets: List<TmuxAttachTarget>) : BrowseState

    /** tmux reported that no server is listening on the profile's socket. */
    data object NoServer : BrowseState

    /** Anything else went wrong (network, auth, host key, parse). */
    data class Error(val message: String) : BrowseState
}

/**
 * Creates (hostId == null) or edits a [HostProfile]. [knownHostsFactory] supplies the TOFU store
 * for the "Browse" lookup of remote tmux sessions, which opens a one-off SSH connection with the
 * form's current values.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    hostId: String?,
    repository: HostRepository,
    knownHostsFactory: (HostKeyPrompt) -> KnownHosts,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loaded: Boolean by remember { mutableStateOf(hostId == null) }
    var name: String by rememberSaveable { mutableStateOf("") }
    var hostname: String by rememberSaveable { mutableStateOf("") }
    var port: String by rememberSaveable { mutableStateOf(DEFAULT_SSH_PORT.toString()) }
    var username: String by rememberSaveable { mutableStateOf("") }
    var tmuxSession: String by rememberSaveable { mutableStateOf(DEFAULT_TMUX_SESSION) }
    var tmuxBinary: String by rememberSaveable { mutableStateOf(DEFAULT_TMUX_BINARY) }
    var socketName: String by rememberSaveable { mutableStateOf("") }
    var socketPath: String by rememberSaveable { mutableStateOf("") }
    var advancedOpen: Boolean by rememberSaveable { mutableStateOf(false) }
    var error: String? by remember { mutableStateOf(null) }

    var browse: BrowseState? by remember { mutableStateOf(null) }
    var browseJob: Job? by remember { mutableStateOf(null) }
    // A StateFlow rather than MutableState because the host-key prompt is raised from sshj's
    // reader thread (see TofuHostKeyVerifier); mirrors SessionController.prompt.
    val promptFlow: MutableStateFlow<SessionPrompt?> = remember { MutableStateFlow(null) }
    val prompt: SessionPrompt? by promptFlow.collectAsState()
    val knownHosts: KnownHosts = remember(knownHostsFactory) {
        knownHostsFactory(HostKeyPrompt { host, keyType, fingerprint ->
            val p = SessionPrompt.HostKey(host, keyType, fingerprint, CompletableDeferred())
            promptFlow.value = p
            try {
                p.reply.await()
            } finally {
                promptFlow.compareAndSet(p, null)
            }
        })
    }

    LaunchedEffect(hostId) {
        if (hostId != null && !loaded) {
            val existing: HostProfile? = repository.get(hostId)
            if (existing != null) {
                name = existing.name
                hostname = existing.hostname
                port = existing.port.toString()
                username = existing.username
                tmuxSession = existing.tmuxSession
                tmuxBinary = existing.tmuxBinary
                socketName = existing.tmuxSocketName ?: ""
                socketPath = existing.tmuxSocketPath ?: ""
                advancedOpen = socketName.isNotEmpty() || socketPath.isNotEmpty() || tmuxBinary != DEFAULT_TMUX_BINARY
            }
            loaded = true
        }
    }

    /**
     * Validates the form and builds the profile it describes, or sets [error] and returns null.
     * Browsing only needs what it takes to run one SSH command, so the name and tmux session
     * (the very field Browse fills in) are only required when [forSave].
     */
    fun buildProfile(forSave: Boolean): HostProfile? {
        val portValue: Int? = port.trim().toIntOrNull()
        error = when {
            forSave && name.isBlank() -> "Name is required"
            hostname.isBlank() -> "Hostname is required"
            username.isBlank() -> "Username is required"
            portValue == null || portValue !in MIN_PORT..MAX_PORT -> "Port must be between $MIN_PORT and $MAX_PORT"
            forSave && tmuxSession.isBlank() -> "tmux session is required"
            tmuxBinary.isBlank() -> "tmux binary is required"
            socketName.isNotBlank() && socketPath.isNotBlank() -> "Set either a socket name or a socket path, not both"
            else -> null
        }
        if (error != null || portValue == null) return null
        return HostProfile(
            id = hostId ?: HostProfile.newId(),
            name = name.trim(),
            hostname = hostname.trim(),
            port = portValue,
            username = username.trim(),
            tmuxSession = tmuxSession.trim(),
            tmuxSocketName = socketName.trim().ifEmpty { null },
            tmuxSocketPath = socketPath.trim().ifEmpty { null },
            tmuxBinary = tmuxBinary.trim(),
        )
    }

    fun save() {
        val profile: HostProfile = buildProfile(forSave = true) ?: return
        scope.launch {
            repository.upsert(profile)
            onDone()
        }
    }

    /** Dismisses the Browse dialog, cancelling the lookup and any prompt it is waiting on. */
    fun closeBrowse() {
        browseJob?.cancel()
        browseJob = null
        when (val p: SessionPrompt? = promptFlow.value) {
            null -> Unit
            is SessionPrompt.HostKey -> p.reply.complete(false)
        }
        browse = null
    }

    /** Runs `tmux list-sessions` on the host described by the form and shows the result. */
    fun browseSessions() {
        val profile: HostProfile = buildProfile(forSave = false) ?: return
        browseJob?.cancel()
        browse = BrowseState.Loading
        browseJob = scope.launch {
            try {
                val stdout: String = SshControlTransport.runOnce(
                    profile, knownHosts, SshControlTransport.buildListSessionsCommand(profile),
                )
                browse = BrowseState.Targets(SshControlTransport.attachTargets(SshControlTransport.parseListSessions(stdout)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteCommandFailedException) {
                browse = if (SshControlTransport.isNoServerError(e)) BrowseState.NoServer else BrowseState.Error(e.message ?: e.toString())
            } catch (e: Exception) {
                browse = BrowseState.Error(e.message ?: e.toString())
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (hostId == null) "New host" else "Edit host") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(FORM_PADDING),
            verticalArrangement = Arrangement.spacedBy(FIELD_SPACING),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                hostname, { hostname = it }, label = { Text("Hostname") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                port, { port = it }, label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    tmuxSession, { tmuxSession = it },
                    label = { Text("tmux session") },
                    supportingText = { Text(TMUX_SESSION_HELP) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { browseSessions() },
                    enabled = hostname.isNotBlank() && username.isNotBlank() && browse == null,
                    modifier = Modifier.padding(start = BROWSE_BUTTON_SPACING),
                ) { Text(BROWSE_BUTTON_LABEL) }
            }

            Row(
                Modifier.fillMaxWidth().clickable { advancedOpen = !advancedOpen },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Advanced", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (advancedOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }
            if (advancedOpen) {
                OutlinedTextField(tmuxBinary, { tmuxBinary = it }, label = { Text("tmux binary") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(socketName, { socketName = it }, label = { Text("Socket name (tmux -L)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(socketPath, { socketPath = it }, label = { Text("Socket path (tmux -S)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDone) { Text("Cancel") }
                Button(onClick = { save() }, enabled = loaded) { Text("Save") }
            }
        }
    }

    browse?.let { state: BrowseState ->
        BrowseSessionsDialog(
            state = state,
            hostname = hostname.trim(),
            onPick = { target -> tmuxSession = target.name; closeBrowse() },
            onDismiss = { closeBrowse() },
        )
    }

    when (val p: SessionPrompt? = prompt) {
        null -> Unit
        is SessionPrompt.HostKey -> HostKeyDialog(
            hostname = p.hostname,
            keyType = p.keyType,
            fingerprintSha256 = p.fingerprintSha256,
            onAccept = { p.reply.complete(true) },
            onReject = { p.reply.complete(false) },
        )
    }
}

/** Lists the session groups / sessions on the host; picking one fills the tmux session field. */
@Composable
private fun BrowseSessionsDialog(
    state: BrowseState,
    hostname: String,
    onPick: (TmuxAttachTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("tmux sessions on $hostname") },
        text = {
            when (state) {
                BrowseState.Loading -> Row(
                    Modifier.fillMaxWidth().padding(BROWSE_STATUS_PADDING),
                    horizontalArrangement = Arrangement.spacedBy(BROWSE_STATUS_PADDING),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text("Connecting…")
                }
                BrowseState.NoServer -> Text("No tmux server running on $hostname")
                is BrowseState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
                is BrowseState.Targets -> if (state.targets.isEmpty()) {
                    Text("The tmux server on $hostname has no sessions")
                } else {
                    Box(Modifier.heightIn(max = BROWSE_LIST_MAX_HEIGHT)) {
                        LazyColumn {
                            items(state.targets, key = { "${it.grouped}:${it.name}" }) { target ->
                                ListItem(
                                    headlineContent = { Text(target.name) },
                                    supportingContent = { Text(attachTargetSummary(target)) },
                                    modifier = Modifier.clickable { onPick(target) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "group · 2 sessions · 3 windows" or "session · 1 window". */
private fun attachTargetSummary(target: TmuxAttachTarget): String {
    val windows: String = plural(target.windows, "window")
    return if (target.grouped) {
        listOf("group", plural(target.sessions, "session"), windows).joinToString(BROWSE_SUMMARY_SEPARATOR)
    } else {
        listOf("session", windows).joinToString(BROWSE_SUMMARY_SEPARATOR)
    }
}

private fun plural(count: Int, noun: String): String = if (count == 1) "$count $noun" else "$count ${noun}s"


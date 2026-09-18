package dev.estaab.salchang.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.estaab.salchang.data.DEFAULT_SSH_PORT
import dev.estaab.salchang.data.DEFAULT_TMUX_BINARY
import dev.estaab.salchang.data.AuthMethod
import dev.estaab.salchang.data.DEFAULT_TMUX_SESSION
import dev.estaab.salchang.data.HostProfile
import dev.estaab.salchang.data.HostRepository
import dev.estaab.salchang.data.KeyInfo
import dev.estaab.salchang.data.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FORM_PADDING = 16.dp
private val FIELD_SPACING = 12.dp
private val RADIO_LABEL_SPACING = 8.dp

private const val MIN_PORT: Int = 1
private const val MAX_PORT: Int = 65535

/** Creates (hostId == null) or edits a [HostProfile]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    hostId: String?,
    repository: HostRepository,
    keyStore: KeyStore,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loaded: Boolean by remember { mutableStateOf(hostId == null) }
    var name: String by rememberSaveable { mutableStateOf("") }
    var hostname: String by rememberSaveable { mutableStateOf("") }
    var port: String by rememberSaveable { mutableStateOf(DEFAULT_SSH_PORT.toString()) }
    var username: String by rememberSaveable { mutableStateOf("") }
    var authMethod: AuthMethod by rememberSaveable { mutableStateOf(AuthMethod.NONE) }
    var keyId: String? by rememberSaveable { mutableStateOf(null) }
    var tmuxSession: String by rememberSaveable { mutableStateOf(DEFAULT_TMUX_SESSION) }
    var tmuxBinary: String by rememberSaveable { mutableStateOf(DEFAULT_TMUX_BINARY) }
    var socketName: String by rememberSaveable { mutableStateOf("") }
    var socketPath: String by rememberSaveable { mutableStateOf("") }
    var advancedOpen: Boolean by rememberSaveable { mutableStateOf(false) }
    var keys: List<KeyInfo> by remember { mutableStateOf(emptyList()) }
    var error: String? by remember { mutableStateOf(null) }

    LaunchedEffect(hostId) {
        keys = withContext(Dispatchers.IO) { keyStore.list() }
        if (hostId != null && !loaded) {
            val existing: HostProfile? = repository.get(hostId)
            if (existing != null) {
                name = existing.name
                hostname = existing.hostname
                port = existing.port.toString()
                username = existing.username
                authMethod = existing.authMethod
                keyId = existing.keyId
                tmuxSession = existing.tmuxSession
                tmuxBinary = existing.tmuxBinary
                socketName = existing.tmuxSocketName ?: ""
                socketPath = existing.tmuxSocketPath ?: ""
                advancedOpen = socketName.isNotEmpty() || socketPath.isNotEmpty() || tmuxBinary != DEFAULT_TMUX_BINARY
            }
            loaded = true
        }
    }

    fun save() {
        val portValue: Int? = port.trim().toIntOrNull()
        error = when {
            name.isBlank() -> "Name is required"
            hostname.isBlank() -> "Hostname is required"
            username.isBlank() -> "Username is required"
            portValue == null || portValue !in MIN_PORT..MAX_PORT -> "Port must be between $MIN_PORT and $MAX_PORT"
            authMethod == AuthMethod.KEY && keyId == null -> "Select an SSH key (or choose None for Tailscale SSH)"
            tmuxSession.isBlank() -> "tmux session is required"
            tmuxBinary.isBlank() -> "tmux binary is required"
            socketName.isNotBlank() && socketPath.isNotBlank() -> "Set either a socket name or a socket path, not both"
            else -> null
        }
        if (error != null || portValue == null) return
        val profile = HostProfile(
            id = hostId ?: HostProfile.newId(),
            name = name.trim(),
            hostname = hostname.trim(),
            port = portValue,
            username = username.trim(),
            authMethod = authMethod,
            keyId = if (authMethod == AuthMethod.KEY) keyId else null,
            tmuxSession = tmuxSession.trim(),
            tmuxSocketName = socketName.trim().ifEmpty { null },
            tmuxSocketPath = socketPath.trim().ifEmpty { null },
            tmuxBinary = tmuxBinary.trim(),
        )
        scope.launch {
            repository.upsert(profile)
            onDone()
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
            AuthMethodGroup(selected = authMethod, onSelect = { authMethod = it })
            if (authMethod == AuthMethod.KEY) KeyDropdown(keys = keys, selectedId = keyId, onSelect = { keyId = it })
            OutlinedTextField(tmuxSession, { tmuxSession = it }, label = { Text("tmux session") }, singleLine = true, modifier = Modifier.fillMaxWidth())

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
}

/** Radio group for [HostProfile.authMethod]. */
@Composable
private fun AuthMethodGroup(selected: AuthMethod, onSelect: (AuthMethod) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        Text("Authentication", style = MaterialTheme.typography.titleSmall)
        AuthMethod.entries.forEach { method ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = method == selected, onClick = { onSelect(method) }, role = Role.RadioButton),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = method == selected, onClick = null)
                Text(authMethodLabel(method), modifier = Modifier.padding(start = RADIO_LABEL_SPACING))
            }
        }
    }
}

private fun authMethodLabel(method: AuthMethod): String = when (method) {
    AuthMethod.NONE -> "None (Tailscale SSH)"
    AuthMethod.KEY -> "SSH key"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyDropdown(keys: List<KeyInfo>, selectedId: String?, onSelect: (String?) -> Unit) {
    var expanded: Boolean by remember { mutableStateOf(false) }
    val selected: KeyInfo? = keys.firstOrNull { it.id == selectedId }
    val label: String = when {
        selected != null -> selected.name
        selectedId != null -> "(missing key)"
        keys.isEmpty() -> "No keys yet: add one under Keys"
        else -> "Select a key"
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("SSH key") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            keys.forEach { key ->
                DropdownMenuItem(
                    text = { Text("${key.name}  ${key.fingerprintSha256}", maxLines = 1) },
                    onClick = { onSelect(key.id); expanded = false },
                )
            }
        }
    }
}

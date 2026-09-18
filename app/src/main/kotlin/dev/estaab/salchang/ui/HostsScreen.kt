package dev.estaab.salchang.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.estaab.salchang.data.AuthMethod
import dev.estaab.salchang.data.HostProfile
import dev.estaab.salchang.data.HostRepository
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState

private val EMPTY_HINT_PADDING = 32.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    repository: HostRepository,
    onOpenSession: (String) -> Unit,
    onAddHost: () -> Unit,
    onEditHost: (String) -> Unit,
    onOpenKeys: () -> Unit,
) {
    val hosts: List<HostProfile> by repository.hosts.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var pendingDelete: HostProfile? by remember { mutableStateOf(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("salchang") },
                actions = {
                    IconButton(onClick = onOpenKeys) { Icon(Icons.Default.Key, contentDescription = "Keys") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddHost) { Icon(Icons.Default.Add, contentDescription = "Add host") }
        },
    ) { padding ->
        if (hosts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(EMPTY_HINT_PADDING), contentAlignment = Alignment.Center) {
                Text(
                    "No hosts yet. Tap + to add a tmux host. Tailscale SSH needs no key; " +
                        "otherwise add one under Keys first.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(hosts, key = { it.id }) { host ->
                    HostRow(
                        host = host,
                        onClick = { onOpenSession(host.id) },
                        onEdit = { onEditHost(host.id) },
                        onDelete = { pendingDelete = host },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    pendingDelete?.let { host ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${host.name}?") },
            text = { Text("The saved host profile is removed. Keys and known hosts are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { repository.delete(host.id) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun authLabel(method: AuthMethod): String = when (method) {
    AuthMethod.NONE -> "tailscale ssh"
    AuthMethod.KEY -> "key"
}

@Composable
private fun HostRow(host: HostProfile, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menuOpen: Boolean by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        headlineContent = { Text(host.name) },
        supportingContent = {
            Column {
                Text("${host.username}@${host.hostname}:${host.port}")
                Text("tmux session: ${host.tmuxSession}", style = MaterialTheme.typography.bodySmall)
                Text(
                    authLabel(host.authMethod),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        },
    )
}

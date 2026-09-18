package dev.estaab.salchang.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.estaab.salchang.data.KeyImportException
import dev.estaab.salchang.data.KeyInfo
import dev.estaab.salchang.data.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private val EMPTY_HINT_PADDING = 32.dp
private val DIALOG_SPACING = 8.dp
private val HELP_PADDING = 16.dp

/** Private key files are small; refuse anything larger to avoid reading a random big file. */
private const val MAX_KEY_FILE_BYTES: Int = 64 * 1024

private const val CLIPBOARD_LABEL: String = "public key"
private const val SHARE_MIME_TYPE: String = "text/plain"
private const val SHARE_CHOOSER_TITLE: String = "Share public key"

private const val KEYS_HELP_TEXT: String =
    "Generate a key here, then add its public key to ~/.ssh/authorized_keys on the host " +
        "(Copy or Share it, e.g. via Tailscale SSH). To use an existing key instead, get the " +
        "private key file onto this phone (Taildrop, USB, adb push) and Import it."

/** MIME types offered to the document picker for private key files. */
private val KEY_FILE_MIME_TYPES: Array<String> = arrayOf("*/*")

/** Which dialog the keys screen is showing. */
private sealed interface KeysDialog {
    data object GenerateName : KeysDialog
    data class ImportName(val pemText: String) : KeysDialog
    data class ImportPassphrase(val name: String, val pemText: String) : KeysDialog
    data class Show(val key: KeyInfo) : KeysDialog
    data class ConfirmDelete(val key: KeyInfo) : KeysDialog
    data class Error(val message: String) : KeysDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(keyStore: KeyStore, onBack: () -> Unit) {
    val context: Context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keys: List<KeyInfo> by remember { mutableStateOf(emptyList()) }
    var dialog: KeysDialog? by remember { mutableStateOf(null) }

    suspend fun reload() {
        keys = withContext(Dispatchers.IO) { keyStore.list() }
    }

    LaunchedEffect(Unit) { reload() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text: String? = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes: ByteArray = stream.readBytes()
                        if (bytes.size > MAX_KEY_FILE_BYTES) null else String(bytes, Charsets.UTF_8)
                    }
                } catch (e: IOException) {
                    null
                }
            }
            dialog = if (text == null) KeysDialog.Error("Could not read that file (or it is too large to be a key).")
            else KeysDialog.ImportName(text)
        }
    }

    fun runImport(name: String, pemText: String, passphrase: CharArray?) {
        scope.launch {
            val result: Result<KeyInfo> = withContext(Dispatchers.IO) {
                runCatching { keyStore.importFromText(name, pemText, passphrase) }
            }
            passphrase?.fill('\u0000')
            result.fold(
                onSuccess = { dialog = null; reload() },
                onFailure = { e ->
                    dialog = if (e is KeyImportException && passphrase == null && e.message?.contains("passphrase") == true) {
                        KeysDialog.ImportPassphrase(name, pemText)
                    } else {
                        KeysDialog.Error(e.message ?: "Import failed")
                    }
                },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = { dialog = KeysDialog.GenerateName }) { Text("Generate") }
                    TextButton(onClick = { importLauncher.launch(KEY_FILE_MIME_TYPES) }) { Text("Import") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                KEYS_HELP_TEXT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(HELP_PADDING),
            )
            HorizontalDivider()
            if (keys.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(EMPTY_HINT_PADDING), contentAlignment = Alignment.Center) {
                    Text("No keys yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(keys, key = { it.id }) { key ->
                    ListItem(
                        modifier = Modifier.fillMaxWidth().clickable { dialog = KeysDialog.Show(key) },
                        headlineContent = { Text(key.name) },
                        supportingContent = { Text(key.fingerprintSha256, style = MonoTextStyle) },
                        trailingContent = { if (key.encrypted) Text("encrypted", style = MaterialTheme.typography.labelSmall) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    when (val d: KeysDialog? = dialog) {
        null -> Unit
        is KeysDialog.GenerateName -> NameDialog(
            title = "Generate ed25519 key",
            confirmLabel = "Generate",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                scope.launch {
                    val result: Result<KeyInfo> = withContext(Dispatchers.IO) { runCatching { keyStore.generateEd25519(name) } }
                    result.fold(
                        onSuccess = { key -> reload(); dialog = KeysDialog.Show(key) },
                        onFailure = { e -> dialog = KeysDialog.Error(e.message ?: "Generation failed") },
                    )
                }
            },
        )
        is KeysDialog.ImportName -> NameDialog(
            title = "Name for the imported key",
            confirmLabel = "Import",
            onDismiss = { dialog = null },
            onConfirm = { name -> runImport(name, d.pemText, null) },
        )
        is KeysDialog.ImportPassphrase -> PassphraseDialog(
            title = "Passphrase for ${d.name}",
            message = "This key format needs the passphrase to read the public key during import.",
            onDismiss = { dialog = null },
            onConfirm = { passphrase -> runImport(d.name, d.pemText, passphrase) },
        )
        is KeysDialog.Show -> KeyDetailsDialog(
            key = d.key,
            onCopy = {
                val clipboard: ClipboardManager? = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, d.key.publicKeyOpenSsh))
            },
            onShare = {
                val send = Intent(Intent.ACTION_SEND).setType(SHARE_MIME_TYPE).putExtra(Intent.EXTRA_TEXT, d.key.publicKeyOpenSsh)
                context.startActivity(Intent.createChooser(send, SHARE_CHOOSER_TITLE))
            },
            onDelete = { dialog = KeysDialog.ConfirmDelete(d.key) },
            onDismiss = { dialog = null },
        )
        is KeysDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete ${d.key.name}?") },
            text = { Text("Hosts using this key will fail to connect until another key is selected.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { keyStore.delete(d.key.id) }
                        dialog = null
                        reload()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        is KeysDialog.Error -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Error") },
            text = { Text(d.message) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name: String by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun KeyDetailsDialog(key: KeyInfo, onCopy: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(key.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(DIALOG_SPACING)) {
                Text("Fingerprint", style = MaterialTheme.typography.labelMedium)
                Text(key.fingerprintSha256, style = MonoTextStyle)
                Text("authorized_keys line", style = MaterialTheme.typography.labelMedium)
                Text(key.publicKeyOpenSsh, style = MonoTextStyle)
                Text(
                    "Append this line to ~/.ssh/authorized_keys on the tmux host.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onCopy) { Text("Copy") }
                TextButton(onClick = onShare) { Text("Share") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

package dev.estaab.salchang.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelStoreOwner
import dev.estaab.salchang.SalchangApp
import dev.estaab.salchang.meta.WindowMeta
import dev.estaab.salchang.session.ConnectionState
import dev.estaab.salchang.session.SessionController
import dev.estaab.salchang.session.SessionPrompt
import dev.estaab.salchang.session.SessionViewModel
import dev.estaab.salchang.ssh.extractHttpsUrl
import dev.estaab.salchang.tmuxctl.TmuxState
import dev.estaab.salchang.tmuxctl.TmuxWindow

private val TAB_PADDING_H = 12.dp
private val TAB_PADDING_V = 10.dp
private val META_DOT_SIZE = 6.dp
private val META_DOT_SPACING = 4.dp
private val BANNER_PADDING = 8.dp
private val BANNER_MESSAGE_SPACING = 4.dp
private val PREFIX_CHIP_PADDING_H = 8.dp
private val PREFIX_CHIP_PADDING_V = 2.dp
private val PREFIX_CHIP_MARGIN = 8.dp

/** Shown in the top bar while the tmux prefix key is armed (see `SessionController.prefixArmed`). */
private const val PREFIX_CHIP_LABEL: String = "PREFIX"

private const val BANNER_URL_CLIPBOARD_LABEL: String = "url"

private const val SUB_TAB_TERMINAL: Int = 0
private const val SUB_TAB_INFO: Int = 1

/** ViewModel key prefix; one [SessionViewModel] per host, scoped to the activity. */
private const val VIEW_MODEL_KEY_PREFIX: String = "session:"

/** Which dialog the window tab long-press opened. */
private sealed interface WindowDialog {
    data class Rename(val window: TmuxWindow) : WindowDialog
    data class Kill(val window: TmuxWindow) : WindowDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(hostId: String, onBack: () -> Unit) {
    val activity: Activity = LocalActivity.current ?: return
    val app: SalchangApp = activity.application as SalchangApp
    val viewModel: SessionViewModel = viewModel(
        viewModelStoreOwner = activity as ViewModelStoreOwner,
        key = VIEW_MODEL_KEY_PREFIX + hostId,
    ) { SessionViewModel(app, hostId) }
    val controller: SessionController? by viewModel.controller.collectAsState()
    val missingProfile: Boolean by viewModel.missingProfile.collectAsState()

    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val current: SessionController? = controller
    when {
        missingProfile -> MissingProfile(onBack)
        current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> SessionContent(current, onBack)
    }
}

@Composable
private fun MissingProfile(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(BANNER_PADDING), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("This host no longer exists.")
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionContent(controller: SessionController, onBack: () -> Unit) {
    val connection: ConnectionState by controller.connectionState.collectAsState()
    val tmux: TmuxState by controller.tmuxState.collectAsState()
    val prompt: SessionPrompt? by controller.prompt.collectAsState()
    val lastError: String? by controller.lastError.collectAsState()
    val prefixArmed: Boolean by controller.prefixArmed.collectAsState()
    val snackbar: SnackbarHostState = remember { SnackbarHostState() }
    var subTab: Int by rememberSaveable { mutableIntStateOf(SUB_TAB_TERMINAL) }
    var windowDialog: WindowDialog? by remember { mutableStateOf(null) }

    LaunchedEffect(lastError) {
        val message: String = lastError ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        controller.clearError()
    }

    val windows: List<TmuxWindow> = tmux.windows
    val activeWindow: TmuxWindow? = windows.firstOrNull { it.id == tmux.activeWindowId } ?: windows.firstOrNull()

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(controller.profile.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (prefixArmed) PrefixChip()
                    when (connection) {
                        is ConnectionState.Connected -> TextButton(onClick = { controller.disconnect() }) { Text("Disconnect") }
                        is ConnectionState.Connecting, is ConnectionState.NeedsPassphrase -> Unit
                        else -> TextButton(onClick = { controller.connect() }) { Text("Reconnect") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ConnectionBanner(connection)
            if (windows.isNotEmpty()) {
                WindowTabs(
                    windows = windows,
                    activeWindowId = activeWindow?.id,
                    parseMeta = controller::parseMeta,
                    onSelect = { controller.selectWindow(it.id) },
                    onLongPress = { windowDialog = WindowDialog.Rename(it) },
                    onNew = { controller.newWindow() },
                )
                SecondaryTabRow(selectedTabIndex = subTab) {
                    Tab(selected = subTab == SUB_TAB_TERMINAL, onClick = { subTab = SUB_TAB_TERMINAL }, text = { Text("Terminal") })
                    Tab(selected = subTab == SUB_TAB_INFO, onClick = { subTab = SUB_TAB_INFO }, text = { Text("Info") })
                }
            }
            if (activeWindow != null) {
                when (subTab) {
                    SUB_TAB_TERMINAL -> TerminalTab(controller, activeWindow, Modifier.weight(1f))
                    else -> InfoTab(activeWindow, controller::parseMeta, Modifier.weight(1f))
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        when (connection) {
                            is ConnectionState.Connected -> "No windows"
                            else -> "Not connected"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
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
        is SessionPrompt.Passphrase -> PassphraseDialog(
            title = "Unlock ${p.keyName}",
            message = null,
            onDismiss = { p.reply.complete(null) },
            onConfirm = { p.reply.complete(it) },
        )
    }

    when (val d: WindowDialog? = windowDialog) {
        null -> Unit
        is WindowDialog.Rename -> WindowActionsDialog(
            window = d.window,
            onRename = { name -> controller.renameWindow(d.window.id, name); windowDialog = null },
            onKill = { windowDialog = WindowDialog.Kill(d.window) },
            onDismiss = { windowDialog = null },
        )
        is WindowDialog.Kill -> AlertDialog(
            onDismissRequest = { windowDialog = null },
            title = { Text("Kill window ${d.window.index}:${d.window.name}?") },
            text = { Text("All panes and their processes in this window are terminated.") },
            confirmButton = {
                TextButton(onClick = { controller.killWindow(d.window.id); windowDialog = null }) { Text("Kill") }
            },
            dismissButton = { TextButton(onClick = { windowDialog = null }) { Text("Cancel") } },
        )
    }
}

/** Small "PREFIX" badge: the next key goes to the tmux prefix table instead of the pane. */
@Composable
private fun PrefixChip() {
    Text(
        PREFIX_CHIP_LABEL,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .padding(end = PREFIX_CHIP_MARGIN)
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
            .padding(horizontal = PREFIX_CHIP_PADDING_H, vertical = PREFIX_CHIP_PADDING_V),
    )
}

@Composable
private fun ConnectionBanner(connection: ConnectionState) {
    val (text: String?, isError: Boolean) = when (val c: ConnectionState = connection) {
        is ConnectionState.Connecting -> "Connecting..." to false
        is ConnectionState.NeedsPassphrase -> "Waiting for passphrase" to false
        is ConnectionState.Connected -> null to false
        is ConnectionState.Disconnected -> (c.reason?.let { "Disconnected: $it" } ?: "Disconnected") to false
        is ConnectionState.Failed -> "Connection failed: ${c.error.message ?: c.error.javaClass.simpleName}" to true
    }
    if (text == null) return
    val message: String? = (connection as? ConnectionState.Connecting)?.message?.takeIf { it.isNotBlank() }
    val background = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().background(background).padding(BANNER_PADDING)) {
        Text(text, color = foreground, style = MaterialTheme.typography.bodyMedium)
        if (message != null) BannerMessage(message, foreground)
    }
}

/**
 * The connecting message (SSH auth banner) with its first `https://` URL, if any, rendered as
 * a tappable link that opens the browser, plus a Copy button for that URL. Tailscale SSH
 * check mode sends "visit https://login.tailscale.com/a/..." here.
 */
@Composable
private fun BannerMessage(message: String, foreground: Color) {
    val context: Context = LocalContext.current
    val url: String? = extractHttpsUrl(message)
    Column(Modifier.padding(top = BANNER_MESSAGE_SPACING)) {
        if (url == null) {
            Text(message, color = foreground, style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val linkColor = MaterialTheme.colorScheme.primary
        val annotated = buildAnnotatedString {
            val start: Int = message.indexOf(url)
            append(message.substring(0, start))
            withLink(
                LinkAnnotation.Url(
                    url = url,
                    styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { openUrl(context, url) },
                ),
            ) { append(url) }
            append(message.substring(start + url.length))
        }
        Text(annotated, color = foreground, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { openUrl(context, url) }) { Text("Open") }
            TextButton(onClick = { copyToClipboard(context, url) }) { Text("Copy") }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        // No browser installed; the Copy button is the fallback.
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard: ClipboardManager? = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText(BANNER_URL_CLIPBOARD_LABEL, text))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun WindowTabs(
    windows: List<TmuxWindow>,
    activeWindowId: String?,
    parseMeta: (String) -> WindowMeta,
    onSelect: (TmuxWindow) -> Unit,
    onLongPress: (TmuxWindow) -> Unit,
    onNew: () -> Unit,
) {
    val selectedIndex: Int = windows.indexOfFirst { it.id == activeWindowId }.coerceAtLeast(0)
    PrimaryScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 0.dp) {
        windows.forEach { window ->
            val hasClaude: Boolean = window.meta.values.any { parseMeta(it) is WindowMeta.ClaudeCode }
            val selected: Boolean = window.id == activeWindowId
            Row(
                Modifier
                    .combinedClickable(onClick = { onSelect(window) }, onLongClick = { onLongPress(window) })
                    .padding(horizontal = TAB_PADDING_H, vertical = TAB_PADDING_V),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${window.index}:${window.name}",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (hasClaude) {
                    Box(
                        Modifier
                            .padding(start = META_DOT_SPACING)
                            .size(META_DOT_SIZE)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary),
                    )
                }
            }
        }
        Tab(selected = false, onClick = onNew, text = { Text("+") })
    }
}

@Composable
private fun WindowActionsDialog(window: TmuxWindow, onRename: (String) -> Unit, onKill: () -> Unit, onDismiss: () -> Unit) {
    var name: String by remember { mutableStateOf(window.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Window ${window.index}") },
        text = {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            Row {
                TextButton(onClick = onKill) { Text("Kill", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { onRename(name.trim()) }, enabled = name.isNotBlank()) { Text("Rename") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

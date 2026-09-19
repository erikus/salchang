package dev.estaab.salchang.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.collectAsState
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.estaab.salchang.session.ConnectionState
import com.termux.view.TerminalViewClient
import dev.estaab.salchang.session.PaneTerminal
import dev.estaab.salchang.session.SessionController
import dev.estaab.salchang.session.TerminalSizing
import dev.estaab.salchang.tmuxctl.TmuxPane
import dev.estaab.salchang.tmuxctl.TmuxWindow
import kotlin.math.ceil

private val CHIP_ROW_PADDING = 4.dp
private val CHIP_SPACING = 4.dp

/** Terminal tab for one tmux window: pane chips, the terminal view and the extra keys bar. */
@Composable
fun TerminalTab(controller: SessionController, window: TmuxWindow, modifier: Modifier = Modifier) {
    val panes: List<TmuxPane> = window.panes
    val activePane: TmuxPane? = panes.firstOrNull { it.id == window.activePaneId } ?: panes.firstOrNull()
    val modifiers: ModifierState = remember { ModifierState() }

    Column(modifier.fillMaxSize()) {
        if (panes.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(CHIP_ROW_PADDING),
            ) {
                panes.forEach { pane ->
                    FilterChip(
                        selected = pane.id == activePane?.id,
                        onClick = { controller.selectPane(pane.id) },
                        label = { Text("${pane.id} ${pane.currentCommand}".trim(), maxLines = 1) },
                        modifier = Modifier.padding(horizontal = CHIP_SPACING / 2),
                    )
                }
            }
        }
        var view: TerminalView? by remember { mutableStateOf(null) }
        Box(Modifier.weight(1f).fillMaxWidth().background(TerminalBackground)) {
            if (activePane != null) {
                PaneView(
                    controller = controller,
                    pane = activePane,
                    modifiers = modifiers,
                    onViewCreated = { view = it },
                )
            }
        }
        ExtraKeysBar(
            keys = DEFAULT_EXTRA_KEYS,
            ctrlActive = modifiers.ctrl,
            altActive = modifiers.alt,
            onKey = { key -> view?.let { sendExtraKey(it, key, modifiers) } },
        )
    }
}

/** Sticky CTRL/ALT toggles shared by the extra keys bar and the [TerminalViewClient]. */
class ModifierState {
    var ctrl: Boolean by mutableStateOf(false)
    var alt: Boolean by mutableStateOf(false)

    /** Reads and clears both toggles. */
    fun consume(): Pair<Boolean, Boolean> {
        val result = Pair(ctrl, alt)
        ctrl = false
        alt = false
        return result
    }
}

private fun sendExtraKey(view: TerminalView, key: ExtraKey, modifiers: ModifierState) {
    if (view.currentSession == null) return
    when (key) {
        is ExtraKey.Modifier -> when (key.kind) {
            ModifierKind.CTRL -> modifiers.ctrl = !modifiers.ctrl
            ModifierKind.ALT -> modifiers.alt = !modifiers.alt
        }
        is ExtraKey.Code -> {
            val (ctrl: Boolean, alt: Boolean) = modifiers.consume()
            var keyMod = 0
            if (ctrl) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
            if (alt) keyMod = keyMod or KeyHandler.KEYMOD_ALT
            view.handleKeyCode(key.keyCode, keyMod)
        }
        is ExtraKey.Char -> {
            val (ctrl: Boolean, alt: Boolean) = modifiers.consume()
            view.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, key.codePoint, ctrl, alt)
        }
    }
}

/**
 * The [TerminalView] for one pane.
 *
 * Sizing: the emulator grid is pinned to the tmux pane size (`TerminalSession.setFixedSize`), so
 * the view only decides how big the cells are. The text size is the largest value in
 * [TerminalSizing.MIN_TEXT_SIZE_SP]..[TerminalSizing.MAX_TEXT_SIZE_SP] whose `paneCols * charWidth`
 * fits the viewport width; if even the minimum does not fit the view is widened past the viewport
 * and becomes horizontally scrollable. Separately, the viewport size at the preferred text size is
 * converted to a cols x rows grid and reported to tmux via [SessionController.onViewportGridChanged].
 */
@Composable
private fun PaneView(
    controller: SessionController,
    pane: TmuxPane,
    modifiers: ModifierState,
    onViewCreated: (TerminalView?) -> Unit,
) {
    val density: Density = LocalDensity.current
    val terminals: Map<String, PaneTerminal> by controller.terminals.collectAsState()
    val terminal: PaneTerminal? = terminals[pane.id]
    val connection: ConnectionState by controller.connectionState.collectAsState()
    var viewport: IntSize by remember { mutableStateOf(IntSize.Zero) }

    // Keyed on the connection too: a reconnect drops every terminal, so the pane needs a new one.
    LaunchedEffect(pane.id, connection) { controller.ensureTerminal(pane.id) }

    val paneCols: Int = pane.width.coerceAtLeast(1)
    val minPx: Int = with(density) { TerminalSizing.MIN_TEXT_SIZE_SP.sp.roundToPx() }
    val maxPx: Int = with(density) { TerminalSizing.MAX_TEXT_SIZE_SP.sp.roundToPx() }
    val preferredPx: Int = with(density) { TerminalSizing.PREFERRED_TEXT_SIZE_SP.sp.roundToPx() }

    val textSizePx: Int = remember(paneCols, viewport.width, minPx, maxPx) {
        TerminalSizing.fitTextSize(paneCols, viewport.width, minPx, maxPx) { size -> FontMetrics.at(size).charWidth }
    }
    val charWidthPx: Float = remember(textSizePx) { FontMetrics.at(textSizePx).charWidth }
    val contentWidthPx: Int = maxOf(viewport.width, ceil(paneCols * charWidthPx).toInt())

    LaunchedEffect(viewport, preferredPx) {
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        val metrics: FontMetrics = FontMetrics.at(preferredPx)
        val grid: TerminalSizing.Grid = TerminalSizing.gridFor(
            viewWidthPx = viewport.width,
            viewHeightPx = viewport.height,
            charWidthPx = metrics.charWidth,
            lineSpacingPx = metrics.lineSpacing,
            lineSpacingAndAscentPx = metrics.lineSpacingAndAscent,
        )
        controller.onViewportGridChanged(grid.cols, grid.rows)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .horizontalScroll(rememberScrollState()),
    ) {
        if (terminal == null || viewport.width <= 0) return@Box
        var textSizeSet: Int by remember { mutableIntStateOf(-1) }
        // The terminal whose screenListener currently points at the view (plain holder, not snapshot state).
        val listening: Array<PaneTerminal?> = remember { arrayOfNulls<PaneTerminal>(1) }
        AndroidView(
            factory = { context: Context ->
                TerminalView(context, null).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    // The view is recreated whenever the active pane changes (its emulator does not
                    // exist until ensureTerminal ran). Without this, Android moves focus to the first
                    // focusable Compose node (the back button) and typed keys go nowhere.
                    addOnAttachStateChangeListener(FocusOnAttach)
                    setTerminalViewClient(PaneViewClient(this, modifiers))
                    onViewCreated(this)
                }
            },
            modifier = Modifier.width(with(density) { contentWidthPx.toDp() }).fillMaxHeight(),
            update = { view: TerminalView ->
                if (textSizeSet != textSizePx) {
                    view.setTextSize(textSizePx)
                    textSizeSet = textSizePx
                }
                val session: TerminalSession = terminal.session
                if (view.currentSession !== session) {
                    view.attachSession(session)
                }
                val previous: PaneTerminal? = listening[0]
                if (previous !== terminal) {
                    previous?.sessionClient?.screenListener = null
                    listening[0] = terminal
                }
                terminal.sessionClient.screenListener = { view.onScreenUpdated() }
                view.onScreenUpdated()
            },
            onRelease = { view: TerminalView ->
                listening[0]?.sessionClient?.screenListener = null
                listening[0] = null
                onViewCreated(null)
            },
        )
    }
}

/** Gives a freshly attached terminal view keyboard focus so it receives typed keys immediately. */
private object FocusOnAttach : View.OnAttachStateChangeListener {
    override fun onViewAttachedToWindow(v: View) {
        v.requestFocus()
    }

    override fun onViewDetachedFromWindow(v: View) = Unit
}

/**
 * Cell metrics of the monospace font at a pixel text size, computed the same way as
 * `TerminalRenderer` does (which keeps its ascent package-private).
 */
private class FontMetrics(val charWidth: Float, val lineSpacing: Int, val lineSpacingAndAscent: Int) {
    companion object {
        fun at(textSizePx: Int): FontMetrics {
            val paint = Paint()
            paint.typeface = Typeface.MONOSPACE
            paint.isAntiAlias = true
            paint.textSize = textSizePx.toFloat()
            val lineSpacing: Int = ceil(paint.fontSpacing).toInt()
            val ascent: Int = ceil(paint.ascent()).toInt()
            return FontMetrics(paint.measureText("X"), lineSpacing, lineSpacing + ascent)
        }
    }
}

/** Glue between [TerminalView] and the app: soft keyboard on tap, sticky CTRL/ALT, logging. */
private class PaneViewClient(private val view: TerminalView, private val modifiers: ModifierState) : TerminalViewClient {
    override fun onScale(scale: Float): Float = 1f

    override fun onSingleTapUp(e: MotionEvent) {
        view.requestFocus()
        val imm: InputMethodManager? = view.context.getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(view, 0)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = modifiers.ctrl
    override fun readAltKey(): Boolean = modifiers.alt
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        // A typed character consumes the sticky modifiers, like the Termux extra keys.
        modifiers.consume()
        return false
    }

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message ?: "", e) }
}

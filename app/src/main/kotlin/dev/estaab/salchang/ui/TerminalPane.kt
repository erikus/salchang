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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import dev.estaab.salchang.session.TerminalSizing.CellMetrics
import dev.estaab.salchang.tmuxctl.TmuxPane
import dev.estaab.salchang.tmuxctl.TmuxWindow
import kotlin.math.ceil

private val CHIP_ROW_PADDING = 4.dp
private val CHIP_SPACING = 4.dp

/** Terminal tab for one tmux window: pane chips, the terminal view and the extra keys bar ([extraKeys]). */
@Composable
fun TerminalTab(
    controller: SessionController,
    window: TmuxWindow,
    extraKeys: List<List<ExtraKey>>,
    modifier: Modifier = Modifier,
) {
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
            rows = extraKeys,
            ctrlActive = modifiers.ctrl,
            altActive = modifiers.alt,
            shiftActive = modifiers.shift,
            onKey = { key -> view?.let { sendExtraKey(it, key, modifiers) } },
        )
    }
}

/** Sticky CTRL/ALT/SHIFT toggles shared by the extra keys bar and the [TerminalViewClient]. */
class ModifierState {
    var ctrl: Boolean by mutableStateOf(false)
    var alt: Boolean by mutableStateOf(false)
    var shift: Boolean by mutableStateOf(false)

    /** The toggles read by [consume]. */
    data class Held(val ctrl: Boolean, val alt: Boolean, val shift: Boolean) {
        /** `KeyHandler.KEYMOD_*` bits for `TerminalView.handleKeyCode`. */
        val keyMod: Int
            get() = (if (ctrl) KeyHandler.KEYMOD_CTRL else 0) or
                (if (alt) KeyHandler.KEYMOD_ALT else 0) or
                (if (shift) KeyHandler.KEYMOD_SHIFT else 0)
    }

    /** Reads and clears all toggles. */
    fun consume(): Held {
        val result = Held(ctrl, alt, shift)
        ctrl = false
        alt = false
        shift = false
        return result
    }
}

private fun sendExtraKey(view: TerminalView, key: ExtraKey, modifiers: ModifierState) {
    if (view.currentSession == null) return
    when (key) {
        is ExtraKey.Modifier -> when (key.kind) {
            ModifierKind.CTRL -> modifiers.ctrl = !modifiers.ctrl
            ModifierKind.ALT -> modifiers.alt = !modifiers.alt
            ModifierKind.SHIFT -> modifiers.shift = !modifiers.shift
        }
        is ExtraKey.Code -> view.handleKeyCode(key.keyCode, modifiers.consume().keyMod)
        is ExtraKey.Char -> {
            val held: ModifierState.Held = modifiers.consume()
            // Same as TerminalView does for typed text while the sticky shift is on.
            val codePoint: Int = if (held.shift) Character.toUpperCase(key.codePoint) else key.codePoint
            view.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, codePoint, held.ctrl, held.alt)
        }
    }
}

/**
 * The [TerminalView] for one pane.
 *
 * Sizing: the emulator grid is pinned to the tmux pane size (`TerminalSession.setFixedSize`), so
 * the view only decides how big the cells are. The text size is the largest value in
 * [TerminalSizing.MIN_TEXT_SIZE_SP]..[TerminalSizing.MAX_TEXT_SIZE_SP] at which the whole
 * `paneCols x paneRows` grid fits the viewport. If even the minimum does not fit, the view is
 * widened past the viewport and becomes horizontally scrollable, and/or made taller than the
 * viewport and aligned to its bottom, so the rows around the cursor stay visible and nothing is
 * drawn under the extra keys bar. Separately, the viewport size at the preferred text size is
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
    val paneRows: Int = pane.height.coerceAtLeast(1)
    val minPx: Int = with(density) { TerminalSizing.MIN_TEXT_SIZE_SP.sp.roundToPx() }
    val maxPx: Int = with(density) { TerminalSizing.MAX_TEXT_SIZE_SP.sp.roundToPx() }
    val preferredPx: Int = with(density) { TerminalSizing.PREFERRED_TEXT_SIZE_SP.sp.roundToPx() }

    val textSizePx: Int = remember(paneCols, paneRows, viewport, minPx, maxPx) {
        TerminalSizing.fitTextSize(paneCols, paneRows, viewport.width, viewport.height, minPx, maxPx, ::cellMetricsAt)
    }
    val metrics: CellMetrics = remember(textSizePx) { cellMetricsAt(textSizePx) }
    // The view is at least the viewport; larger only when the pane does not fit at the minimum text size.
    val contentWidthPx: Int = maxOf(viewport.width, ceil(paneCols * metrics.charWidth).toInt())
    val contentHeightPx: Int = maxOf(viewport.height, metrics.heightOf(paneRows))

    LaunchedEffect(viewport, preferredPx) {
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        val preferred: CellMetrics = cellMetricsAt(preferredPx)
        val grid: TerminalSizing.Grid = TerminalSizing.gridFor(
            viewWidthPx = viewport.width,
            viewHeightPx = viewport.height,
            charWidthPx = preferred.charWidth,
            lineSpacingPx = preferred.lineSpacing,
            lineSpacingAndAscentPx = preferred.lineSpacingAndAscent,
        )
        controller.onViewportGridChanged(grid.cols, grid.rows)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            // Compose does not clip by default: a view taller than the viewport would paint over the tabs above.
            .clipToBounds()
            .horizontalScroll(rememberScrollState()),
        // A pane taller than the viewport shows its bottom rows, where the prompt and cursor are.
        contentAlignment = Alignment.BottomStart,
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
            modifier = Modifier.width(with(density) { contentWidthPx.toDp() }).height(with(density) { contentHeightPx.toDp() }),
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
private fun cellMetricsAt(textSizePx: Int): CellMetrics {
    val paint = Paint()
    paint.typeface = Typeface.MONOSPACE
    paint.isAntiAlias = true
    paint.textSize = textSizePx.toFloat()
    val lineSpacing: Int = ceil(paint.fontSpacing).toInt()
    val ascent: Int = ceil(paint.ascent()).toInt()
    return CellMetrics(paint.measureText("X"), lineSpacing, lineSpacing + ascent)
}

/** Glue between [TerminalView] and the app: soft keyboard on tap, sticky CTRL/ALT/SHIFT, logging. */
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
    override fun readShiftKey(): Boolean = modifiers.shift
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

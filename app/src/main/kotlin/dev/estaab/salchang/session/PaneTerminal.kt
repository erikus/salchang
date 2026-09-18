package dev.estaab.salchang.session

import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

private const val LOG_TAG: String = "PaneTerminal"

/** `ESC [` — start of a CSI sequence. */
private const val CSI: String = "["

/** Line terminator used to pad the captured screen; must match tmuxctl's capture line separator. */
private const val CRLF: String = "\r\n"

/**
 * One tmux pane's terminal emulator plus the state machine that brings it up to date.
 *
 * Bootstrap ordering guarantee: `%output` notifications tmux emits *before* it answers our
 * `capture-pane` are already reflected in the capture and are dropped ([Phase.CAPTURING]).
 * Notifications emitted after the capture reply but before the cursor reply are buffered
 * ([Phase.BUFFERING]) and replayed right after the capture, before the cursor is positioned
 * from the `display-message` reply, so the final cursor position is exact even if those
 * bytes landed at a slightly wrong place. Afterwards ([Phase.LIVE]) output is applied directly.
 * The controller gets both replies as `TmuxEvent.CommandReply` through the same event
 * collector as the `%output` events, so they are seen in wire order by construction.
 *
 * Every method must be called on the main thread; the emulator is not thread-safe.
 */
class PaneTerminal internal constructor(
    val paneId: String,
    val windowId: String,
    val session: TerminalSession,
    val sessionClient: PaneSessionClient,
) {
    enum class Phase { CAPTURING, BUFFERING, LIVE, CLOSED }

    var phase: Phase = Phase.CAPTURING
        private set

    private val pending: ArrayList<ByteArray> = ArrayList()

    /** Current fixed grid size (the tmux pane size). */
    val columns: Int get() = session.fixedColumns
    val rows: Int get() = session.fixedRows

    internal fun onOutput(bytes: ByteArray) {
        when (phase) {
            Phase.CAPTURING, Phase.CLOSED -> Unit
            Phase.BUFFERING -> pending.add(bytes)
            Phase.LIVE -> session.appendOutput(bytes, 0, bytes.size)
        }
    }

    internal fun captureReceived() {
        if (phase == Phase.CAPTURING) phase = Phase.BUFFERING
    }

    /**
     * Applies the capture, [padBlankLines] blank lines so the pane's visible screen ends up on the
     * emulator's screen rows, the output buffered meanwhile, and finally the cursor position
     * (zero-based, pane relative).
     */
    internal fun finishBootstrap(capture: ByteArray, padBlankLines: Int, cursorX: Int, cursorY: Int) {
        if (phase == Phase.CLOSED) return
        session.appendOutput(capture, 0, capture.size)
        if (padBlankLines > 0) {
            val pad: ByteArray = CRLF.repeat(padBlankLines).toByteArray(Charsets.US_ASCII)
            session.appendOutput(pad, 0, pad.size)
        }
        for (bytes in pending) session.appendOutput(bytes, 0, bytes.size)
        pending.clear()
        val cursor: ByteArray = "$CSI${cursorY + 1};${cursorX + 1}H".toByteArray(Charsets.US_ASCII)
        session.appendOutput(cursor, 0, cursor.size)
        phase = Phase.LIVE
    }

    /** Pins the emulator to the pane's size; a no-op when unchanged. */
    internal fun resize(columns: Int, rows: Int) {
        if (phase == Phase.CLOSED) return
        if (columns == session.fixedColumns && rows == session.fixedRows) return
        if (columns < MIN_GRID_DIMENSION || rows < MIN_GRID_DIMENSION) {
            Log.w(LOG_TAG, "ignoring tiny pane size ${columns}x$rows for $paneId")
            return
        }
        session.setFixedSize(columns, rows)
    }

    internal fun close() {
        phase = Phase.CLOSED
        pending.clear()
        session.finishIfRunning()
    }

    companion object {
        /** The emulator throws below 2x2; tmux never reports panes that small anyway. */
        const val MIN_GRID_DIMENSION: Int = 2
    }
}

/**
 * [TerminalSessionClient] for a pane. The attached `TerminalView` registers itself through
 * [screenListener] so screen updates trigger a redraw; nothing else is wired to the UI.
 */
class PaneSessionClient(private val copyToClipboard: (String) -> Unit) : TerminalSessionClient {

    /** Invoked on the main thread whenever the screen content or colours changed. */
    var screenListener: (() -> Unit)? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        screenListener?.invoke()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        screenListener?.invoke()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text != null) copyToClipboard(text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        screenListener?.invoke()
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) {
        Log.e(tag ?: LOG_TAG, message ?: "")
    }

    override fun logWarn(tag: String?, message: String?) {
        Log.w(tag ?: LOG_TAG, message ?: "")
    }

    override fun logInfo(tag: String?, message: String?) {
        Log.i(tag ?: LOG_TAG, message ?: "")
    }

    override fun logDebug(tag: String?, message: String?) {
        Log.d(tag ?: LOG_TAG, message ?: "")
    }

    override fun logVerbose(tag: String?, message: String?) {
        Log.v(tag ?: LOG_TAG, message ?: "")
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: LOG_TAG, message ?: "", e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag ?: LOG_TAG, "", e)
    }
}

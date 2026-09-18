package dev.estaab.salchang.tmuxctl

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Scrollback lines fetched by [TmuxControlClient.capturePane] unless overridden. */
const val DEFAULT_HISTORY_LINES: Int = 2000

/** Pane user option carrying per-pane metadata (see docs/DESIGN.md). */
const val META_OPTION: String = "@salchang_meta"

/** Name given to the `refresh-client -B` subscription for [META_OPTION]. */
const val META_SUBSCRIPTION_NAME: String = "meta"

/** Events buffered between the reader and a slow collector before the reader suspends. */
private const val EVENT_BUFFER_CAPACITY: Int = 4096

/** Read buffer for the transport input stream. */
private const val READ_BUFFER_SIZE: Int = 64 * 1024

/** Window/pane notifications within this many ms are coalesced into one refresh. */
private const val REFRESH_DEBOUNCE_MS: Long = 50L

/** Maximum bytes per `send-keys -H` command (keeps command lines short). */
private const val SEND_KEYS_CHUNK_BYTES: Int = 256

/** `%begin` flag value tmux uses for blocks not caused by a control-client command. */
private const val BLOCK_FLAG_UNSOLICITED: Int = 0

/** Separator used in `-F` formats so fields can be split unambiguously. */
private const val FIELD_SEPARATOR: Char = '\t'

/** Line separator used when joining captured pane lines for a terminal emulator. */
private const val CAPTURE_LINE_SEPARATOR: String = "\r\n"

private const val TRUE_FLAG: String = "1"

/** Error text delivered for a reply block that tmux abandoned by starting another `%begin`. */
internal const val BLOCK_INTERRUPTED_MESSAGE: String = "reply block interrupted by %begin"
private const val NEWLINE: Byte = '\n'.code.toByte()
private const val CARRIAGE_RETURN: Byte = '\r'.code.toByte()

/** Something the tmux server told us that a UI or session controller cares about. */
sealed interface TmuxEvent {
    /** Bytes a pane's program wrote (from `%output` / `%extended-output`), in order. */
    class PaneOutput(val paneId: String, val bytes: ByteArray) : TmuxEvent {
        override fun toString(): String = "PaneOutput(paneId=$paneId, bytes=${bytes.size})"
    }

    /** [META_OPTION] changed on a pane; [value] is empty when the option was cleared. */
    data class MetaChanged(val paneId: String, val windowId: String, val value: String) : TmuxEvent

    /** [TmuxControlClient.state] was rebuilt from a full `list-windows`/`list-panes`. */
    data object WindowsChanged : TmuxEvent

    /**
     * Reply to a command sent with [TmuxControlClient.submitTracked]. Unlike [TmuxControlClient.command]
     * it arrives through [TmuxControlClient.events], so a collector sees it in wire order relative to
     * the [PaneOutput] events around it.
     */
    data class CommandReply(val token: Long, val result: CommandResult) : TmuxEvent

    /** tmux sent `%exit`; the client is closed after this. */
    data class Exit(val reason: String?) : TmuxEvent

    /** The transport failed or ended without `%exit`, or a background refresh failed. */
    data class TransportError(val cause: Throwable) : TmuxEvent
}

/** Reply to one control-mode command: the lines between `%begin` and `%end`/`%error`. */
sealed interface CommandResult {
    val lines: List<String>

    data class Success(override val lines: List<String>) : CommandResult
    data class Error(override val lines: List<String>) : CommandResult
}

/** A command was answered with `%error`. [lines] holds tmux's error text. */
class TmuxCommandException(val command: String, val lines: List<String>) :
    IOException("tmux command failed: $command: ${lines.joinToString(" | ")}")

/**
 * A tmux control-mode (`tmux -C`) client.
 *
 * A reader coroutine (on [Dispatchers.IO], in [scope]) consumes [transport]'s
 * input, correlates `%begin`..`%end`/`%error` blocks with commands sent through
 * [command] in FIFO order (tmux answers in order; the unsolicited block tmux
 * emits right after attaching carries flag `0` and is ignored), and turns
 * notifications into [events] and [state] updates.
 *
 * [events] is a `SharedFlow` with a large buffer and `BufferOverflow.SUSPEND`:
 * [TmuxEvent.PaneOutput] is never dropped or reordered; if a collector falls
 * behind, the reader suspends (back-pressure to tmux) rather than losing bytes.
 * Events emitted while there is no collector are discarded, so subscribe before
 * calling [start]. Replies to [command] resume a different coroutine than the
 * event collector, so their order relative to events is *not* defined; when it
 * matters (e.g. `capture-pane` versus the `%output` around it) use [submitTracked],
 * whose reply is itself an event.
 *
 * Each [command] line must contain exactly one tmux command (no `;`), because
 * replies are matched one block per line written.
 */
class TmuxControlClient(
    private val transport: ControlTransport,
    private val scope: CoroutineScope,
    private val historyLines: Int = DEFAULT_HISTORY_LINES,
) {
    private val _state: MutableStateFlow<TmuxState> = MutableStateFlow(TmuxState.EMPTY)

    /** Current view of the attached session; see [TmuxState]. */
    val state: StateFlow<TmuxState> = _state.asStateFlow()

    private val _events: MutableSharedFlow<TmuxEvent> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** Notifications from tmux; see the class KDoc for delivery guarantees. */
    val events: SharedFlow<TmuxEvent> = _events.asSharedFlow()

    /** Serializes writes so the order of [pending] matches the order on the wire. */
    private val writeMutex: Mutex = Mutex()

    /** Serializes [refresh] so two rebuilds cannot interleave. */
    private val refreshMutex: Mutex = Mutex()

    /** Keeps chunks of one [sendKeys] call contiguous on the wire. */
    private val keysMutex: Mutex = Mutex()

    /** Commands awaiting a reply block, oldest first. Guarded by `synchronized(pending)`. */
    private val pending: ArrayDeque<Pending> = ArrayDeque()

    private val nextToken: AtomicLong = AtomicLong(1L)

    /** Latest known [META_OPTION] per pane id. Guarded by [stateLock] together with [_state] patches. */
    private val metaByPane: HashMap<String, String> = HashMap()
    private val stateLock: Any = Any()

    private val closed: AtomicBoolean = AtomicBoolean(false)
    private val refreshScheduled: AtomicBoolean = AtomicBoolean(false)

    private val readerJob: Job = scope.launch(Dispatchers.IO) { readLoop() }

    /**
     * Sends one command line and awaits its reply block.
     *
     * @throws IOException if the client is closed or the transport fails.
     * @throws IllegalArgumentException if [line] contains a newline.
     */
    suspend fun command(line: String): CommandResult = submit(line).await()

    /** A fresh token for [submitTracked]; allocate it before sending so the reply can be matched. */
    fun newToken(): Long = nextToken.getAndIncrement()

    /**
     * Sends one command line whose reply is delivered as a [TmuxEvent.CommandReply] carrying
     * [token] (from [newToken]) instead of being returned. Use this when the reply must be
     * processed in order with the pane output around it (see [TmuxEvent.CommandReply]).
     * If the client is closed before the reply arrives, no event is delivered.
     *
     * @throws IOException if the client is closed or the transport fails.
     */
    suspend fun submitTracked(line: String, token: Long) {
        submit(line, Pending.Tracked(token))
    }

    /**
     * Tells tmux our client size, subscribes to [META_OPTION] changes on every pane,
     * and performs the first [refresh]. Subscribe to [events] before calling this.
     */
    suspend fun start(clientCols: Int, clientRows: Int) {
        setClientSize(clientCols, clientRows)
        // Single quotes: '#' would otherwise start a comment.
        command("refresh-client -B '$META_SUBSCRIPTION_NAME:%*:#{$META_OPTION}'").orThrow()
        refresh()
    }

    /**
     * Rebuilds [state] from `list-windows`, `list-panes -s` and `display-message`.
     * Existing meta values are kept; values already set on the server (before we
     * subscribed) are picked up for panes we have no value for yet.
     */
    suspend fun refresh() {
        refreshMutex.withLock {
            val sep = FIELD_SEPARATOR
            // Free-text fields (window name, pane title) go last so a stray separator in them is harmless.
            val windowsDeferred = submit("list-windows -F '#{window_id}$sep#{window_index}$sep#{window_active}$sep#{window_layout}$sep#{window_name}'")
            val panesDeferred = submit("list-panes -s -F '#{pane_id}$sep#{window_id}$sep#{pane_width}$sep#{pane_height}$sep#{pane_active}$sep#{pane_current_command}$sep#{pane_title}'")
            val metaDeferred = submit("list-panes -s -F '#{pane_id}$sep#{$META_OPTION}'")
            val sessionDeferred = submit("display-message -p '#{session_id}$sep#{session_name}'")

            val windowLines = windowsDeferred.await().orThrow("list-windows")
            val paneLines = panesDeferred.await().orThrow("list-panes")
            val metaLines = metaDeferred.await().orThrow("list-panes (meta)")
            val sessionLines = sessionDeferred.await().orThrow("display-message")

            val panesByWindow = HashMap<String, MutableList<TmuxPane>>()
            for (line in paneLines) {
                val f = line.split(FIELD_SEPARATOR, limit = 7)
                if (f.size < 7) throw IOException("unexpected list-panes line: $line")
                val pane = TmuxPane(
                    id = f[0],
                    windowId = f[1],
                    width = f[2].toIntOrNull() ?: throw IOException("bad pane width: $line"),
                    height = f[3].toIntOrNull() ?: throw IOException("bad pane height: $line"),
                    active = f[4] == TRUE_FLAG,
                    currentCommand = f[5],
                    title = f[6],
                )
                panesByWindow.getOrPut(pane.windowId) { ArrayList() }.add(pane)
            }

            val fetchedMeta = HashMap<String, String>()
            for (line in metaLines) {
                val f = line.split(FIELD_SEPARATOR, limit = 2)
                if (f.size == 2 && f[1].isNotEmpty()) fetchedMeta[f[0]] = f[1]
            }

            val sessionFields = sessionLines.firstOrNull()?.split(FIELD_SEPARATOR, limit = 2)
            val sessionId = sessionFields?.getOrNull(0)?.takeIf { isSessionId(it) }
            val sessionName = sessionFields?.getOrNull(1)

            val windowRows = windowLines.map { line ->
                val f = line.split(FIELD_SEPARATOR, limit = 5)
                if (f.size < 5) throw IOException("unexpected list-windows line: $line")
                f
            }

            synchronized(stateLock) {
                val livePanes = HashSet<String>()
                panesByWindow.values.forEach { list -> list.forEach { livePanes.add(it.id) } }
                metaByPane.keys.retainAll(livePanes)
                for ((paneId, value) in fetchedMeta) {
                    if (paneId in livePanes && paneId !in metaByPane) metaByPane[paneId] = value
                }
                val windows = windowRows.map { f ->
                    val panes: List<TmuxPane> = panesByWindow[f[0]] ?: emptyList()
                    TmuxWindow(
                        id = f[0],
                        index = f[1].toIntOrNull() ?: throw IOException("bad window index: $f"),
                        active = f[2] == TRUE_FLAG,
                        layout = f[3],
                        name = f[4],
                        activePaneId = panes.firstOrNull { it.active }?.id,
                        panes = panes,
                        meta = panes.mapNotNull { p -> metaByPane[p.id]?.let { p.id to it } }.toMap(),
                    )
                }.sortedBy { it.index }
                _state.value = TmuxState(
                    sessionId = sessionId,
                    sessionName = sessionName,
                    activeWindowId = windows.firstOrNull { it.active }?.id,
                    windows = windows,
                )
            }
        }
        _events.emit(TmuxEvent.WindowsChanged)
    }

    /** Types [bytes] into [paneId] via `send-keys -H`, in chunks of [SEND_KEYS_CHUNK_BYTES]. */
    suspend fun sendKeys(paneId: String, bytes: ByteArray) {
        requirePaneId(paneId)
        if (bytes.isEmpty()) return
        val replies = ArrayList<Deferred<CommandResult>>()
        keysMutex.withLock {
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + SEND_KEYS_CHUNK_BYTES, bytes.size)
                replies.add(submit("send-keys -t $paneId -H ${hexEncode(bytes.copyOfRange(offset, end))}"))
                offset = end
            }
        }
        for (reply in replies) reply.await().orThrow("send-keys")
    }

    /**
     * Returns the pane's visible screen plus [historyLines] of scrollback, with SGR
     * escapes (`-e`), wrapped lines joined (`-J`) and trailing spaces kept (`-N`), as
     * lines joined by CR LF. Trailing fully empty lines are dropped so replaying the
     * bytes into an emulator leaves the cursor on the last line with content; use
     * [paneCursor] afterwards to place the cursor exactly.
     */
    suspend fun capturePane(paneId: String): ByteArray =
        captureToBytes(command(capturePaneCommand(paneId)).orThrow("capture-pane"))

    /** The command line [capturePane] sends, for use with [submitTracked]. */
    fun capturePaneCommand(paneId: String): String {
        requirePaneId(paneId)
        return "capture-pane -p -e -J -N -t $paneId -S -$historyLines"
    }

    /** Cursor position `(x, y)` in [paneId], zero-based from the pane's top-left. */
    suspend fun paneCursor(paneId: String): Pair<Int, Int> {
        requirePaneId(paneId)
        val lines = command("display-message -p -t $paneId '#{cursor_x}$FIELD_SEPARATOR#{cursor_y}'").orThrow("display-message")
        val f = lines.firstOrNull()?.split(FIELD_SEPARATOR) ?: throw IOException("empty cursor reply for $paneId")
        val x = f.getOrNull(0)?.toIntOrNull() ?: throw IOException("bad cursor reply: $lines")
        val y = f.getOrNull(1)?.toIntOrNull() ?: throw IOException("bad cursor reply: $lines")
        return Pair(x, y)
    }

    /** `refresh-client -C colsxrows`: the size tmux assumes for this client. */
    suspend fun setClientSize(cols: Int, rows: Int) {
        require(cols > 0 && rows > 0) { "client size must be positive: ${cols}x$rows" }
        command("refresh-client -C ${cols}x$rows").orThrow("refresh-client")
    }

    suspend fun selectWindow(windowId: String) {
        requireWindowId(windowId)
        command("select-window -t $windowId").orThrow("select-window")
    }

    suspend fun newWindow() {
        command("new-window").orThrow("new-window")
    }

    suspend fun killWindow(windowId: String) {
        requireWindowId(windowId)
        command("kill-window -t $windowId").orThrow("kill-window")
    }

    /** Renames a window; [name] may contain spaces and quotes but not line breaks. */
    suspend fun renameWindow(windowId: String, name: String) {
        requireWindowId(windowId)
        command("rename-window -t $windowId ${singleQuote(name)}").orThrow("rename-window")
    }

    suspend fun selectPane(paneId: String) {
        requirePaneId(paneId)
        command("select-pane -t $paneId").orThrow("select-pane")
    }

    /** Stops the reader, closes the transport and fails every pending command. Idempotent. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        readerJob.cancel()
        try {
            transport.close()
        } catch (_: IOException) {
            // Best effort; the reader is already cancelled.
        }
        failPending(IOException("tmux control client closed"))
    }

    // ---- command plumbing -------------------------------------------------------------------

    /** How the reply to a sent command is delivered. */
    private sealed interface Pending {
        class Awaited(val deferred: CompletableDeferred<CommandResult>) : Pending
        class Tracked(val token: Long) : Pending
    }

    private suspend fun submit(line: String): Deferred<CommandResult> {
        val deferred = CompletableDeferred<CommandResult>()
        submit(line, Pending.Awaited(deferred))
        return deferred
    }

    private suspend fun submit(line: String, entry: Pending) {
        require(line.indexOf('\n') < 0 && line.indexOf('\r') < 0) { "command must be a single line: $line" }
        if (closed.get()) throw IOException("tmux control client closed")
        val bytes = (line + '\n').toByteArray(Charsets.UTF_8)
        writeMutex.withLock {
            synchronized(pending) { pending.addLast(entry) }
            try {
                withContext(Dispatchers.IO) {
                    transport.output.write(bytes)
                    transport.output.flush()
                }
            } catch (e: IOException) {
                synchronized(pending) { pending.remove(entry) }
                throw e
            }
        }
    }

    private fun takePending(): Pending? = synchronized(pending) { pending.removeFirstOrNull() }

    private fun failPending(cause: Throwable) {
        val stale = synchronized(pending) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        for (entry in stale) if (entry is Pending.Awaited) entry.deferred.completeExceptionally(cause)
    }

    /** Delivers [result] for [entry]: completes the deferred or emits the tracked reply event. */
    private suspend fun deliver(entry: Pending?, result: CommandResult) {
        when (entry) {
            null -> Unit
            is Pending.Awaited -> entry.deferred.complete(result)
            is Pending.Tracked -> _events.emit(TmuxEvent.CommandReply(entry.token, result))
        }
    }

    // ---- reader ----------------------------------------------------------------------------

    /** Reply block currently being collected; [pending] is null for an unsolicited block. */
    private class Block(val pending: Pending?) {
        val lines: MutableList<String> = ArrayList()
    }

    private suspend fun readLoop() {
        val reader = LineReader(transport.input)
        var block: Block? = null
        try {
            while (true) {
                val raw = reader.readLine() ?: throw EOFException("tmux control stream ended without %exit")
                when (val line = parseControlLine(raw, insideBlock = block != null)) {
                    is ControlLine.Begin -> {
                        block?.let { deliver(it.pending, CommandResult.Error(listOf(BLOCK_INTERRUPTED_MESSAGE))) }
                        block = Block(if (line.flags == BLOCK_FLAG_UNSOLICITED) null else takePending())
                    }
                    is ControlLine.Body -> block?.lines?.add(line.line)
                    is ControlLine.End -> {
                        block?.let { deliver(it.pending, CommandResult.Success(it.lines)) }
                        block = null
                    }
                    is ControlLine.Error -> {
                        block?.let { deliver(it.pending, CommandResult.Error(it.lines)) }
                        block = null
                    }
                    is ControlLine.Exit -> {
                        _events.emit(TmuxEvent.Exit(line.reason))
                        close()
                        return
                    }
                    else -> handleNotification(line)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (!closed.get()) {
                _events.emit(TmuxEvent.TransportError(e))
                close()
            }
        } catch (e: RuntimeException) {
            _events.emit(TmuxEvent.TransportError(e))
            close()
        }
    }

    private suspend fun handleNotification(line: ControlLine) {
        when (line) {
            is ControlLine.Output -> _events.emit(TmuxEvent.PaneOutput(line.paneId, line.data))
            is ControlLine.ExtendedOutput -> _events.emit(TmuxEvent.PaneOutput(line.paneId, line.data))
            is ControlLine.SubscriptionChanged -> {
                val paneId = line.paneId
                val windowId = line.windowId
                if (line.name == META_SUBSCRIPTION_NAME && paneId != null && windowId != null) {
                    updateMeta(paneId, windowId, line.value)
                    _events.emit(TmuxEvent.MetaChanged(paneId, windowId, line.value))
                }
            }
            is ControlLine.LayoutChange -> {
                applyLayout(line.windowId, line.layout)
                scheduleRefresh()
            }
            is ControlLine.WindowAdd -> scheduleRefresh()
            is ControlLine.WindowClose -> {
                patchState { st -> st.copy(windows = st.windows.filterNot { it.id == line.windowId }) }
                scheduleRefresh()
            }
            is ControlLine.WindowRenamed -> {
                patchWindow(line.windowId) { it.copy(name = line.name) }
                scheduleRefresh()
            }
            is ControlLine.WindowPaneChanged -> {
                patchWindow(line.windowId) { w ->
                    w.copy(activePaneId = line.paneId, panes = w.panes.map { it.copy(active = it.id == line.paneId) })
                }
                scheduleRefresh()
            }
            is ControlLine.SessionWindowChanged -> {
                patchState { st ->
                    st.copy(
                        activeWindowId = line.windowId,
                        windows = st.windows.map { it.copy(active = it.id == line.windowId) },
                    )
                }
                scheduleRefresh()
            }
            is ControlLine.SessionChanged -> {
                patchState { it.copy(sessionId = line.sessionId, sessionName = line.name) }
                scheduleRefresh()
            }
            is ControlLine.SessionRenamed -> patchState { it.copy(sessionName = line.name) }
            is ControlLine.UnlinkedWindowAdd,
            is ControlLine.UnlinkedWindowClose,
            is ControlLine.UnlinkedWindowRenamed,
            -> scheduleRefresh()
            else -> Unit // Sessions-changed, pause/continue, messages, unknown: nothing to track.
        }
    }

    private fun updateMeta(paneId: String, windowId: String, value: String) {
        synchronized(stateLock) {
            if (value.isEmpty()) metaByPane.remove(paneId) else metaByPane[paneId] = value
            _state.update { st ->
                st.copy(windows = st.windows.map { w ->
                    when {
                        w.id != windowId -> w
                        value.isEmpty() -> w.copy(meta = w.meta - paneId)
                        else -> w.copy(meta = w.meta + (paneId to value))
                    }
                })
            }
        }
    }

    private fun applyLayout(windowId: String, layout: String) {
        val sizes = try {
            paneSizes(layout)
        } catch (_: LayoutParseException) {
            return
        }
        patchWindow(windowId) { w ->
            w.copy(
                layout = layout,
                panes = w.panes.map { p ->
                    val size = sizes[p.id]
                    if (size == null) p else p.copy(width = size.first, height = size.second)
                },
            )
        }
    }

    private fun patchState(transform: (TmuxState) -> TmuxState) {
        synchronized(stateLock) { _state.update(transform) }
    }

    private fun patchWindow(windowId: String, transform: (TmuxWindow) -> TmuxWindow) {
        patchState { st -> st.copy(windows = st.windows.map { if (it.id == windowId) transform(it) else it }) }
    }

    /**
     * Runs [refresh] after [REFRESH_DEBOUNCE_MS]; further calls while one is scheduled
     * are folded into it. Runs in [scope], never on the reader (which must keep reading
     * to deliver the replies the refresh waits for).
     */
    private fun scheduleRefresh() {
        if (closed.get() || !refreshScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(REFRESH_DEBOUNCE_MS)
            refreshScheduled.set(false)
            try {
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!closed.get()) _events.emit(TmuxEvent.TransportError(e))
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun CommandResult.orThrow(command: String = "command"): List<String> = when (this) {
        is CommandResult.Success -> lines
        is CommandResult.Error -> throw TmuxCommandException(command, lines)
    }

    private fun requirePaneId(id: String) = require(isPaneId(id)) { "not a pane id: $id" }
    private fun requireWindowId(id: String) = require(isWindowId(id)) { "not a window id: $id" }

    /** Wraps [s] in single quotes for tmux's parser; embedded quotes become `'\''`. */
    private fun singleQuote(s: String): String {
        require(s.indexOf('\n') < 0 && s.indexOf('\r') < 0) { "value must not contain line breaks: $s" }
        return "'" + s.replace("'", "'\\''") + "'"
    }

    companion object {
        /**
         * Turns the reply lines of [capturePaneCommand] into bytes for a terminal emulator: lines
         * joined by CR LF with trailing fully empty lines dropped (see [capturePane]).
         */
        fun captureToBytes(lines: List<String>): ByteArray =
            lines.dropLastWhile { it.isEmpty() }.joinToString(CAPTURE_LINE_SEPARATOR).toByteArray(Charsets.UTF_8)
    }
}

/** Splits an [InputStream] into `\n`-terminated lines (a trailing `\r` is stripped), as raw bytes. */
private class LineReader(private val input: InputStream) {
    private val buffer: ByteArray = ByteArray(READ_BUFFER_SIZE)
    private var start: Int = 0
    private var end: Int = 0
    private val partial: ByteArrayOutputStream = ByteArrayOutputStream()

    /** Next line without its terminator, or null at end of stream. */
    fun readLine(): ByteArray? {
        while (true) {
            var i = start
            while (i < end) {
                if (buffer[i] == NEWLINE) {
                    partial.write(buffer, start, i - start)
                    start = i + 1
                    return finishLine()
                }
                i++
            }
            partial.write(buffer, start, end - start)
            start = 0
            end = 0
            val n = input.read(buffer)
            if (n < 0) return if (partial.size() > 0) finishLine() else null
            end = n
        }
    }

    private fun finishLine(): ByteArray {
        var line = partial.toByteArray()
        partial.reset()
        if (line.isNotEmpty() && line[line.size - 1] == CARRIAGE_RETURN) line = line.copyOf(line.size - 1)
        return line
    }
}

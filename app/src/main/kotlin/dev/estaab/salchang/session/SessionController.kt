package dev.estaab.salchang.session

import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSink
import dev.estaab.salchang.data.AuthMethod
import dev.estaab.salchang.data.HostProfile
import dev.estaab.salchang.data.KeyInfo
import dev.estaab.salchang.data.KeyStore
import dev.estaab.salchang.meta.WindowMeta
import dev.estaab.salchang.ssh.HostKeyPrompt
import dev.estaab.salchang.ssh.KnownHosts
import dev.estaab.salchang.ssh.SshControlTransport
import dev.estaab.salchang.tmuxctl.CommandResult
import dev.estaab.salchang.tmuxctl.DEFAULT_HISTORY_LINES
import dev.estaab.salchang.tmuxctl.TmuxCommandException
import dev.estaab.salchang.tmuxctl.TmuxControlClient
import dev.estaab.salchang.tmuxctl.TmuxEvent
import dev.estaab.salchang.tmuxctl.TmuxKeyBinding
import dev.estaab.salchang.tmuxctl.TmuxKeyRouter
import dev.estaab.salchang.tmuxctl.TmuxPane
import dev.estaab.salchang.tmuxctl.TmuxState
import dev.estaab.salchang.tmuxctl.parseListKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream

private const val LOG_TAG: String = "SessionController"

/** Field separator for the `display-message -F` we send during bootstrap. */
private const val FIELD_SEPARATOR: Char = '\t'

/** Viewport changes within this window are folded into one `refresh-client -C`. */
private const val RESIZE_DEBOUNCE_MS: Long = 150L

/** Upper bound on the "kill own grouped session" step of a disconnect. */
private const val DISCONNECT_TIMEOUT_MS: Long = 3_000L

/** Parsed meta values kept per distinct raw option string. */
private const val META_CACHE_SIZE: Int = 64

/** Bytes of tmux stderr read (non-blocking) when the control stream dies, to explain why. */
private const val STDERR_SNIPPET_BYTES: Int = 2_048

/** Commands that load the key-binding emulation (see [SessionController.keyRouter]). */
private const val SHOW_PREFIX_COMMAND: String = "show-options -gv prefix"
private const val LIST_PREFIX_KEYS_COMMAND: String = "list-keys -T prefix"
private const val LIST_ROOT_KEYS_COMMAND: String = "list-keys -T root"

/**
 * One connected host: SSH transport, tmux control client and one [PaneTerminal] per pane the
 * UI has asked for. Owned by [SessionViewModel].
 *
 * Threading: [scope] must be `Dispatchers.Main.immediate`. Everything that touches an emulator
 * runs in it; the tmux reader runs on IO inside the same scope and hands events back via
 * `TmuxControlClient.events`. Public methods are main-thread only unless noted.
 */
class SessionController(
    val profile: HostProfile,
    private val keyStore: KeyStore,
    knownHostsFactory: (HostKeyPrompt) -> KnownHosts,
    private val copyToClipboard: (String) -> Unit,
    private val scope: CoroutineScope,
) {
    private val knownHosts: KnownHosts = knownHostsFactory(HostKeyPrompt { hostname, keyType, fingerprint ->
        askHostKey(hostname, keyType, fingerprint)
    })

    private val _connectionState: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected(null))
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _tmuxState: MutableStateFlow<TmuxState> = MutableStateFlow(TmuxState.EMPTY)
    /** Last tmux state seen; keeps its value after a disconnect so the tabs do not vanish. */
    val tmuxState: StateFlow<TmuxState> = _tmuxState.asStateFlow()

    private val _terminals: MutableStateFlow<Map<String, PaneTerminal>> = MutableStateFlow(emptyMap())
    /** Terminals created so far, keyed by pane id. Use [ensureTerminal] to create one. */
    val terminals: StateFlow<Map<String, PaneTerminal>> = _terminals.asStateFlow()

    private val _prompt: MutableStateFlow<SessionPrompt?> = MutableStateFlow(null)
    val prompt: StateFlow<SessionPrompt?> = _prompt.asStateFlow()

    private val _lastError: MutableStateFlow<String?> = MutableStateFlow(null)
    /** Non-fatal failures of window/pane commands, for a snackbar. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _keyRouter: MutableStateFlow<TmuxKeyRouter?> = MutableStateFlow(null)
    /**
     * Client-side emulation of the tmux prefix and `root` key tables, loaded from the server
     * after each connect. Null until loaded or if loading failed; typed bytes then pass
     * through to `send-keys` unchanged (so the prefix prints literally).
     */
    val keyRouter: StateFlow<TmuxKeyRouter?> = _keyRouter.asStateFlow()

    private val _prefixArmed: MutableStateFlow<Boolean> = MutableStateFlow(false)
    /** True between the prefix key and the key it applies to, for a UI indicator. */
    val prefixArmed: StateFlow<Boolean> = _prefixArmed.asStateFlow()

    private var client: TmuxControlClient? = null
    private var transport: SshControlTransport? = null
    private var connectJob: Job? = null
    private var resizeJob: Job? = null
    private val clientJobs: MutableList<Job> = ArrayList()

    private var clientCols: Int = TerminalSizing.DEFAULT_CLIENT_COLS
    private var clientRows: Int = TerminalSizing.DEFAULT_CLIENT_ROWS

    /**
     * Pane bootstraps awaiting tracked replies, keyed by both of their tokens (see [bootstrap]).
     * Main thread only.
     */
    private val bootstraps: HashMap<Long, Bootstrap> = HashMap()

    /** Access-ordered LRU: raw `@salchang_meta` string -> parsed value. Main thread only. */
    private val metaCache: LinkedHashMap<String, WindowMeta> =
        object : LinkedHashMap<String, WindowMeta>(META_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WindowMeta>?): Boolean = size > META_CACHE_SIZE
        }

    // ---- connection lifecycle --------------------------------------------------------------

    /** Starts connecting unless a connect is already running. Re-runnable after a failure. */
    fun connect() {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch { runConnect() }
    }

    /** Kills our grouped session (only if the group has other members), then closes everything. */
    fun disconnect() {
        scope.launch { runDisconnect("disconnected") }
    }

    /** Disconnects and cancels [scope]; the controller is unusable afterwards. */
    fun destroy() {
        val job: Job = scope.launch { runDisconnect("closed") }
        job.invokeOnCompletion { scope.cancel() }
    }

    private suspend fun runConnect() {
        teardown()
        _connectionState.value = ConnectionState.Connecting()
        var passphrase: CharArray? = null
        if (profile.authMethod == AuthMethod.KEY) {
            val keyId: String? = profile.keyId
            if (keyId == null) {
                _connectionState.value = ConnectionState.Failed(IllegalStateException("No SSH key selected for this host"))
                return
            }
            val key: KeyInfo? = withContext(Dispatchers.IO) { keyStore.get(keyId) }
            if (key == null) {
                _connectionState.value = ConnectionState.Failed(IllegalStateException("The selected SSH key no longer exists"))
                return
            }
            if (key.encrypted) {
                _connectionState.value = ConnectionState.NeedsPassphrase
                passphrase = askPassphrase(key.name)
                if (passphrase == null) {
                    _connectionState.value = ConnectionState.Disconnected("Passphrase required")
                    return
                }
                _connectionState.value = ConnectionState.Connecting()
            }
        }
        val transport: SshControlTransport = try {
            SshControlTransport.connect(profile, keyStore, passphrase, knownHosts, onAuthBanner = ::onAuthBanner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Failed(e)
            return
        } finally {
            passphrase?.fill('\u0000')
        }
        this.transport = transport
        val client = TmuxControlClient(transport, scope)
        this.client = client
        // UNDISPATCHED so both collectors are subscribed before start() sends anything.
        clientJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { handleEvent(client, it) }
        }
        clientJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.state.collect { onTmuxState(it) }
        }
        val startCols: Int = clientCols
        val startRows: Int = clientRows
        try {
            client.start(startCols, startRows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (this.client === client) {
                val stderr: String = readStderr(transport)
                teardown()
                _connectionState.value = ConnectionState.Failed(
                    if (stderr.isEmpty()) e else IOException("${e.message}: $stderr", e),
                )
            }
            return
        }
        if (this.client !== client) return
        _connectionState.value = ConnectionState.Connected
        // The view may have reported its grid while we were connecting; tmux still has the size start() sent.
        if (clientCols != startCols || clientRows != startRows) scheduleClientSize(client)
        loadKeyBindings(client)
    }

    /**
     * Fills [keyRouter] from the server's prefix option and `prefix`/`root` key tables. On
     * failure keys keep passing through unchanged and the reason lands in [lastError].
     */
    private suspend fun loadKeyBindings(client: TmuxControlClient) {
        try {
            val prefix: String = client.command(SHOW_PREFIX_COMMAND).linesOrThrow(SHOW_PREFIX_COMMAND).firstOrNull()?.trim()
                ?: throw IOException("empty reply to $SHOW_PREFIX_COMMAND")
            val lines: List<String> = client.command(LIST_PREFIX_KEYS_COMMAND).linesOrThrow(LIST_PREFIX_KEYS_COMMAND) +
                client.command(LIST_ROOT_KEYS_COMMAND).linesOrThrow(LIST_ROOT_KEYS_COMMAND)
            if (this.client !== client) return
            _keyRouter.value = TmuxKeyRouter(prefix, parseListKeys(lines))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "loading tmux key bindings failed; keys pass through unchanged", e)
            _lastError.value = "Key bindings unavailable: ${e.message}"
        }
    }

    private suspend fun runDisconnect(reason: String) {
        connectJob?.cancel()
        val client: TmuxControlClient? = this.client
        if (client != null) {
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                try {
                    killOwnSessionIfGrouped(client)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "kill-session on disconnect failed", e)
                }
            }
        }
        teardown()
        _connectionState.value = ConnectionState.Disconnected(reason)
    }

    /**
     * `new-session -t <name>` creates the group (and `<name>-0`) when nothing exists yet, so our
     * grouped session may be the only member. Only kill it when the group has another member;
     * otherwise just detach so the user's windows survive.
     */
    private suspend fun killOwnSessionIfGrouped(client: TmuxControlClient) {
        val target: String = _tmuxState.value.sessionId ?: return
        val result: CommandResult = client.command("display-message -p '#{session_group_size}'")
        val size: Int = (result as? CommandResult.Success)?.lines?.firstOrNull()?.trim()?.toIntOrNull() ?: return
        if (size > 1) client.command("kill-session -t $target")
    }

    /** Closes client/transport, drops terminals. Safe to call repeatedly; does not touch [connectionState]. */
    private fun teardown() {
        resizeJob?.cancel()
        resizeJob = null
        clientJobs.forEach { it.cancel() }
        clientJobs.clear()
        bootstraps.clear()
        _keyRouter.value = null
        _prefixArmed.value = false
        client?.close()
        client = null
        transport?.close()
        transport = null
        val old: Map<String, PaneTerminal> = _terminals.value
        _terminals.value = emptyMap()
        old.values.forEach { it.close() }
        _prompt.value?.let { cancelPrompt(it) }
    }

    private fun cancelPrompt(prompt: SessionPrompt) {
        when (prompt) {
            is SessionPrompt.HostKey -> prompt.reply.complete(false)
            is SessionPrompt.Passphrase -> prompt.reply.complete(null)
        }
        _prompt.compareAndSet(prompt, null)
    }

    /** Called on sshj's reader thread (via `runBlocking`); only touches thread-safe state. */
    private suspend fun askHostKey(hostname: String, keyType: String, fingerprint: String): Boolean {
        val prompt = SessionPrompt.HostKey(hostname, keyType, fingerprint, CompletableDeferred())
        _prompt.value = prompt
        return try {
            prompt.reply.await()
        } finally {
            _prompt.compareAndSet(prompt, null)
        }
    }

    /** Called on an IO thread while sshj is still inside auth; only touches the thread-safe state flow. */
    private fun onAuthBanner(banner: String) {
        _connectionState.update { state ->
            if (state is ConnectionState.Connecting) ConnectionState.Connecting(banner.trim()) else state
        }
    }

    private suspend fun askPassphrase(keyName: String): CharArray? {
        val prompt = SessionPrompt.Passphrase(keyName, CompletableDeferred())
        _prompt.value = prompt
        return try {
            prompt.reply.await()
        } finally {
            _prompt.compareAndSet(prompt, null)
        }
    }

    // ---- tmux events -----------------------------------------------------------------------

    private suspend fun handleEvent(client: TmuxControlClient, event: TmuxEvent) {
        when (event) {
            is TmuxEvent.PaneOutput -> _terminals.value[event.paneId]?.onOutput(event.bytes)
            is TmuxEvent.CommandReply -> onCommandReply(event)
            is TmuxEvent.Exit -> onClientDead(client, event.reason ?: "tmux exited")
            is TmuxEvent.TransportError -> {
                if (event.cause is TmuxCommandException) {
                    _lastError.value = event.cause.message
                } else {
                    onClientDead(client, describe(event.cause))
                }
            }
            is TmuxEvent.WindowsChanged, is TmuxEvent.MetaChanged -> Unit
        }
    }

    private suspend fun onClientDead(client: TmuxControlClient, reason: String) {
        if (this.client !== client) return
        val stderr: String = transport?.let { readStderr(it) } ?: ""
        teardown()
        _connectionState.value = ConnectionState.Disconnected(if (stderr.isEmpty()) reason else "$reason: $stderr")
    }

    private fun onTmuxState(state: TmuxState) {
        _tmuxState.value = state
        val current: Map<String, PaneTerminal> = _terminals.value
        if (current.isEmpty()) return
        var next: MutableMap<String, PaneTerminal>? = null
        for ((paneId, terminal) in current) {
            val pane: TmuxPane? = state.pane(paneId)
            if (pane == null) {
                terminal.close()
                (next ?: HashMap(current).also { next = it }).remove(paneId)
            } else {
                terminal.resize(pane.width, pane.height)
            }
        }
        next?.let { _terminals.value = it }
    }

    // ---- terminals -------------------------------------------------------------------------

    /**
     * Returns the terminal for [paneId], creating and bootstrapping it on first use. Null if
     * not connected or the pane is unknown. Main thread only.
     */
    fun ensureTerminal(paneId: String): PaneTerminal? {
        _terminals.value[paneId]?.let { return it }
        val client: TmuxControlClient = this.client ?: return null
        val pane: TmuxPane = _tmuxState.value.pane(paneId) ?: return null
        val sessionClient = PaneSessionClient(copyToClipboard)
        // The sink runs on the main thread: TerminalView writes typed keys from its key handlers,
        // and the emulator's own replies are written while it processes output in handleEvent.
        // That is what lets the (single, shared) router keep its prefix state without locking.
        val sink = TerminalSink { data, offset, count ->
            val copy: ByteArray = data.copyOfRange(offset, offset + count)
            val router: TmuxKeyRouter? = _keyRouter.value
            if (router == null) {
                sendBytes(client, paneId, copy)
                return@TerminalSink
            }
            val actions: List<TmuxKeyRouter.Action> = router.route(copy)
            _prefixArmed.value = router.armed
            for (action in actions) {
                when (action) {
                    is TmuxKeyRouter.Action.Send -> sendBytes(client, paneId, action.bytes)
                    is TmuxKeyRouter.Action.Run -> runBinding(client, action.binding)
                }
            }
        }
        val session = TerminalSession(sessionClient, sink)
        val terminal = PaneTerminal(paneId, pane.windowId, session, sessionClient)
        session.setFixedSize(
            maxOf(pane.width, PaneTerminal.MIN_GRID_DIMENSION),
            maxOf(pane.height, PaneTerminal.MIN_GRID_DIMENSION),
        )
        _terminals.value = _terminals.value + (paneId to terminal)
        scope.launch { bootstrap(client, terminal) }
        return terminal
    }

    /**
     * Types [bytes] into [paneId]. Launch order == the client's key-mutex acquisition order, so
     * typed bytes and bound commands ([runBinding]) reach tmux in the sequence they were typed.
     */
    private fun sendBytes(client: TmuxControlClient, paneId: String, bytes: ByteArray) {
        scope.launch {
            try {
                client.sendKeys(paneId, bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "send-keys to $paneId failed", e)
            }
        }
    }

    /** Runs the command of a matched key binding; a tmux error is reported as "<key>: <error>". */
    private fun runBinding(client: TmuxControlClient, binding: TmuxKeyBinding) {
        scope.launch {
            try {
                val result: CommandResult = client.commandInKeyOrder(binding.command)
                if (result is CommandResult.Error) _lastError.value = "${binding.key}: ${result.lines.joinToString(" | ")}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _lastError.value = "${binding.key}: ${e.message}"
            }
        }
    }

    /** One pane's in-flight bootstrap: the capture reply is expected first, then the cursor/history reply. */
    private class Bootstrap(val terminal: PaneTerminal, val captureToken: Long, val infoToken: Long) {
        var capture: ByteArray? = null
    }

    /**
     * Sends `capture-pane` and the history/cursor query back to back (same write order on the
     * wire) as tracked commands, so both replies come back through [handleEvent] in wire order
     * with the pane's `%output`; see [PaneTerminal] for why that order matters. Tokens are
     * registered before anything is written so a reply can never arrive unmatched.
     */
    private suspend fun bootstrap(client: TmuxControlClient, terminal: PaneTerminal) {
        val paneId: String = terminal.paneId
        val bootstrap = Bootstrap(terminal, captureToken = client.newToken(), infoToken = client.newToken())
        bootstraps[bootstrap.captureToken] = bootstrap
        bootstraps[bootstrap.infoToken] = bootstrap
        try {
            client.submitTracked(client.capturePaneCommand(paneId), bootstrap.captureToken)
            client.submitTracked(
                "display-message -p -t $paneId '#{history_size}$FIELD_SEPARATOR#{cursor_x}$FIELD_SEPARATOR#{cursor_y}'",
                bootstrap.infoToken,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failBootstrap(bootstrap, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Applies a tracked reply to the bootstrap that is waiting for it; unknown tokens are ignored. */
    private fun onCommandReply(reply: TmuxEvent.CommandReply) {
        val bootstrap: Bootstrap = bootstraps.remove(reply.token) ?: return
        val result: CommandResult = reply.result
        if (result !is CommandResult.Success) {
            failBootstrap(bootstrap, result.lines.joinToString(" | "))
            return
        }
        val terminal: PaneTerminal = bootstrap.terminal
        when (reply.token) {
            bootstrap.captureToken -> {
                bootstrap.capture = TmuxControlClient.captureToBytes(result.lines)
                terminal.captureReceived()
            }
            bootstrap.infoToken -> {
                val bytes: ByteArray = bootstrap.capture ?: run {
                    failBootstrap(bootstrap, "cursor reply arrived before the capture reply")
                    return
                }
                val fields: List<String> = result.lines.firstOrNull()?.split(FIELD_SEPARATOR) ?: emptyList()
                val historySize: Int = fields.getOrNull(0)?.toIntOrNull() ?: 0
                val cursorX: Int = fields.getOrNull(1)?.toIntOrNull() ?: 0
                val cursorY: Int = fields.getOrNull(2)?.toIntOrNull() ?: 0
                val pad: Int = blankLinesToPad(bytes, historySize, terminal.rows)
                terminal.finishBootstrap(bytes, pad, cursorX, cursorY)
            }
        }
    }

    private fun failBootstrap(bootstrap: Bootstrap, reason: String) {
        Log.w(LOG_TAG, "bootstrap of ${bootstrap.terminal.paneId} failed: $reason")
        bootstraps.remove(bootstrap.captureToken)
        bootstraps.remove(bootstrap.infoToken)
        removeTerminal(bootstrap.terminal)
    }

    private fun removeTerminal(terminal: PaneTerminal) {
        terminal.close()
        val current: Map<String, PaneTerminal> = _terminals.value
        if (current[terminal.paneId] === terminal) _terminals.value = current - terminal.paneId
    }

    // ---- sizing ----------------------------------------------------------------------------

    /**
     * The grid the phone could show at the preferred font size. Sent to tmux as the client size
     * (debounced) so `window-size latest` follows the phone; also used for the next connect.
     */
    fun onViewportGridChanged(cols: Int, rows: Int) {
        if (cols == clientCols && rows == clientRows) return
        clientCols = cols
        clientRows = rows
        val client: TmuxControlClient = this.client ?: return
        if (_connectionState.value != ConnectionState.Connected) return
        scheduleClientSize(client)
    }

    /** Sends the current [clientCols] x [clientRows] to tmux after [RESIZE_DEBOUNCE_MS], replacing a pending send. */
    private fun scheduleClientSize(client: TmuxControlClient) {
        resizeJob?.cancel()
        resizeJob = scope.launch {
            delay(RESIZE_DEBOUNCE_MS)
            val cols: Int = clientCols
            val rows: Int = clientRows
            try {
                client.setClientSize(cols, rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "refresh-client -C failed", e)
            }
        }
    }

    // ---- window / pane commands ------------------------------------------------------------

    fun selectWindow(windowId: String): Unit = runCommand("select-window") { it.selectWindow(windowId) }
    fun newWindow(): Unit = runCommand("new-window") { it.newWindow() }
    fun killWindow(windowId: String): Unit = runCommand("kill-window") { it.killWindow(windowId) }
    fun renameWindow(windowId: String, name: String): Unit = runCommand("rename-window") { it.renameWindow(windowId, name) }
    fun selectPane(paneId: String): Unit = runCommand("select-pane") { it.selectPane(paneId) }

    fun clearError() {
        _lastError.value = null
    }

    private fun runCommand(what: String, block: suspend (TmuxControlClient) -> Unit) {
        val client: TmuxControlClient = this.client ?: run {
            _lastError.value = "Not connected"
            return
        }
        scope.launch {
            try {
                block(client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _lastError.value = "$what failed: ${e.message}"
            }
        }
    }

    // ---- meta ------------------------------------------------------------------------------

    /** Parses a raw `@salchang_meta` value, memoised by the raw string. Main thread only. */
    fun parseMeta(raw: String): WindowMeta = metaCache.getOrPut(raw) { WindowMeta.parse(raw) }

    companion object {
        /**
         * How many blank lines to append after a trimmed capture so that the pane's visible
         * screen occupies the emulator's screen rows: the capture (`-S -[DEFAULT_HISTORY_LINES]`)
         * holds `min(historySize, DEFAULT_HISTORY_LINES) + paneRows` lines before trailing blank
         * lines were dropped. Wrapped lines are joined (`-J`) and re-wrap in the emulator at
         * the same width, so line counts match.
         */
        fun blankLinesToPad(capture: ByteArray, historySize: Int, paneRows: Int): Int {
            val expected: Int = minOf(maxOf(historySize, 0), DEFAULT_HISTORY_LINES) + paneRows
            val present: Int = if (capture.isEmpty()) 0 else countLines(capture)
            return maxOf(0, expected - present)
        }

        private fun countLines(bytes: ByteArray): Int {
            var lines = 1
            for (b in bytes) if (b == '\n'.code.toByte()) lines++
            return lines
        }

        private fun describe(cause: Throwable): String = cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName

        private fun CommandResult.linesOrThrow(command: String): List<String> = when (this) {
            is CommandResult.Success -> lines
            is CommandResult.Error -> throw TmuxCommandException(command, lines)
        }

        /** Whatever tmux wrote to stderr so far, without blocking. */
        private suspend fun readStderr(transport: SshControlTransport): String = withContext(Dispatchers.IO) {
            try {
                val stream: InputStream = transport.errorStream
                val available: Int = stream.available()
                if (available <= 0) {
                    ""
                } else {
                    val buffer = ByteArray(minOf(available, STDERR_SNIPPET_BYTES))
                    val n: Int = stream.read(buffer)
                    if (n <= 0) "" else String(buffer, 0, n, Charsets.UTF_8).trim()
                }
            } catch (e: IOException) {
                ""
            }
        }
    }
}

package dev.estaab.salchang.tmuxctl

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

private const val TEST_TIMEOUT_MS: Long = 10_000L
private const val META_EVENT_TIMEOUT_MS: Long = 3_000L
private const val EXTERNAL_TMUX_TIMEOUT_SECONDS: Long = 10L
private const val SESSION_NAME: String = "demo"
private const val CLIENT_COLS: Int = 80
private const val CLIENT_ROWS: Int = 24
private const val SOCKET_PREFIX: String = "salchang-test-"
private const val SOCKET_RANDOM_CHARS: Int = 8

/** Unix socket paths are limited to ~108 bytes; stay well below (tmux adds `tmux-<uid>/<name>`). */
private const val MAX_SOCKET_PATH_LENGTH: Int = 100
private const val TMUX_TMPDIR_UNDER_BUILD: String = "build/tmp/tt"

/** The developer's ~/.tmux.conf must never be loaded (it sets destroy-unattached). */
private val TMUX_BASE_ARGS: List<String> = listOf("tmux", "-f", "/dev/null")

/**
 * Drives a real local tmux server through [TmuxControlClient]. Skipped when tmux
 * is not on PATH. Each test gets its own socket and `TMUX_TMPDIR` under the
 * module build dir.
 */
class LocalTmuxTest {
    private lateinit var socket: String
    private lateinit var tmpDir: File
    private lateinit var env: Map<String, String>
    private lateinit var transport: ProcessControlTransport
    private lateinit var scope: CoroutineScope
    private lateinit var client: TmuxControlClient
    private val events = Channel<TmuxEvent>(Channel.UNLIMITED)

    private fun tmuxOnPath(): Boolean =
        System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).any { File(it, "tmux").canExecute() }

    /** Runs a tmux CLI command against the test server and returns (exit code, stdout). */
    private fun tmux(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(TMUX_BASE_ARGS + listOf("-L", socket) + args).apply {
            environment().putAll(env)
            environment().remove("TMUX")
            redirectErrorStream(true)
        }.start()
        val out = ByteArrayOutputStream()
        process.inputStream.copyTo(out)
        if (!process.waitFor(EXTERNAL_TMUX_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("tmux ${args.toList()} did not exit")
        }
        return Pair(process.exitValue(), out.toString(Charsets.UTF_8.name()))
    }

    @Before
    fun setUp() {
        assumeTrue("tmux not on PATH", tmuxOnPath())
        socket = SOCKET_PREFIX + UUID.randomUUID().toString().replace("-", "").take(SOCKET_RANDOM_CHARS)
        tmpDir = File(TMUX_TMPDIR_UNDER_BUILD).absoluteFile
        if (tmpDir.path.length + "/tmux-99999/".length + socket.length > MAX_SOCKET_PATH_LENGTH) {
            tmpDir = File(System.getProperty("java.io.tmpdir"), "salchang-tt")
        }
        tmpDir.mkdirs()
        env = mapOf("TMUX_TMPDIR" to tmpDir.path, "TERM" to "xterm-256color")

        val (code, out) = tmux("new-session", "-d", "-s", SESSION_NAME, "-x", CLIENT_COLS.toString(), "-y", CLIENT_ROWS.toString(), "bash", "--norc")
        assertEquals("new-session failed: $out", 0, code)

        transport = ProcessControlTransport(
            command = TMUX_BASE_ARGS + listOf("-L", socket, "-C", "new-session", "-t", SESSION_NAME),
            extraEnv = env,
            removedEnv = listOf("TMUX"),
            stderrFile = File(tmpDir, "$socket.stderr"),
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        client = TmuxControlClient(transport, scope)
        runBlocking {
            val subscribed = CompletableDeferred<Unit>()
            scope.launch { client.events.onSubscription { subscribed.complete(Unit) }.collect { events.send(it) } }
            withTimeout(TEST_TIMEOUT_MS) {
                subscribed.await()
                client.start(CLIENT_COLS, CLIENT_ROWS)
            }
        }
    }

    @After
    fun tearDown() {
        if (!::client.isInitialized) return
        client.close()
        scope.cancel()
        tmux("kill-server")
    }

    private suspend fun awaitState(what: String, predicate: (TmuxState) -> Boolean): TmuxState =
        try {
            withTimeout(TEST_TIMEOUT_MS) { client.state.first(predicate) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what; state=${client.state.value}", e)
        }

    private suspend inline fun <reified T : TmuxEvent> awaitEvent(timeoutMs: Long, crossinline accept: (T) -> Boolean): T =
        withTimeout(timeoutMs) {
            while (true) {
                val event = events.receive()
                if (event is T && accept(event)) return@withTimeout event
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        }

    private fun firstPane(): TmuxPane {
        val state = client.state.value
        assertTrue("no windows in $state", state.windows.isNotEmpty())
        val window = state.windows.first()
        return window.panes.first { it.id == window.activePaneId }
    }

    @Test
    fun startPopulatesState() {
        val state = client.state.value
        assertNotNull(state.sessionId)
        assertTrue(state.sessionName.orEmpty().startsWith(SESSION_NAME))
        assertEquals(1, state.windows.size)
        val window = state.windows[0]
        assertTrue(window.active)
        assertEquals(window.id, state.activeWindowId)
        assertEquals(1, window.panes.size)
        val pane = window.panes[0]
        assertEquals(window.activePaneId, pane.id)
        assertEquals(CLIENT_COLS, pane.width)
        assertEquals(CLIENT_ROWS, pane.height)
        assertEquals("bash", pane.currentCommand)
        assertEquals(mapOf(pane.id to Pair(pane.width, pane.height)), paneSizes(window.layout))
    }

    @Test
    fun sendKeysProducesOutputAndCaptureSeesIt() = runBlocking {
        val pane = firstPane()
        client.sendKeys(pane.id, "echo salchang-\$((6*7))\n".toByteArray())
        val collected = ByteArrayOutputStream()
        awaitEvent<TmuxEvent.PaneOutput>(TEST_TIMEOUT_MS) {
            if (it.paneId == pane.id) collected.write(it.bytes)
            collected.toString(Charsets.UTF_8.name()).contains("salchang-42")
        }
        val captured = client.capturePane(pane.id).toString(Charsets.UTF_8)
        assertTrue(captured, captured.contains("salchang-42"))
        assertTrue(captured, captured.contains("\r\n"))
        assertFalse("trailing blank lines should be stripped", captured.endsWith("\r\n"))
        val (x, y) = client.paneCursor(pane.id)
        assertTrue("cursor $x,$y", x >= 0 && y >= 0 && y < CLIENT_ROWS)
    }

    @Test
    fun externalMetaOptionArrivesAsEventAndState() = runBlocking {
        val pane = firstPane()
        val (code, out) = tmux("set-option", "-p", "-t", pane.id, META_OPTION, "{\"a\":1}")
        assertEquals(out, 0, code)
        val event = awaitEvent<TmuxEvent.MetaChanged>(META_EVENT_TIMEOUT_MS) { it.paneId == pane.id }
        assertEquals("{\"a\":1}", event.value)
        assertEquals(pane.windowId, event.windowId)
        val state = awaitState("meta in state") { it.window(pane.windowId)?.meta?.get(pane.id) == "{\"a\":1}" }
        assertEquals("{\"a\":1}", state.window(pane.windowId)!!.meta[pane.id])
    }

    @Test
    fun metaSetBeforeConnectIsVisibleAfterRefresh() = runBlocking {
        val pane = firstPane()
        // Set through the CLI, then force a refresh: fetched (not notified) values must be picked up.
        // The subscription may also notify; either path must yield the same state.
        val (code, out) = tmux("set-option", "-p", "-t", pane.id, META_OPTION, "pre")
        assertEquals(out, 0, code)
        client.refresh()
        awaitState("fetched meta") { it.window(pane.windowId)?.meta?.get(pane.id) == "pre" }
        Unit
    }

    @Test
    fun newKillAndRenameWindow() = runBlocking {
        val original = client.state.value.windows.map { it.id }.toSet()
        client.newWindow()
        val added = awaitState("window added") { it.windows.size == original.size + 1 }
        val newWindow = added.windows.first { it.id !in original }
        assertTrue(newWindow.panes.isNotEmpty())
        assertEquals(newWindow.id, added.activeWindowId)

        val name = "it's a \"na#me\" ;x"
        client.renameWindow(newWindow.id, name)
        awaitState("window renamed") { it.window(newWindow.id)?.name == name }
        val (_, listed) = tmux("list-windows", "-F", "#{window_id} #{window_name}")
        assertTrue(listed, listed.contains("${newWindow.id} $name"))

        client.selectWindow(original.first())
        awaitState("window selected") { it.activeWindowId == original.first() }

        client.killWindow(newWindow.id)
        awaitState("window killed") { it.windows.size == original.size && it.window(newWindow.id) == null }
        Unit
    }

    @Test
    fun commandErrorsAreReported() = runBlocking {
        val result = client.command("kill-window -t @999")
        assertTrue(result.toString(), result is CommandResult.Error)
        assertTrue(result.lines.toString(), result.lines.any { it.contains("@999") })
        val thrown = runCatching { client.killWindow("@999") }.exceptionOrNull()
        assertTrue(thrown.toString(), thrown is TmuxCommandException)
    }

    @Test
    fun commandInKeyOrderSurvivesNestedReplyBlocks() = runBlocking {
        // A `;` list and an if-shell -F branch each answer with one block per nested command;
        // the fence must swallow the extra blocks so the next command still gets its own reply.
        val list = client.commandInKeyOrder("display-message -p one ; display-message -p two")
        assertEquals(CommandResult.Success(listOf("one")), list)
        val nested = client.commandInKeyOrder("if-shell -F 1 \"display-message -p a ; display-message -p b\"")
        assertTrue(nested.toString(), nested is CommandResult.Success)
        assertEquals(CommandResult.Success(listOf("after")), client.command("display-message -p after"))
        val failed = client.commandInKeyOrder("kill-window -t @999")
        assertTrue(failed.toString(), failed is CommandResult.Error)
        assertEquals(CommandResult.Success(listOf("still in sync")), client.command("display-message -p 'still in sync'"))

        // The server's own prefix table parses and a binding's command runs as printed.
        val (code, listed) = tmux("list-keys", "-T", "prefix")
        assertEquals(listed, 0, code)
        val bindings = parseListKeys(listed.lines())
        val newWindow = bindings.first { it.table == PREFIX_TABLE && it.key == "c" }
        assertEquals("new-window", newWindow.command)
        assertEquals("last-window", bindings.first { it.key == "l" }.command)
        val before = client.state.value.windows.size
        val run = client.commandInKeyOrder(newWindow.command)
        assertTrue(run.toString(), run is CommandResult.Success)
        awaitState("window added by binding") { it.windows.size == before + 1 }
        Unit
    }

    @Test
    fun serverExitProducesExitEvent() = runBlocking {
        tmux("kill-server")
        val event = awaitEvent<TmuxEvent>(TEST_TIMEOUT_MS) { it is TmuxEvent.Exit || it is TmuxEvent.TransportError }
        assertTrue(event.toString(), event is TmuxEvent.Exit)
    }
}

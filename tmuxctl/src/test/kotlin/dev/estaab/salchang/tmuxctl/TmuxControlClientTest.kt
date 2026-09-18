package dev.estaab.salchang.tmuxctl

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val TEST_TIMEOUT_MS: Long = 5_000L
private const val POLL_INTERVAL_MS: Long = 10L
private const val PIPE_BUFFER_BYTES: Int = 1 shl 16

/** Scripted transport: the test feeds tmux's side of the conversation and inspects what the client wrote. */
private class FakeTransport : ControlTransport {
    private val toClient = PipedOutputStream()
    override val input: InputStream = PipedInputStream(toClient, PIPE_BUFFER_BYTES)
    private val written = ByteArrayOutputStream()
    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = synchronized(written) { written.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = synchronized(written) { written.write(b, off, len) }
    }
    @Volatile var closed: Boolean = false

    fun feed(text: String) {
        toClient.write(text.toByteArray(Charsets.UTF_8))
        toClient.flush()
    }

    fun writtenText(): String = synchronized(written) { written.toString(Charsets.UTF_8.name()) }

    override fun close() {
        closed = true
        try {
            toClient.close()
        } catch (_: IOException) {
        }
        input.close()
    }
}

class TmuxControlClientTest {
    private val transport = FakeTransport()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = Channel<TmuxEvent>(Channel.UNLIMITED)
    private lateinit var client: TmuxControlClient

    @Before
    fun setUp() = runBlocking {
        client = TmuxControlClient(transport, scope)
        val subscribed = CompletableDeferred<Unit>()
        scope.launch { client.events.onSubscription { subscribed.complete(Unit) }.collect { events.send(it) } }
        withTimeout(TEST_TIMEOUT_MS) { subscribed.await() }
    }

    @After
    fun tearDown() {
        client.close()
        scope.cancel()
    }

    private suspend fun awaitWritten(fragment: String) {
        withTimeout(TEST_TIMEOUT_MS) {
            while (!transport.writtenText().contains(fragment)) delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun nextEvent(): TmuxEvent = withTimeout(TEST_TIMEOUT_MS) { events.receive() }

    @Test
    fun unsolicitedBlockIsIgnoredAndRepliesAreFifo() = runBlocking {
        // tmux emits this before we have sent anything.
        transport.feed("%begin 1 1 0\n%end 1 1 0\n")
        val first = async { client.command("first") }
        val second = async { client.command("second") }
        awaitWritten("second\n")
        assertEquals("first\nsecond\n", transport.writtenText())
        transport.feed("%begin 1 2 1\nline a\nline b\n%end 1 2 1\n")
        transport.feed("%begin 1 3 1\ncan't find window: @9\n%error 1 3 1\n")
        assertEquals(CommandResult.Success(listOf("line a", "line b")), withTimeout(TEST_TIMEOUT_MS) { first.await() })
        assertEquals(CommandResult.Error(listOf("can't find window: @9")), withTimeout(TEST_TIMEOUT_MS) { second.await() })
    }

    @Test
    fun trackedReplyIsDeliveredAsEventInWireOrder() = runBlocking {
        val token: Long = client.newToken()
        transport.feed("%output %0 a\n")
        client.submitTracked("cmd", token)
        awaitWritten("cmd\n")
        transport.feed("%begin 1 2 1\nline\n%end 1 2 1\n%output %0 b\n")
        assertArrayEquals("a".toByteArray(), (nextEvent() as TmuxEvent.PaneOutput).bytes)
        assertEquals(TmuxEvent.CommandReply(token, CommandResult.Success(listOf("line"))), nextEvent())
        assertArrayEquals("b".toByteArray(), (nextEvent() as TmuxEvent.PaneOutput).bytes)
    }

    @Test
    fun interruptedBlockFailsTrackedAndAwaitedReplies() = runBlocking {
        val token: Long = client.newToken()
        client.submitTracked("first", token)
        val second = async { client.command("second") }
        awaitWritten("second\n")
        // tmux starts the second reply without ending the first.
        transport.feed("%begin 1 2 1\n%begin 1 3 1\n%end 1 3 1\n")
        assertEquals(TmuxEvent.CommandReply(token, CommandResult.Error(listOf(BLOCK_INTERRUPTED_MESSAGE))), nextEvent())
        assertEquals(CommandResult.Success(emptyList()), withTimeout(TEST_TIMEOUT_MS) { second.await() })
    }

    @Test
    fun crlfLinesAndEmptyReply() = runBlocking {
        val reply = async { client.command("noop") }
        awaitWritten("noop\n")
        transport.feed("%begin 1 2 1\r\n%end 1 2 1\r\n")
        assertEquals(CommandResult.Success(emptyList()), withTimeout(TEST_TIMEOUT_MS) { reply.await() })
    }

    @Test
    fun paneOutputIsDeliveredInOrder() = runBlocking {
        transport.feed("%output %0 a\\015\\012\n%output %0 b\n%extended-output %0 5 : c\n")
        val a = nextEvent() as TmuxEvent.PaneOutput
        val b = nextEvent() as TmuxEvent.PaneOutput
        val c = nextEvent() as TmuxEvent.PaneOutput
        assertEquals("%0", a.paneId)
        assertArrayEquals("a\r\n".toByteArray(), a.bytes)
        assertArrayEquals("b".toByteArray(), b.bytes)
        assertArrayEquals("c".toByteArray(), c.bytes)
    }

    @Test
    fun metaSubscriptionUpdatesEventsAndState() = runBlocking {
        transport.feed("%subscription-changed meta \$1 @0 0 %0 : {\"a\":1}\n")
        assertEquals(TmuxEvent.MetaChanged("%0", "@0", "{\"a\":1}"), nextEvent())
        // Window subscriptions (no pane) are ignored.
        transport.feed("%subscription-changed meta \$1 @0 0 - : x\n")
        transport.feed("%subscription-changed meta \$1 @0 0 %0 : \n")
        assertEquals(TmuxEvent.MetaChanged("%0", "@0", ""), nextEvent())
    }

    @Test
    fun exitEmitsEventAndClosesClient() = runBlocking {
        transport.feed("%exit detached\n")
        assertEquals(TmuxEvent.Exit("detached"), nextEvent())
        withTimeout(TEST_TIMEOUT_MS) { while (!transport.closed) delay(POLL_INTERVAL_MS) }
        val failed = runCatching { client.command("after-exit") }
        assertTrue(failed.exceptionOrNull() is IOException)
    }

    @Test
    fun eofWithoutExitFailsPendingCommands() = runBlocking {
        val reply = async { runCatching { client.command("hang") } }
        awaitWritten("hang\n")
        transport.close()
        val event = nextEvent()
        assertTrue(event.toString(), event is TmuxEvent.TransportError)
        val failed = withTimeout(TEST_TIMEOUT_MS) { reply.await() }
        assertTrue(failed.toString(), failed.exceptionOrNull() is IOException)
    }

    @Test
    fun sendKeysChunksAndHexEncodes() = runBlocking {
        val bytes = ByteArray(300) { it.toByte() }
        val job = async { client.sendKeys("%3", bytes) }
        awaitWritten("send-keys -t %3 -H ${hexEncode(bytes.copyOfRange(256, 300))}\n")
        val lines = transport.writtenText().trimEnd('\n').split('\n')
        assertEquals(2, lines.size)
        assertEquals("send-keys -t %3 -H ${hexEncode(bytes.copyOfRange(0, 256))}", lines[0])
        transport.feed("%begin 1 2 1\n%end 1 2 1\n%begin 1 3 1\n%end 1 3 1\n")
        withTimeout(TEST_TIMEOUT_MS) { job.await() }
    }

    @Test
    fun renameWindowQuotesName() = runBlocking {
        val job = async { client.renameWindow("@1", "it's a \"na#me\"") }
        awaitWritten("\n")
        assertEquals("rename-window -t @1 'it'\\''s a \"na#me\"'\n", transport.writtenText())
        transport.feed("%begin 1 2 1\n%end 1 2 1\n")
        withTimeout(TEST_TIMEOUT_MS) { job.await() }
    }

    @Test
    fun rejectsBadIdsAndMultilineCommands() = runBlocking {
        assertTrue(runCatching { client.sendKeys("0", byteArrayOf(1)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { client.killWindow("%0") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { client.command("a\nb") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals("", transport.writtenText())
    }
}

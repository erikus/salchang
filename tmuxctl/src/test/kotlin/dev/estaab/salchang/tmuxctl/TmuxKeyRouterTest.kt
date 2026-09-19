package dev.estaab.salchang.tmuxctl

import dev.estaab.salchang.tmuxctl.TmuxKeyRouter.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PREFIX: String = "C-n"
private val PREFIX_BYTES: ByteArray = byteArrayOf(0x0e)

private val LAST_WINDOW = TmuxKeyBinding("prefix", "C-n", "last-window", repeat = false)
private val NEXT_WINDOW = TmuxKeyBinding("prefix", "n", "next-window", repeat = false)
private val SPLIT = TmuxKeyBinding("prefix", "\"", "split-window", repeat = false)
private val RESIZE_UP = TmuxKeyBinding("prefix", "M-Up", "resize-pane -U 5", repeat = true)
private val PAGE_UP = TmuxKeyBinding("prefix", "PgUp", "copy-mode -u", repeat = false)
private val PREVIOUS_WINDOW = TmuxKeyBinding("root", "M-Left", "previous-window", repeat = false)
private val MOUSE = TmuxKeyBinding("root", "WheelUpPane", "copy-mode -e", repeat = false)

private val BINDINGS: List<TmuxKeyBinding> = listOf(LAST_WINDOW, NEXT_WINDOW, SPLIT, RESIZE_UP, PAGE_UP, PREVIOUS_WINDOW, MOUSE)

private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)
private fun send(text: String): Action = Action.Send(ascii(text))
private fun send(bytes: ByteArray): Action = Action.Send(bytes)

class TmuxKeyRouterTest {
    private val router = TmuxKeyRouter(PREFIX, BINDINGS)

    @Test
    fun textWithoutPrefixIsSentAsOneChunk() {
        assertEquals(listOf(send("hello\r")), router.route(ascii("hello\r")))
        assertFalse(router.armed)
    }

    @Test
    fun prefixThenBoundKeyRunsTheBinding() {
        assertEquals(emptyList<Action>(), router.route(PREFIX_BYTES))
        assertTrue(router.armed)
        assertEquals(listOf(Action.Run(LAST_WINDOW)), router.route(PREFIX_BYTES))
        assertFalse(router.armed)

        router.route(PREFIX_BYTES)
        assertEquals(listOf(Action.Run(NEXT_WINDOW)), router.route(ascii("n")))
    }

    @Test
    fun prefixThenUnboundKeyIsDropped() {
        router.route(PREFIX_BYTES)
        assertEquals(emptyList<Action>(), router.route(ascii("q")))
        assertFalse(router.armed)
        assertEquals(listOf(send("q")), router.route(ascii("q")))
    }

    @Test
    fun prefixAndKeyInOneChunk() {
        assertEquals(
            listOf(send("ab"), Action.Run(NEXT_WINDOW), send("c")),
            router.route(byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 0x0e, 'n'.code.toByte(), 'c'.code.toByte())),
        )
        assertFalse(router.armed)
    }

    @Test
    fun prefixStateSurvivesChunks() {
        router.route(ascii("x"))
        router.route(PREFIX_BYTES)
        assertTrue(router.armed)
        assertEquals(listOf(Action.Run(SPLIT)), router.route(ascii("\"")))
    }

    @Test
    fun rootBindingRunsWithoutPrefix() {
        assertEquals(listOf(Action.Run(PREVIOUS_WINDOW)), router.route(ascii("[D")))
        // The xterm modifier form the Termux key handler sends for Alt+Left matches too.
        assertEquals(listOf(Action.Run(PREVIOUS_WINDOW)), router.route(ascii("[1;3D")))
    }

    @Test
    fun escapeAloneAndOtherSequencesAreSent() {
        assertEquals(listOf(send("")), router.route(ascii("")))
        assertEquals(listOf(send("[A")), router.route(ascii("[A")))
        assertEquals(listOf(send("x")), router.route(ascii("x")))
    }

    @Test
    fun emulatorRepliesPassThroughWithoutDisarming() {
        router.route(PREFIX_BYTES)
        assertEquals(listOf(send("[24;80R")), router.route(ascii("[24;80R")))
        assertTrue(router.armed)
        assertEquals(listOf(Action.Run(NEXT_WINDOW)), router.route(ascii("n")))
    }

    @Test
    fun bindingKeyAliasesMatchTypedBytes() {
        router.route(PREFIX_BYTES)
        assertEquals(listOf(Action.Run(PAGE_UP)), router.route(ascii("[5~")))
        router.route(PREFIX_BYTES)
        assertEquals(listOf(Action.Run(RESIZE_UP)), router.route(ascii("[A")))
        router.route(PREFIX_BYTES)
        assertEquals(listOf(Action.Run(RESIZE_UP)), router.route(ascii("[1;3A")))
    }

    @Test
    fun prefixTwiceWithoutPrefixBindingSendsThePrefix() {
        val bare = TmuxKeyRouter(PREFIX, listOf(NEXT_WINDOW))
        bare.route(PREFIX_BYTES)
        assertEquals(listOf(send(PREFIX_BYTES)), bare.route(PREFIX_BYTES))
        assertFalse(bare.armed)
    }

    @Test
    fun sendPrefixBindingIsHandledLocally() {
        val default = TmuxKeyRouter("C-b", listOf(TmuxKeyBinding("prefix", "C-b", "send-prefix", repeat = false)))
        default.route(byteArrayOf(0x02))
        assertEquals(listOf(send(byteArrayOf(0x02))), default.route(byteArrayOf(0x02)))
    }

    @Test
    fun resetDisarms() {
        router.route(PREFIX_BYTES)
        router.reset()
        assertFalse(router.armed)
        assertEquals(listOf(send("n")), router.route(ascii("n")))
    }

    @Test
    fun unmappablePrefixNeverArms() {
        val none = TmuxKeyRouter("None", BINDINGS)
        assertEquals(listOf(send(PREFIX_BYTES)), none.route(PREFIX_BYTES))
        assertFalse(none.armed)
        assertEquals(listOf(Action.Run(PREVIOUS_WINDOW)), none.route(ascii("[D")))
    }
}

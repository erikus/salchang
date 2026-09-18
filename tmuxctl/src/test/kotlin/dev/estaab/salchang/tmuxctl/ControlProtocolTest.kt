package dev.estaab.salchang.tmuxctl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlProtocolTest {
    private fun parse(line: String, insideBlock: Boolean = false): ControlLine = parseControlLine(line, insideBlock)

    @Test
    fun blockMarkers() {
        assertEquals(ControlLine.Begin(1789681155L, 269L, 0), parse("%begin 1789681155 269 0"))
        assertEquals(ControlLine.End(1789681155L, 277L, 1), parse("%end 1789681155 277 1"))
        assertEquals(ControlLine.Error(1789681157L, 300L, 1), parse("%error 1789681157 300 1"))
        assertEquals(ControlLine.Unknown("%begin x"), parse("%begin x"))
    }

    @Test
    fun bodyLinesInsideBlock() {
        assertEquals(ControlLine.Body("@0\t0\tbash"), parse("@0\t0\tbash", insideBlock = true))
        assertEquals(ControlLine.Body("%window-add @9"), parse("%window-add @9", insideBlock = true))
        assertEquals(ControlLine.Body(""), parse("", insideBlock = true))
        assertEquals(ControlLine.End(1L, 2L, 1), parse("%end 1 2 1", insideBlock = true))
        assertEquals(ControlLine.Error(1L, 2L, 1), parse("%error 1 2 1", insideBlock = true))
        // Outside a block a non-% line is unexpected.
        assertEquals(ControlLine.Unknown("stray"), parse("stray"))
    }

    @Test
    fun outputUnescapesOctal() {
        val line = parse("%output %0 echo hi\\015\\012\\033[K\\134x")
        val expected = "echo hi\r\n[K\\x".toByteArray(Charsets.UTF_8)
        assertEquals(ControlLine.Output("%0", expected), line)
    }

    @Test
    fun outputKeepsUtf8AndEmptyPayload() {
        assertEquals(ControlLine.Output("%12", "héllo €".toByteArray(Charsets.UTF_8)), parse("%output %12 héllo €"))
        assertEquals(ControlLine.Output("%3", ByteArray(0)), parse("%output %3"))
        assertEquals(ControlLine.Output("%3", ByteArray(0)), parse("%output %3 "))
    }

    @Test
    fun outputBytesArePassedThroughByteExact() {
        // A UTF-8 sequence split across lines: only the first byte of 'e-acute' (0xC3) here.
        val raw = "%output %1 a".toByteArray(Charsets.US_ASCII) + byteArrayOf(0xC3.toByte())
        val parsed = parseControlLine(raw, insideBlock = false) as ControlLine.Output
        assertArrayEquals(byteArrayOf('a'.code.toByte(), 0xC3.toByte()), parsed.data)
    }

    @Test
    fun extendedOutput() {
        val line = parse("%extended-output %2 17 : \\033]0;title\\007")
        assertEquals(ControlLine.ExtendedOutput("%2", 17L, "]0;title".toByteArray()), line)
        // Extra args before ':' are skipped.
        assertEquals(
            ControlLine.ExtendedOutput("%2", 0L, "x".toByteArray()),
            parse("%extended-output %2 0 foo bar : x"),
        )
        assertEquals(ControlLine.ExtendedOutput("%2", 0L, ByteArray(0)), parse("%extended-output %2 0 : "))
        assertTrue(parse("%extended-output %2 0 no-colon") is ControlLine.Unknown)
    }

    @Test
    fun unescapeOutputStandalone() {
        assertArrayEquals("\\".toByteArray(), unescapeOutput("\\134"))
        assertArrayEquals("[K".toByteArray(), unescapeOutput("\\033[K"))
        assertArrayEquals("\\12".toByteArray(), unescapeOutput("\\12")) // too short: literal
        assertArrayEquals("\\8ab".toByteArray(), unescapeOutput("\\8ab")) // not octal: literal
        assertArrayEquals(byteArrayOf(0xff.toByte()), unescapeOutput("\\377"))
    }

    @Test
    fun hexEncoding() {
        assertEquals("1b 5b 41", hexEncode(byteArrayOf(0x1b, 0x5b, 0x41)))
        assertEquals("00 ff", hexEncode(byteArrayOf(0, 0xff.toByte())))
        assertEquals("", hexEncode(ByteArray(0)))
    }

    @Test
    fun subscriptionChangedPaneWindowSession() {
        assertEquals(
            ControlLine.SubscriptionChanged("meta", "$1", "@2", 3, "%4", "{\"a\":1,\"b\":\"x#y\\\\z\"}"),
            parse("%subscription-changed meta $1 @2 3 %4 : {\"a\":1,\"b\":\"x#y\\\\z\"}"),
        )
        assertEquals(
            ControlLine.SubscriptionChanged("meta", "$1", "@2", 3, null, "value with : colon"),
            parse("%subscription-changed meta $1 @2 3 - : value with : colon"),
        )
        assertEquals(
            ControlLine.SubscriptionChanged("s", "$1", null, null, null, "v"),
            parse("%subscription-changed s $1 - - - : v"),
        )
        // Empty value, with and without the trailing space.
        assertEquals(
            ControlLine.SubscriptionChanged("meta", "$1", "@2", 1, "%3", ""),
            parse("%subscription-changed meta $1 @2 1 %3 : "),
        )
        assertEquals(
            ControlLine.SubscriptionChanged("meta", "$1", "@2", 1, "%3", ""),
            parse("%subscription-changed meta $1 @2 1 %3 :"),
        )
        // Extra args after the pane id are ignored.
        assertEquals(
            ControlLine.SubscriptionChanged("meta", "$1", "@2", 1, "%3", "v"),
            parse("%subscription-changed meta $1 @2 1 %3 extra 42 : v"),
        )
        assertTrue(parse("%subscription-changed meta $1 @2") is ControlLine.Unknown)
        assertTrue(parse("%subscription-changed meta $1 @2 1 %3 no-colon") is ControlLine.Unknown)
    }

    @Test
    fun layoutChange() {
        assertEquals(
            ControlLine.LayoutChange("@0", "b25d,80x24,0,0,0", "b25d,80x24,0,0,0", "*"),
            parse("%layout-change @0 b25d,80x24,0,0,0 b25d,80x24,0,0,0 *"),
        )
        assertEquals(
            ControlLine.LayoutChange("@0", "b25d,80x24,0,0,0", "b25d,80x24,0,0,0", ""),
            parse("%layout-change @0 b25d,80x24,0,0,0 b25d,80x24,0,0,0 "),
        )
        assertEquals(
            ControlLine.LayoutChange("@0", "b25d,80x24,0,0,0", "b25d,80x24,0,0,0", ""),
            parse("%layout-change @0 b25d,80x24,0,0,0 b25d,80x24,0,0,0"),
        )
    }

    @Test
    fun windowAndSessionNotifications() {
        assertEquals(ControlLine.WindowAdd("@0"), parse("%window-add @0"))
        assertEquals(ControlLine.WindowClose("@7"), parse("%window-close @7"))
        assertEquals(ControlLine.WindowRenamed("@0", "it's a \"name\""), parse("%window-renamed @0 it's a \"name\""))
        assertEquals(ControlLine.WindowRenamed("@0", ""), parse("%window-renamed @0"))
        assertEquals(ControlLine.WindowPaneChanged("@0", "%2"), parse("%window-pane-changed @0 %2"))
        assertEquals(ControlLine.SessionChanged("$1", "demo-1"), parse("%session-changed $1 demo-1"))
        assertEquals(ControlLine.SessionWindowChanged("$1", "@2"), parse("%session-window-changed $1 @2"))
        assertEquals(ControlLine.SessionsChanged, parse("%sessions-changed"))
        assertEquals(ControlLine.SessionRenamed("new name"), parse("%session-renamed new name"))
        assertEquals(ControlLine.UnlinkedWindowAdd("@1"), parse("%unlinked-window-add @1"))
        assertEquals(ControlLine.UnlinkedWindowClose("@1"), parse("%unlinked-window-close @1"))
        assertEquals(ControlLine.UnlinkedWindowRenamed("@1", "x y"), parse("%unlinked-window-renamed @1 x y"))
        assertEquals(ControlLine.PaneModeChanged("%5"), parse("%pane-mode-changed %5"))
        assertEquals(ControlLine.Pause("%5"), parse("%pause %5"))
        assertEquals(ControlLine.Continue("%5"), parse("%continue %5"))
        assertEquals(ControlLine.Message("hello there"), parse("%message hello there"))
        assertEquals(ControlLine.ConfigError("bad: thing"), parse("%config-error bad: thing"))
        assertEquals(ControlLine.ClientDetached("/dev/pts/3"), parse("%client-detached /dev/pts/3"))
        assertEquals(
            ControlLine.ClientSessionChanged("/dev/pts/3", "$2", "other"),
            parse("%client-session-changed /dev/pts/3 $2 other"),
        )
        assertEquals(ControlLine.Exit(null), parse("%exit"))
        assertEquals(ControlLine.Exit("detached"), parse("%exit detached"))
    }

    @Test
    fun malformedNotificationsAreUnknown() {
        assertEquals(ControlLine.Unknown("%window-add 0"), parse("%window-add 0"))
        assertEquals(ControlLine.Unknown("%window-pane-changed @0"), parse("%window-pane-changed @0"))
        assertEquals(ControlLine.Unknown("%no-such-thing a b"), parse("%no-such-thing a b"))
        assertEquals(ControlLine.Unknown("%output 0 x"), parse("%output 0 x"))
    }

    @Test
    fun idPredicates() {
        assertTrue(isPaneId("%0") && isWindowId("@12") && isSessionId("$3"))
        assertTrue(!isPaneId("%") && !isPaneId("@1") && !isWindowId("@x") && !isSessionId("1"))
    }
}

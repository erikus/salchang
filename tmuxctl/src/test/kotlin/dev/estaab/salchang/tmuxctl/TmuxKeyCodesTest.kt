package dev.estaab.salchang.tmuxctl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)
private const val ESC: Int = 0x1b

class TmuxKeyCodesTest {
    private fun assertKey(name: String, expected: ByteArray) {
        assertArrayEquals(name, expected, TmuxKeyCodes.bytesForKey(name))
        assertEquals(name, TmuxKeyCodes.keyForBytes(expected))
    }

    @Test
    fun controlKeys() {
        assertKey("C-a", bytes(0x01))
        assertKey("C-n", bytes(0x0e))
        assertKey("C-z", bytes(0x1a))
        assertKey("C-Space", bytes(0x00))
        assertKey("C-\\", bytes(0x1c))
        assertKey("C-]", bytes(0x1d))
        assertKey("C-^", bytes(0x1e))
        assertKey("C-_", bytes(0x1f))
        // Spellings tmux accepts but prints differently map to the same bytes.
        assertArrayEquals(bytes(0x00), TmuxKeyCodes.bytesForKey("C-@"))
        assertArrayEquals(bytes(0x1b), TmuxKeyCodes.bytesForKey("C-["))
        assertArrayEquals(bytes(0x09), TmuxKeyCodes.bytesForKey("C-i"))
        assertArrayEquals(bytes(0x0d), TmuxKeyCodes.bytesForKey("C-M"))
        assertEquals("C-Space", TmuxKeyCodes.keyForBytes(bytes(0x00)))
        assertEquals("Tab", TmuxKeyCodes.keyForBytes(bytes(0x09)))
        assertEquals("Enter", TmuxKeyCodes.keyForBytes(bytes(0x0d)))
        assertEquals("Escape", TmuxKeyCodes.keyForBytes(bytes(0x1b)))
    }

    @Test
    fun namedKeys() {
        assertKey("Space", ascii(" "))
        assertKey("Enter", ascii("\r"))
        assertKey("Tab", ascii("\t"))
        assertKey("BSpace", bytes(0x7f))
        assertKey("Escape", bytes(ESC))
        assertKey("Up", ascii("[A"))
        assertKey("Down", ascii("[B"))
        assertKey("Right", ascii("[C"))
        assertKey("Left", ascii("[D"))
        assertKey("Home", ascii("[H"))
        assertKey("End", ascii("[F"))
        assertKey("PPage", ascii("[5~"))
        assertKey("NPage", ascii("[6~"))
        assertKey("IC", ascii("[2~"))
        assertKey("DC", ascii("[3~"))
        assertKey("BTab", ascii("[Z"))
        assertKey("F1", ascii("OP"))
        assertKey("F4", ascii("OS"))
        assertKey("F5", ascii("[15~"))
        assertKey("F12", ascii("[24~"))
    }

    @Test
    fun aliasesMapToCanonicalNames() {
        for (alias in listOf("PgUp", "PageUp")) assertEquals("PPage", TmuxKeyCodes.keyForBytes(TmuxKeyCodes.bytesForKey(alias)!!))
        for (alias in listOf("PgDn", "PageDown")) assertEquals("NPage", TmuxKeyCodes.keyForBytes(TmuxKeyCodes.bytesForKey(alias)!!))
        assertEquals("IC", TmuxKeyCodes.keyForBytes(TmuxKeyCodes.bytesForKey("Insert")!!))
        assertEquals("DC", TmuxKeyCodes.keyForBytes(TmuxKeyCodes.bytesForKey("Delete")!!))
    }

    @Test
    fun printableAndUnicodeCharacters() {
        assertKey("a", ascii("a"))
        assertKey("Z", ascii("Z"))
        assertKey("\"", ascii("\""))
        assertKey("é", ascii("é"))
        assertKey("€", ascii("€"))
    }

    @Test
    fun metaKeys() {
        assertKey("M-t", ascii("t"))
        assertKey("M-Left", ascii("[D"))
        assertKey("M-Space", ascii(" "))
        assertKey("M-Enter", ascii("\r"))
        assertKey("M-1", ascii("1"))
        assertKey("C-M-Left", ascii("[1;5D"))
    }

    @Test
    fun modifiedSpecialKeys() {
        assertKey("S-Up", ascii("[1;2A"))
        assertKey("C-Up", ascii("[1;5A"))
        assertKey("C-S-Up", ascii("[1;6A"))
        assertKey("C-Right", ascii("[1;5C"))
        assertKey("C-Home", ascii("[1;5H"))
        assertKey("C-F1", ascii("[1;5P"))
        assertKey("S-F5", ascii("[15;2~"))
        assertKey("C-PPage", ascii("[5;5~"))
        assertKey("C-DC", ascii("[3;5~"))
    }

    @Test
    fun alternateInputFormsMapToTheSameNames() {
        // Application cursor mode (SS3) arrows and home/end.
        assertEquals("Up", TmuxKeyCodes.keyForBytes(ascii("OA")))
        assertEquals("Left", TmuxKeyCodes.keyForBytes(ascii("OD")))
        assertEquals("Home", TmuxKeyCodes.keyForBytes(ascii("OH")))
        assertEquals("End", TmuxKeyCodes.keyForBytes(ascii("OF")))
        // vt220 / rxvt home and end.
        assertEquals("Home", TmuxKeyCodes.keyForBytes(ascii("[1~")))
        assertEquals("End", TmuxKeyCodes.keyForBytes(ascii("[4~")))
        assertEquals("Home", TmuxKeyCodes.keyForBytes(ascii("[7~")))
        assertEquals("End", TmuxKeyCodes.keyForBytes(ascii("[8~")))
        // xterm modifier form for meta (what the Termux key handler sends for Alt+arrow).
        assertEquals("M-Left", TmuxKeyCodes.keyForBytes(ascii("[1;3D")))
        assertEquals("C-M-S-Up", TmuxKeyCodes.keyForBytes(ascii("[1;8A")))
        assertEquals("M-Escape", TmuxKeyCodes.keyForBytes(ascii("")))
        assertEquals("M-[", TmuxKeyCodes.keyForBytes(ascii("[")))
    }

    @Test
    fun unknownNamesAndNonKeysAreNull() {
        for (name in listOf("MouseDown1Pane", "WheelUpPane", "Any", "KPEnter", "F13", "S-a", "C-S-Space", "", "C-", "Meta")) {
            assertNull(name, TmuxKeyCodes.bytesForKey(name))
        }
        assertNull(TmuxKeyCodes.keyForBytes(ByteArray(0)))
        assertNull(TmuxKeyCodes.keyForBytes(ascii("ab")))
        assertNull(TmuxKeyCodes.keyForBytes(bytes(0x80)))
        assertNull(TmuxKeyCodes.keyForBytes(bytes(0xc3)))
        // Emulator replies (cursor position report, device attributes) are not keys.
        assertNull(TmuxKeyCodes.keyForBytes(ascii("[24;80R")))
        assertNull(TmuxKeyCodes.keyForBytes(ascii("[?6c")))
        assertNull(TmuxKeyCodes.keyForBytes(ascii("[1;5X")))
    }
}

class TmuxKeyTokenizerTest {
    private fun tokens(vararg parts: String): List<ByteArray> = parts.map { ascii(it) }

    private fun assertTokens(expected: List<ByteArray>, input: ByteArray) {
        val actual: List<ByteArray> = TmuxKeyTokenizer.tokenize(input)
        assertEquals(expected.map { it.toList() }, actual.map { it.toList() })
    }

    @Test
    fun plainTextIsOneTokenPerByte() {
        assertTokens(tokens("a", "b", "\r"), ascii("ab\r"))
        assertTokens(emptyList(), ByteArray(0))
    }

    @Test
    fun utf8CharactersAreOneToken() {
        assertTokens(tokens("é", "x", "€", "😀"), ascii("éx€😀"))
    }

    @Test
    fun malformedUtf8FallsBackToSingleBytes() {
        assertTokens(listOf(bytes(0xc3), bytes(0x41)), bytes(0xc3, 0x41))
        assertTokens(listOf(bytes(0xe2), bytes(0x82)), bytes(0xe2, 0x82))
    }

    @Test
    fun escapeSequencesAreOneToken() {
        assertTokens(tokens("[A", "x"), ascii("[Ax"))
        assertTokens(tokens("[1;5D"), ascii("[1;5D"))
        assertTokens(tokens("[24;80R", "OP"), ascii("[24;80ROP"))
        assertTokens(tokens("[Z"), ascii("[Z"))
    }

    @Test
    fun metaKeysAreOneToken() {
        assertTokens(tokens("t", "a"), ascii("ta"))
        assertTokens(tokens("é"), ascii("é"))
        assertTokens(tokens("[D", "b"), ascii("[Db"))
    }

    @Test
    fun loneOrTruncatedEscapeAtEndOfChunk() {
        assertTokens(tokens("a", ""), ascii("a"))
        assertTokens(tokens("["), ascii("["))
        assertTokens(tokens("O"), ascii("O"))
        assertTokens(tokens("[1;"), ascii("[1;"))
        assertTokens(tokens(""), ascii(""))
    }
}

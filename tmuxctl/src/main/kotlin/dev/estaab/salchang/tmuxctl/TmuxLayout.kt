package dev.estaab.salchang.tmuxctl

/** Length of the hex checksum that prefixes a tmux layout string (`468d,`). */
private const val LAYOUT_CHECKSUM_LENGTH: Int = 4

private const val VERTICAL_OPEN: Char = '['
private const val VERTICAL_CLOSE: Char = ']'
private const val HORIZONTAL_OPEN: Char = '{'
private const val HORIZONTAL_CLOSE: Char = '}'
private const val FIELD_SEPARATOR: Char = ','
private const val SIZE_SEPARATOR: Char = 'x'

/**
 * A node of a parsed tmux window layout (`#{window_layout}`). Sizes are in
 * cells; [x]/[y] are offsets from the window's top-left corner.
 */
sealed interface LayoutNode {
    val width: Int
    val height: Int
    val x: Int
    val y: Int

    /** A leaf: one pane. [paneNumber] is the numeric part of the pane id (`3` for `%3`). */
    data class Pane(
        override val width: Int,
        override val height: Int,
        override val x: Int,
        override val y: Int,
        val paneNumber: Int,
    ) : LayoutNode {
        /** The pane id as tmux prints it elsewhere, e.g. `%3`. */
        val paneId: String get() = "%$paneNumber"
    }

    /**
     * A container. [horizontal] is true for `{...}` (children side by side, left to
     * right) and false for `[...]` (children stacked top to bottom).
     */
    data class Split(
        override val width: Int,
        override val height: Int,
        override val x: Int,
        override val y: Int,
        val horizontal: Boolean,
        val children: List<LayoutNode>,
    ) : LayoutNode
}

/** Thrown when a layout string does not follow tmux's grammar. */
class LayoutParseException(message: String) : IllegalArgumentException(message)

/**
 * Parses a tmux layout string such as `468d,100x30,0,0[100x15,0,0,1,100x14,0,16,2]`
 * (with or without the leading checksum) into a [LayoutNode] tree.
 *
 * @throws LayoutParseException on malformed input.
 */
fun parseLayout(layout: String): LayoutNode {
    val parser = LayoutParser(stripChecksum(layout))
    val root = parser.parseNode()
    parser.expectEnd()
    return root
}

/**
 * Returns the size of every pane in [layout] as `paneId -> (cols, rows)`, with pane
 * ids in the `%N` form used by the rest of the protocol.
 *
 * @throws LayoutParseException on malformed input.
 */
fun paneSizes(layout: String): Map<String, Pair<Int, Int>> {
    val sizes = LinkedHashMap<String, Pair<Int, Int>>()
    fun walk(node: LayoutNode) {
        when (node) {
            is LayoutNode.Pane -> sizes[node.paneId] = Pair(node.width, node.height)
            is LayoutNode.Split -> node.children.forEach(::walk)
        }
    }
    walk(parseLayout(layout))
    return sizes
}

private fun stripChecksum(layout: String): String {
    if (layout.length > LAYOUT_CHECKSUM_LENGTH &&
        layout[LAYOUT_CHECKSUM_LENGTH] == FIELD_SEPARATOR &&
        layout.substring(0, LAYOUT_CHECKSUM_LENGTH).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    ) {
        return layout.substring(LAYOUT_CHECKSUM_LENGTH + 1)
    }
    return layout
}

private class LayoutParser(private val s: String) {
    private var pos: Int = 0

    fun parseNode(): LayoutNode {
        val width = readInt()
        expect(SIZE_SEPARATOR)
        val height = readInt()
        expect(FIELD_SEPARATOR)
        val x = readInt()
        expect(FIELD_SEPARATOR)
        val y = readInt()
        return when (peek()) {
            FIELD_SEPARATOR -> {
                pos++
                LayoutNode.Pane(width, height, x, y, readInt())
            }
            VERTICAL_OPEN -> LayoutNode.Split(width, height, x, y, horizontal = false, children = readChildren(VERTICAL_OPEN, VERTICAL_CLOSE))
            HORIZONTAL_OPEN -> LayoutNode.Split(width, height, x, y, horizontal = true, children = readChildren(HORIZONTAL_OPEN, HORIZONTAL_CLOSE))
            else -> fail("expected ',', '[' or '{'")
        }
    }

    fun expectEnd() {
        if (pos != s.length) fail("trailing characters")
    }

    private fun readChildren(open: Char, close: Char): List<LayoutNode> {
        expect(open)
        val children = ArrayList<LayoutNode>()
        while (true) {
            children.add(parseNode())
            when (peek()) {
                FIELD_SEPARATOR -> pos++
                close -> {
                    pos++
                    break
                }
                else -> fail("expected ',' or '$close'")
            }
        }
        if (children.isEmpty()) fail("empty container")
        return children
    }

    private fun peek(): Char? = if (pos < s.length) s[pos] else null

    private fun expect(c: Char) {
        if (peek() != c) fail("expected '$c'")
        pos++
    }

    private fun readInt(): Int {
        val start = pos
        while (pos < s.length && s[pos] in '0'..'9') pos++
        if (start == pos) fail("expected a number")
        return s.substring(start, pos).toInt()
    }

    private fun fail(what: String): Nothing =
        throw LayoutParseException("malformed layout at offset $pos ($what): $s")
}

package dev.estaab.salchang.tmuxctl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TmuxLayoutTest {
    @Test
    fun singlePane() {
        assertEquals(LayoutNode.Pane(100, 30, 0, 0, 0), parseLayout("a87d,100x30,0,0,0"))
        assertEquals(mapOf("%0" to Pair(100, 30)), paneSizes("a87d,100x30,0,0,0"))
        // Checksum is optional.
        assertEquals(mapOf("%7" to Pair(80, 24)), paneSizes("80x24,0,0,7"))
    }

    @Test
    fun verticalSplit() {
        val layout = "468d,100x30,0,0[100x15,0,0,1,100x14,0,16,2]"
        assertEquals(
            LayoutNode.Split(
                100, 30, 0, 0, horizontal = false,
                children = listOf(LayoutNode.Pane(100, 15, 0, 0, 1), LayoutNode.Pane(100, 14, 0, 16, 2)),
            ),
            parseLayout(layout),
        )
        assertEquals(mapOf("%1" to Pair(100, 15), "%2" to Pair(100, 14)), paneSizes(layout))
    }

    @Test
    fun nestedSplits() {
        val layout = "1234,159x48,0,0{79x48,0,0,0,79x48,80,0[79x24,80,0,1,79x23,80,25{39x23,80,25,2,39x23,120,25,3}]}"
        assertEquals(
            LayoutNode.Split(
                159, 48, 0, 0, horizontal = true,
                children = listOf(
                    LayoutNode.Pane(79, 48, 0, 0, 0),
                    LayoutNode.Split(
                        79, 48, 80, 0, horizontal = false,
                        children = listOf(
                            LayoutNode.Pane(79, 24, 80, 0, 1),
                            LayoutNode.Split(
                                79, 23, 80, 25, horizontal = true,
                                children = listOf(LayoutNode.Pane(39, 23, 80, 25, 2), LayoutNode.Pane(39, 23, 120, 25, 3)),
                            ),
                        ),
                    ),
                ),
            ),
            parseLayout(layout),
        )
        assertEquals(
            mapOf("%0" to Pair(79, 48), "%1" to Pair(79, 24), "%2" to Pair(39, 23), "%3" to Pair(39, 23)),
            paneSizes(layout),
        )
    }

    @Test
    fun malformedLayoutsThrow() {
        assertThrows(LayoutParseException::class.java) { parseLayout("") }
        assertThrows(LayoutParseException::class.java) { parseLayout("abcd,100x30,0,0") }
        assertThrows(LayoutParseException::class.java) { parseLayout("abcd,100x30,0,0[100x15,0,0,1") }
        assertThrows(LayoutParseException::class.java) { parseLayout("abcd,100x30,0,0,1,extra") }
        assertThrows(LayoutParseException::class.java) { parseLayout("abcd,100x30,0,0[]") }
    }
}

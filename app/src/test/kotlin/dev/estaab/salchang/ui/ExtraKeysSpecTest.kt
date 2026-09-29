package dev.estaab.salchang.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraKeysSpecTest {
    @Test
    fun defaultParsesToOneRowWithoutErrors() {
        val result: ExtraKeysSpec.Result = ExtraKeysSpec.parse(ExtraKeysSpec.DEFAULT)
        assertEquals(emptyList<String>(), result.errors)
        assertEquals(1, result.rows.size)
        assertEquals(
            listOf("ESC", "TAB", "CTRL", "ALT", "←", "↑", "↓", "→", "HOME", "END", "PGUP", "PGDN", "-", "/", "|"),
            result.rows.single().map { it.label },
        )
        assertEquals(result.rows, ExtraKeysSpec.DEFAULT_ROWS)
    }

    @Test
    fun namesAreCaseInsensitiveAndAliased() {
        val row: List<ExtraKey> = ExtraKeysSpec.parse("esc Escape pageup ← control F10").rows.single()
        assertEquals(ExtraKey.Code("ESC", KeyEvent.KEYCODE_ESCAPE), row[0])
        assertEquals(row[0], row[1])
        assertEquals(ExtraKey.Code("PGUP", KeyEvent.KEYCODE_PAGE_UP), row[2])
        assertEquals(ExtraKey.Code("←", KeyEvent.KEYCODE_DPAD_LEFT), row[3])
        assertEquals(ExtraKey.Modifier("CTRL", ModifierKind.CTRL), row[4])
        assertEquals(ExtraKey.Code("F10", KeyEvent.KEYCODE_F10), row[5])
    }

    @Test
    fun singleCharactersTypeThemselves() {
        val row: List<ExtraKey> = ExtraKeysSpec.parse("- ~ é 😀 space").rows.single()
        assertEquals(ExtraKey.Char("-", '-'.code), row[0])
        assertEquals(ExtraKey.Char("~", '~'.code), row[1])
        assertEquals(ExtraKey.Char("é", 0xE9), row[2])
        assertEquals(ExtraKey.Char("😀", 0x1F600), row[3])
        assertEquals(ExtraKey.Char("SPACE", ' '.code), row[4])
    }

    @Test
    fun linesAreRowsAndBlankLinesAreSkipped() {
        val result: ExtraKeysSpec.Result = ExtraKeysSpec.parse("ESC  TAB\n\n  UP DOWN \t LEFT\n")
        assertEquals(emptyList<String>(), result.errors)
        assertEquals(listOf(listOf("ESC", "TAB"), listOf("↑", "↓", "←")), result.rows.map { row -> row.map { it.label } })
    }

    @Test
    fun blankSpecHidesTheBar() {
        assertEquals(ExtraKeysSpec.Result(emptyList(), emptyList()), ExtraKeysSpec.parse(""))
        assertEquals(ExtraKeysSpec.Result(emptyList(), emptyList()), ExtraKeysSpec.parse(" \n\t\n"))
    }

    @Test
    fun unknownTokensAreReportedAndSkipped() {
        val result: ExtraKeysSpec.Result = ExtraKeysSpec.parse("ESC bogus TAB\nls")
        assertEquals(listOf("Unknown key: bogus", "Unknown key: ls"), result.errors)
        assertEquals(listOf(listOf("ESC", "TAB")), result.rows.map { row -> row.map { it.label } })
    }

    @Test
    fun everyListedNameParses() {
        for (name in ExtraKeysSpec.NAMES) {
            val result: ExtraKeysSpec.Result = ExtraKeysSpec.parse(name)
            assertTrue(name, result.errors.isEmpty())
            assertEquals(name, 1, result.rows.single().size)
        }
    }
}

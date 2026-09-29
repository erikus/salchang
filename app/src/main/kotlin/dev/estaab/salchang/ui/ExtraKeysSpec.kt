package dev.estaab.salchang.ui

import android.view.KeyEvent

/**
 * The text form of the extra keys bar, as edited in the "Extra keys" dialog and stored in the app
 * settings: one line per row of keys, tokens separated by blanks. A token is a key name from
 * [ExtraKeysSpec.NAMES] (case-insensitive) or a single character, which is typed literally.
 */
object ExtraKeysSpec {
    /** The Termux-style default row. */
    const val DEFAULT: String = "ESC TAB CTRL ALT LEFT UP DOWN RIGHT HOME END PGUP PGDN - / |"

    /** Rows of the parsed [DEFAULT]. */
    val DEFAULT_ROWS: List<List<ExtraKey>> by lazy { parse(DEFAULT).rows }

    /** The result of [parse]: [rows] to show and one message per token that is not a key. */
    data class Result(val rows: List<List<ExtraKey>>, val errors: List<String>)

    private val named: Map<String, ExtraKey> = buildMap {
        fun code(name: String, label: String, keyCode: Int) = put(name, ExtraKey.Code(label, keyCode))
        put("CTRL", ExtraKey.Modifier("CTRL", ModifierKind.CTRL))
        put("ALT", ExtraKey.Modifier("ALT", ModifierKind.ALT))
        code("ESC", "ESC", KeyEvent.KEYCODE_ESCAPE)
        code("TAB", "TAB", KeyEvent.KEYCODE_TAB)
        code("ENTER", "ENTER", KeyEvent.KEYCODE_ENTER)
        code("BKSP", "BKSP", KeyEvent.KEYCODE_DEL)
        code("DEL", "DEL", KeyEvent.KEYCODE_FORWARD_DEL)
        code("INS", "INS", KeyEvent.KEYCODE_INSERT)
        code("LEFT", "←", KeyEvent.KEYCODE_DPAD_LEFT)
        code("UP", "↑", KeyEvent.KEYCODE_DPAD_UP)
        code("DOWN", "↓", KeyEvent.KEYCODE_DPAD_DOWN)
        code("RIGHT", "→", KeyEvent.KEYCODE_DPAD_RIGHT)
        code("HOME", "HOME", KeyEvent.KEYCODE_MOVE_HOME)
        code("END", "END", KeyEvent.KEYCODE_MOVE_END)
        code("PGUP", "PGUP", KeyEvent.KEYCODE_PAGE_UP)
        code("PGDN", "PGDN", KeyEvent.KEYCODE_PAGE_DOWN)
        for (n in 1..12) code("F$n", "F$n", KeyEvent.KEYCODE_F1 + (n - 1))
        put("SPACE", ExtraKey.Char("SPACE", ' '.code))
    }

    /** Alternative spellings accepted for names in [named]. */
    private val aliases: Map<String, String> = mapOf(
        "ESCAPE" to "ESC",
        "CONTROL" to "CTRL",
        "RETURN" to "ENTER",
        "BACKSPACE" to "BKSP",
        "DELETE" to "DEL",
        "INSERT" to "INS",
        "PAGEUP" to "PGUP",
        "PAGEDOWN" to "PGDN",
        "PGDOWN" to "PGDN",
        "←" to "LEFT",
        "↑" to "UP",
        "↓" to "DOWN",
        "→" to "RIGHT",
    )

    /** Key names in the order they are listed in the dialog's help text. */
    val NAMES: List<String> = named.keys.toList()

    /** Parses [text]; blank lines are skipped, so an all-blank spec hides the bar. */
    fun parse(text: String): Result {
        val rows = mutableListOf<List<ExtraKey>>()
        val errors = mutableListOf<String>()
        for (line in text.lines()) {
            val tokens: List<String> = line.trim().split(BLANKS).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            val row = mutableListOf<ExtraKey>()
            for (token in tokens) {
                val key: ExtraKey? = keyFor(token)
                if (key == null) errors += "Unknown key: $token" else row += key
            }
            if (row.isNotEmpty()) rows += row
        }
        return Result(rows, errors)
    }

    private fun keyFor(token: String): ExtraKey? {
        val upper: String = token.uppercase()
        named[aliases[upper] ?: upper]?.let { return it }
        // A single character (one code point) types itself; a longer unknown token is an error.
        if (token.codePointCount(0, token.length) == 1) return ExtraKey.Char(token, token.codePointAt(0))
        return null
    }

    private val BLANKS: Regex = Regex("[ \\t]+")
}

package dev.estaab.salchang.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BAR_HEIGHT = 40.dp
private val KEY_SPACING = 4.dp
private val KEY_LABEL_SIZE = 12.sp

/** One button on the extra keys bar. */
sealed interface ExtraKey {
    val label: String

    /** Sends a key code through `TerminalView.handleKeyCode`, honouring the CTRL/ALT toggles. */
    data class Code(override val label: String, val keyCode: Int) : ExtraKey

    /** Sends a literal character through `TerminalView.inputCodePoint`, honouring the toggles. */
    data class Char(override val label: String, val codePoint: Int) : ExtraKey

    /** Sticky modifier applied to the next key. */
    data class Modifier(override val label: String, val kind: ModifierKind) : ExtraKey
}

enum class ModifierKind { CTRL, ALT }

/** Termux-style default row, left to right. */
val DEFAULT_EXTRA_KEYS: List<ExtraKey> = listOf(
    ExtraKey.Code("ESC", KeyEvent.KEYCODE_ESCAPE),
    ExtraKey.Code("TAB", KeyEvent.KEYCODE_TAB),
    ExtraKey.Modifier("CTRL", ModifierKind.CTRL),
    ExtraKey.Modifier("ALT", ModifierKind.ALT),
    ExtraKey.Code("←", KeyEvent.KEYCODE_DPAD_LEFT),
    ExtraKey.Code("↑", KeyEvent.KEYCODE_DPAD_UP),
    ExtraKey.Code("↓", KeyEvent.KEYCODE_DPAD_DOWN),
    ExtraKey.Code("→", KeyEvent.KEYCODE_DPAD_RIGHT),
    ExtraKey.Code("HOME", KeyEvent.KEYCODE_MOVE_HOME),
    ExtraKey.Code("END", KeyEvent.KEYCODE_MOVE_END),
    ExtraKey.Code("PGUP", KeyEvent.KEYCODE_PAGE_UP),
    ExtraKey.Code("PGDN", KeyEvent.KEYCODE_PAGE_DOWN),
    ExtraKey.Char("-", '-'.code),
    ExtraKey.Char("/", '/'.code),
    ExtraKey.Char("|", '|'.code),
)

@Composable
fun ExtraKeysBar(
    keys: List<ExtraKey>,
    ctrlActive: Boolean,
    altActive: Boolean,
    onKey: (ExtraKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = KEY_SPACING),
    ) {
        keys.forEach { key ->
            val selected: Boolean = when (key) {
                is ExtraKey.Modifier -> if (key.kind == ModifierKind.CTRL) ctrlActive else altActive
                else -> false
            }
            FilterChip(
                selected = selected,
                onClick = { onKey(key) },
                label = { Text(key.label, fontFamily = FontFamily.Monospace, fontSize = KEY_LABEL_SIZE) },
                modifier = Modifier.padding(horizontal = KEY_SPACING / 2),
            )
        }
    }
}

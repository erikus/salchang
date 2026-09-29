package dev.estaab.salchang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
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

    /** Sends a key code through `TerminalView.handleKeyCode`, honouring the CTRL/ALT/SHIFT toggles. */
    data class Code(override val label: String, val keyCode: Int) : ExtraKey

    /** Sends a literal character through `TerminalView.inputCodePoint`, honouring the toggles. */
    data class Char(override val label: String, val codePoint: Int) : ExtraKey

    /** Sticky modifier applied to the next key. */
    data class Modifier(override val label: String, val kind: ModifierKind) : ExtraKey
}

enum class ModifierKind { CTRL, ALT, SHIFT }

/**
 * The rows of on-screen keys under the terminal, from [ExtraKeysSpec.parse]. Each row scrolls
 * horizontally on its own; no rows means no bar.
 */
@Composable
fun ExtraKeysBar(
    rows: List<List<ExtraKey>>,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    onKey: (ExtraKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
        rows.forEach { keys ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(BAR_HEIGHT)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = KEY_SPACING),
            ) {
                keys.forEach { key ->
                    val selected: Boolean = when (key) {
                        is ExtraKey.Modifier -> when (key.kind) {
                            ModifierKind.CTRL -> ctrlActive
                            ModifierKind.ALT -> altActive
                            ModifierKind.SHIFT -> shiftActive
                        }
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
    }
}

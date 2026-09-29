package dev.estaab.salchang.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

private val DIALOG_SPACING = 8.dp
private const val SPEC_MIN_LINES: Int = 2

/**
 * Edits the extra keys bar spec ([ExtraKeysSpec]). [spec] is the stored text, or null for the
 * default; [onSave] gets the new text, or null when it equals the default so the stored value is
 * cleared. Saving is disabled while the text has tokens that are not keys.
 */
@Composable
fun ExtraKeysDialog(spec: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text: String by rememberSaveable { mutableStateOf(spec ?: ExtraKeysSpec.DEFAULT) }
    val parsed: ExtraKeysSpec.Result = remember(text) { ExtraKeysSpec.parse(text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Extra keys") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DIALOG_SPACING)) {
                Text(
                    "One line per row of keys, separated by spaces. A single character types itself; " +
                        "an empty spec hides the bar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MonoTextStyle,
                    minLines = SPEC_MIN_LINES,
                    isError = parsed.errors.isNotEmpty(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (parsed.errors.isNotEmpty()) {
                    Text(
                        parsed.errors.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Keys: " + ExtraKeysSpec.NAMES.joinToString(" "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExtraKeysBar(rows = parsed.rows, ctrlActive = false, altActive = false, shiftActive = false, onKey = {})
                TextButton(onClick = { text = ExtraKeysSpec.DEFAULT }, enabled = text != ExtraKeysSpec.DEFAULT) {
                    Text("Reset to default")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text.takeUnless { it == ExtraKeysSpec.DEFAULT }) },
                enabled = parsed.errors.isEmpty(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

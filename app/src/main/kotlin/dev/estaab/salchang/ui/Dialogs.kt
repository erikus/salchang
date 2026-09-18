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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private val DIALOG_SPACING = 8.dp

/** Asks for a passphrase; the value is handed over as a [CharArray] the receiver must wipe. */
@Composable
fun PassphraseDialog(
    title: String,
    message: String?,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var value: String by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DIALOG_SPACING)) {
                if (message != null) Text(message)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.toCharArray()) }, enabled = value.isNotEmpty()) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Trust-on-first-use prompt for an unknown SSH host key. */
@Composable
fun HostKeyDialog(
    hostname: String,
    keyType: String,
    fingerprintSha256: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Unknown host key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DIALOG_SPACING)) {
                Text("The authenticity of $hostname can't be established.")
                Text("Key type", style = MaterialTheme.typography.labelMedium)
                Text(keyType, style = MonoTextStyle)
                Text("Fingerprint", style = MaterialTheme.typography.labelMedium)
                Text(fingerprintSha256, style = MonoTextStyle)
                Text(
                    "Compare with `ssh-keygen -lf /etc/ssh/ssh_host_*_key.pub` on the host. Accepting stores it in the app's known_hosts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Accept") } },
        dismissButton = { TextButton(onClick = onReject) { Text("Reject") } },
    )
}

package com.phonecapture.tracker.capture.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.phonecapture.tracker.capture.cloud.CloudUser
import com.phonecapture.tracker.capture.cloud.KeyringSync
import com.phonecapture.tracker.capture.security.VaultState
import com.phonecapture.tracker.capture.ui.theme.CaptureColors
import com.phonecapture.tracker.capture.ui.theme.NumberFont

/** E-mail / password sign-in for Firebase Authentication. */
@Composable
fun AccountSection(
    user: CloudUser?,
    keyringState: KeyringSync?,
    busy: Boolean,
    onSignIn: (String, String) -> Unit,
    onRegister: (String, String) -> Unit,
    onResetPassword: (String) -> Unit,
    onSignOut: () -> Unit,
    onCheckKeyring: () -> Unit,
) {
    if (user != null) {
        Text("Signed in as ${user.email ?: "this account"}", style = MaterialTheme.typography.bodyLarge)
        val keyringText = when (keyringState) {
            KeyringSync.InSync -> "Recovery keyring: in sync"
            KeyringSync.NeedsRecoveryCode -> "Recovery keyring: enter this account's recovery code below (uploads are paused)"
            is KeyringSync.Error -> "Recovery keyring: ${keyringState.message}"
            null -> "Recovery keyring: not checked yet"
        }
        Text(
            keyringText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (keyringState == KeyringSync.InSync) CaptureColors.Success else CaptureColors.Pending,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheckKeyring, enabled = !busy) { Text("Check keyring") }
            OutlinedButton(onClick = onSignOut, enabled = !busy) { Text("Sign out") }
        }
        return
    }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Text(
        "Sign in to back up encrypted captures. Images are encrypted on this phone before upload; " +
            "the cloud only ever stores encrypted files.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("E-mail") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    val ready = email.isNotBlank() && password.length >= 6 && !busy
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSignIn(email, password) }, enabled = ready) { Text("Sign in") }
        OutlinedButton(onClick = { onRegister(email, password) }, enabled = ready) { Text("Create account") }
    }
    TextButton(onClick = { onResetPassword(email) }, enabled = email.isNotBlank() && !busy) { Text("Forgot password?") }
}

/** Encryption key status plus create / restore / regenerate actions. */
@Composable
fun EncryptionSection(
    vault: VaultState,
    signedIn: Boolean,
    busy: Boolean,
    onCreate: () -> Unit,
    onRegenerate: () -> Unit,
    onRestoreFromCloud: (String) -> Unit,
    onUnlockLocal: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    when (vault) {
        VaultState.NotSetUp -> {
            Text("No encryption key on this phone yet. Captures cannot start until one exists.")
            Button(onClick = onCreate, enabled = !busy) { Text("Create encryption key") }
        }
        is VaultState.Ready -> {
            Text(
                "Encryption ready: AES-256-GCM, key protected by the Android Keystore (${vault.keyCount} key(s)).",
                color = CaptureColors.Success,
            )
            OutlinedButton(onClick = onRegenerate, enabled = !busy) { Text("Create a new recovery code") }
        }
        is VaultState.Locked -> {
            Text(vault.reason + " Enter your recovery code to unlock it.", color = CaptureColors.Failure)
        }
    }
    if (vault is VaultState.Locked || signedIn) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Recovery code (XXXX-XXXX-…)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vault is VaultState.Locked) {
                Button(onClick = { onUnlockLocal(code) }, enabled = code.isNotBlank() && !busy) { Text("Unlock") }
            }
            if (signedIn) {
                OutlinedButton(onClick = { onRestoreFromCloud(code) }, enabled = code.isNotBlank() && !busy) {
                    Text("Restore from cloud")
                }
            }
        }
        Text(
            "Use “Restore from cloud” on a new phone, or when this account already holds another phone's keys.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Shows a new recovery code exactly once. The window is FLAG_SECURE, so neither Android
 * screenshots nor this app's own screen capture can record it.
 */
@Composable
fun RecoveryCodeDialog(code: String, onDone: () -> Unit) {
    var saved by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { /* must be acknowledged */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Your recovery code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Write this code down and keep it somewhere safe. It is the ONLY way to decrypt your cloud " +
                        "captures on a new phone. It is not stored anywhere and will not be shown again.",
                )
                Text(code, fontFamily = NumberFont, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = saved, onCheckedChange = { saved = it })
                    Text("I have written down my recovery code")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone, enabled = saved) { Text("Done") } },
    )
}

@Composable
fun PasswordConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) {
                Text(confirmLabel, color = CaptureColors.Failure)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = CaptureColors.Failure) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

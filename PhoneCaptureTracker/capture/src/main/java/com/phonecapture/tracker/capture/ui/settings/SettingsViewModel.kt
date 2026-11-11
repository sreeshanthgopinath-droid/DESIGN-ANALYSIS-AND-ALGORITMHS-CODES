package com.phonecapture.tracker.capture.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonecapture.tracker.capture.CaptureGraph
import com.phonecapture.tracker.capture.cloud.AuthResult
import com.phonecapture.tracker.capture.cloud.CloudUser
import com.phonecapture.tracker.capture.cloud.KeyringSync
import com.phonecapture.tracker.capture.data.settings.CaptureSettings
import com.phonecapture.tracker.capture.security.VaultState
import com.phonecapture.tracker.core.crypto.WrongRecoveryCodeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val graph: CaptureGraph) : ViewModel() {
    val settings: StateFlow<CaptureSettings> = graph.settings.settings
    val vault: StateFlow<VaultState> = graph.vault.state
    val cloudConfigured: Boolean = graph.firebase.isConfigured
    val cloudUser: StateFlow<CloudUser?> = graph.firebase.user
    val keyringState: StateFlow<KeyringSync?> = graph.dataManager.keyringState
    val localBytes: StateFlow<Long> =
        graph.repository.observeLocalBytes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** A freshly generated recovery code, shown once in a secure dialog and then forgotten. */
    private val _recoveryCode = MutableStateFlow<String?>(null)
    val recoveryCode: StateFlow<String?> = _recoveryCode.asStateFlow()

    fun update(transform: (CaptureSettings) -> CaptureSettings) {
        val before = graph.settings.current
        val after = graph.settings.update(transform)
        viewModelScope.launch { applySideEffects(before, after) }
    }

    private suspend fun applySideEffects(before: CaptureSettings, after: CaptureSettings) {
        val dao = graph.repository.dao
        if (after.cloudSyncEnabled && !before.cloudSyncEnabled) {
            val queued = if (graph.isCloudReady()) dao.queueLocalOnlyCaptures() else 0
            graph.uploads.syncPeriodicWork()
            graph.uploads.enqueueNow()
            _message.value = if (queued > 0) "Cloud sync on. $queued earlier capture(s) queued." else "Cloud sync on."
        }
        if (!after.cloudSyncEnabled && before.cloudSyncEnabled) {
            dao.unqueuePendingUploads()
            graph.uploads.cancelAll()
            _message.value = "Cloud sync off. New captures stay on this phone only."
        }
        if (after.allowMobileDataUpload != before.allowMobileDataUpload && after.cloudSyncEnabled) {
            graph.uploads.syncPeriodicWork()
            graph.uploads.enqueueNow(replace = true)
        }
        if (after.captureFront != before.captureFront || after.captureRear != before.captureRear ||
            after.captureScreen != before.captureScreen
        ) {
            graph.controller.refreshNotification()
        }
        if (after.localRetentionDays != before.localRetentionDays || after.cloudRetentionDays != before.cloudRetentionDays) {
            _message.value = "Retention saved. Clean-up runs daily; you can also run it now."
        }
    }

    fun applyRetentionNow() = runBusy {
        val affected = graph.dataManager.applyRetention()
        "Retention clean-up finished: $affected capture(s) affected."
    }

    // ---------- encryption & recovery ----------

    fun setUpEncryption() = runBusy {
        val code = withContext(Dispatchers.Default) { graph.vault.setUpNewVault() }
        _recoveryCode.value = code
        if (graph.isCloudReady()) graph.dataManager.syncKeyring()
        "Encryption key created."
    }

    fun regenerateRecoveryCode() = runBusy {
        val (code, keyring) = withContext(Dispatchers.Default) { graph.vault.regenerateRecoveryCode() }
        _recoveryCode.value = code
        if (graph.firebase.currentUid() != null) graph.dataManager.uploadKeyring(keyring)
        "New recovery code created. The old code no longer works."
    }

    fun dismissRecoveryCode() {
        _recoveryCode.value = null
    }

    /** New phone, or "this account already has a keyring": unlock the cloud keyring with the code. */
    fun restoreFromCloud(code: String) = runBusy {
        val keyring = graph.dataManager.fetchCloudKeyring()
            ?: return@runBusy "This cloud account has no recovery keyring yet."
        try {
            val merged = withContext(Dispatchers.Default) { graph.vault.restore(keyring, code) }
            graph.dataManager.uploadKeyring(merged)
            graph.uploads.enqueueNow()
            "Keys restored. Earlier cloud captures can be decrypted on this phone."
        } catch (e: WrongRecoveryCodeException) {
            "That recovery code is not correct for this account."
        }
    }

    /** The phone's Keystore key was lost but the local keyring file survived. */
    fun restoreFromLocalKeyring(code: String) = runBusy {
        val keyring = graph.vault.localKeyring() ?: return@runBusy "No local recovery keyring was found."
        try {
            withContext(Dispatchers.Default) { graph.vault.restore(keyring, code) }
            "Encryption key unlocked."
        } catch (e: WrongRecoveryCodeException) {
            "That recovery code is not correct."
        }
    }

    // ---------- account ----------

    fun signIn(email: String, password: String) = runBusy { authMessage(graph.firebase.signIn(email, password), "Signed in.") }
    fun register(email: String, password: String) = runBusy { authMessage(graph.firebase.register(email, password), "Account created and signed in.") }
    fun resetPassword(email: String) = runBusy { authMessage(graph.firebase.sendPasswordReset(email), "Password-reset e-mail sent.") }

    fun signOut() {
        graph.firebase.signOut()
        _message.value = "Signed out. Uploads wait until you sign in again."
    }

    fun syncKeyringNow() = runBusy {
        when (val r = graph.dataManager.syncKeyring()) {
            KeyringSync.InSync -> "Recovery keyring is in sync."
            KeyringSync.NeedsRecoveryCode -> "This account already has a recovery keyring. Enter its recovery code below."
            is KeyringSync.Error -> "Keyring sync failed: ${r.message}"
        }
    }

    // ---------- deletion ----------

    fun deleteAllLocalData() = runBusy {
        graph.thumbnails.clear()
        graph.dataManager.deleteAllLocalData()
        "All captures, records and logs on this phone were deleted."
    }

    fun deleteAllCloudData() = runBusy {
        graph.dataManager.deleteAllCloudData().fold(
            onSuccess = { "Deleted $it file(s) from the cloud. Cloud sync is now off." },
            onFailure = { "Cloud deletion failed: ${it.message}" },
        )
    }

    fun deleteAccount(password: String) = runBusy {
        graph.dataManager.deleteAccount(password).fold(
            onSuccess = { "Your cloud data and account were deleted." },
            onFailure = { "Account deletion failed: ${it.message}" },
        )
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun authMessage(result: AuthResult, success: String): String = when (result) {
        is AuthResult.Success -> success
        is AuthResult.Failure -> result.message
    }

    private fun runBusy(block: suspend () -> String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                _message.value = block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message ?: e.javaClass.simpleName
            } finally {
                _busy.value = false
            }
        }
    }
}

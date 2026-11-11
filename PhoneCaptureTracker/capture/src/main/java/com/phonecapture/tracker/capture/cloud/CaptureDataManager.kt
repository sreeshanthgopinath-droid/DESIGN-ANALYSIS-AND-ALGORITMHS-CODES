package com.phonecapture.tracker.capture.cloud

import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageReference
import com.phonecapture.tracker.capture.data.CaptureRepository
import com.phonecapture.tracker.capture.data.db.CaptureRecordEntity
import com.phonecapture.tracker.capture.data.db.DiagnosticLogDao
import com.phonecapture.tracker.capture.data.hasCloudCopy
import com.phonecapture.tracker.capture.data.hasLocalImage
import com.phonecapture.tracker.capture.data.settings.SettingsRepository
import com.phonecapture.tracker.capture.data.toRetentionItem
import com.phonecapture.tracker.capture.diagnostics.DiagnosticLogger
import com.phonecapture.tracker.capture.security.EncryptedFileStore
import com.phonecapture.tracker.capture.security.VaultManager
import com.phonecapture.tracker.core.cloud.CloudPaths
import com.phonecapture.tracker.core.crypto.RecoveryKeyring
import com.phonecapture.tracker.core.model.DiagnosticType
import com.phonecapture.tracker.core.model.UploadStatus
import com.phonecapture.tracker.core.model.VerificationStatus
import com.phonecapture.tracker.core.policy.RetentionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

sealed interface KeyringSync {
    /** The cloud keyring can recover every key used on this phone. */
    data object InSync : KeyringSync

    /** The account already has a different keyring: the user must enter that account's recovery code. */
    data object NeedsRecoveryCode : KeyringSync

    data class Error(val message: String) : KeyringSync
}

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    /** Removed from the phone; the cloud copy will be removed when online. */
    data object CloudDeletionPending : DeleteResult
    data class Failed(val message: String) : DeleteResult
}

/**
 * Deletion, retention, recovery-keyring sync and account removal. Cloud operations only ever touch
 * paths inside the signed-in user's own folder (checked with [CloudPaths.belongsTo]).
 */
class CaptureDataManager(
    private val repository: CaptureRepository,
    private val fileStore: EncryptedFileStore,
    private val firebase: FirebaseGateway,
    private val vault: VaultManager,
    private val settings: SettingsRepository,
    private val diagnostics: DiagnosticLogger,
    private val diagnosticDao: DiagnosticLogDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = repository.dao

    private val _keyringState = MutableStateFlow<KeyringSync?>(null)
    val keyringState: StateFlow<KeyringSync?> = _keyringState.asStateFlow()

    // ---------- single captures ----------

    suspend fun deleteCapture(record: CaptureRecordEntity, alsoFromCloud: Boolean): DeleteResult {
        fileStore.delete(record.localPath)
        fileStore.delete(record.thumbnailPath)
        val cloudCopy = record.hasCloudCopy
        if (!cloudCopy) {
            dao.delete(record.captureId)
            diagnostics.log(DiagnosticType.DATA_DELETED, "A ${record.category.displayName.lowercase()} capture was deleted", captureId = record.captureId)
            return DeleteResult.Deleted
        }
        val localGone = record.copy(localImageDeleted = true, thumbnailPath = null)
        if (!alsoFromCloud) {
            dao.update(localGone)
            return DeleteResult.Deleted
        }
        return if (deleteCloudCopy(record)) {
            dao.delete(record.captureId)
            diagnostics.log(DiagnosticType.DATA_DELETED, "A capture was deleted from the phone and the cloud", captureId = record.captureId)
            DeleteResult.Deleted
        } else {
            dao.update(localGone.copy(cloudDeletePending = true))
            DeleteResult.CloudDeletionPending
        }
    }

    /** Full-size image: local file if present, otherwise downloaded (still encrypted) and decrypted here. */
    suspend fun loadFullImage(record: CaptureRecordEntity): ByteArray {
        val local = record.localPath
        if (record.hasLocalImage && local != null && fileStore.exists(local)) {
            return fileStore.readImage(local, record.captureId)
        }
        val path = record.cloudPath ?: error("No copy of this image is available")
        val storage = firebase.storage ?: error(FirebaseGateway.NOT_CONFIGURED)
        val envelope = withTimeout(60_000) { storage.reference.child(path).getBytes(MAX_DOWNLOAD_BYTES).await() }
        return fileStore.decryptImageEnvelope(envelope, record.captureId)
    }

    suspend fun deleteLocalImageAfterVerifiedUpload(record: CaptureRecordEntity) {
        if (record.uploadStatus != UploadStatus.UPLOADED || record.verificationStatus != VerificationStatus.VERIFIED) return
        fileStore.delete(record.localPath)
        dao.update(record.copy(localImageDeleted = true))
    }

    // ---------- retention ----------

    /** @return number of captures affected. */
    suspend fun applyRetention(): Int {
        val candidates = dao.retentionCandidates()
        val plan = RetentionPolicy.plan(clock(), candidates.map { it.toRetentionItem() }, settings.current.toRetentionSettings())
        if (plan.isEmpty) return 0
        for (id in plan.deleteLocalImageOnly) {
            val r = dao.get(id) ?: continue
            fileStore.delete(r.localPath)
            dao.update(r.copy(localImageDeleted = true))
        }
        for (id in plan.deleteLocalEverything) {
            val r = dao.get(id) ?: continue
            fileStore.delete(r.localPath)
            fileStore.delete(r.thumbnailPath)
            dao.update(r.copy(localImageDeleted = true, thumbnailPath = null))
        }
        for (id in plan.deleteCloud) {
            val r = dao.get(id) ?: continue
            if (deleteCloudCopy(r)) {
                dao.update(r.copy(cloudDeleted = true, cloudDeletePending = false))
            } else {
                dao.update(r.copy(cloudDeletePending = true))
            }
        }
        val affected = (plan.deleteLocalImageOnly + plan.deleteLocalEverything + plan.deleteCloud).size
        diagnostics.log(DiagnosticType.DATA_DELETED, "Retention clean-up removed data for $affected capture(s)")
        return affected
    }

    suspend fun processPendingCloudDeletes() {
        for (r in dao.pendingCloudDeletes()) {
            if (!deleteCloudCopy(r)) return // still offline; try again later
            if (r.hasLocalImage || r.thumbnailPath != null) {
                dao.update(r.copy(cloudDeleted = true, cloudDeletePending = false))
            } else {
                dao.delete(r.captureId)
            }
        }
    }

    // ---------- bulk deletion ----------

    /** Deletes all images, records and logs on this phone. Keys are kept so cloud copies stay readable. */
    suspend fun deleteAllLocalData() {
        fileStore.deleteAll()
        dao.deleteAll()
        diagnosticDao.deleteAll()
        diagnostics.log(DiagnosticType.DATA_DELETED, "All local captures, records and logs were deleted")
    }

    /** Deletes every object and document of the signed-in user. Turns cloud sync off. */
    suspend fun deleteAllCloudData(): Result<Int> {
        val uid = firebase.currentUid() ?: return Result.failure(IllegalStateException("Not signed in"))
        val storage = firebase.storage ?: return Result.failure(IllegalStateException(FirebaseGateway.NOT_CONFIGURED))
        val firestore = firebase.firestore ?: return Result.failure(IllegalStateException(FirebaseGateway.NOT_CONFIGURED))
        return try {
            settings.update { it.copy(cloudSyncEnabled = false) }
            val objects = deleteFolder(storage.reference.child(CloudPaths.userRoot(uid)))
            val captures = firestore.collection(CloudPaths.capturesCollection(uid))
            while (true) {
                val page = captures.limit(200).get().await()
                if (page.isEmpty) break
                val batch = firestore.batch()
                page.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
            firestore.document(CloudPaths.keyringDocument(uid)).delete().await()
            for (r in dao.withCloudCopies()) {
                if (r.hasLocalImage || r.thumbnailPath != null) {
                    dao.update(
                        r.copy(
                            cloudPath = null,
                            uploadStatus = UploadStatus.NOT_QUEUED,
                            verificationStatus = VerificationStatus.NOT_VERIFIED,
                            cloudDeletePending = false,
                        ),
                    )
                } else {
                    dao.delete(r.captureId)
                }
            }
            dao.unqueuePendingUploads()
            _keyringState.value = null
            diagnostics.log(DiagnosticType.DATA_DELETED, "All cloud data was deleted ($objects files)")
            Result.success(objects)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.log(DiagnosticType.UPLOAD_FAILURE, "Deleting cloud data failed", e.toString())
            Result.failure(e)
        }
    }

    /** Deletes cloud data, then the Firebase account itself. Requires the password (recent sign-in). */
    suspend fun deleteAccount(password: String): Result<Unit> {
        when (val auth = firebase.reauthenticate(password)) {
            is AuthResult.Failure -> return Result.failure(IllegalStateException(auth.message))
            is AuthResult.Success -> Unit
        }
        deleteAllCloudData().onFailure { return Result.failure(it) }
        return try {
            val user = firebase.auth?.currentUser ?: return Result.failure(IllegalStateException("Not signed in"))
            user.delete().await()
            diagnostics.log(DiagnosticType.DATA_DELETED, "The cloud account was deleted")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException(firebase.describe(e)))
        }
    }

    // ---------- recovery keyring ----------

    suspend fun fetchCloudKeyring(): RecoveryKeyring? {
        val uid = firebase.currentUid() ?: return null
        val firestore = firebase.firestore ?: return null
        val snapshot = firestore.document(CloudPaths.keyringDocument(uid)).get().await()
        if (!snapshot.exists()) return null
        val map = snapshot.data?.mapValues { (_, v) -> v.toString() } ?: return null
        return RecoveryKeyring.fromMap(map)
    }

    suspend fun uploadKeyring(keyring: RecoveryKeyring) {
        val uid = firebase.currentUid() ?: return
        val firestore = firebase.firestore ?: return
        firestore.document(CloudPaths.keyringDocument(uid)).set(keyring.toMap()).await()
        _keyringState.value = KeyringSync.InSync
    }

    /**
     * Makes sure the cloud keyring can recover every local key before any image is uploaded.
     *  - no cloud keyring yet → upload ours;
     *  - cloud keyring covers our keys → in sync;
     *  - ours is a newer version of the same keys (recovery code regenerated) → upload ours;
     *  - otherwise the account has another phone's keyring → ask for that recovery code.
     */
    suspend fun syncKeyring(): KeyringSync {
        val result = try {
            val local = vault.localKeyring() ?: return KeyringSync.Error("This phone has no recovery keyring yet")
            val remote = fetchCloudKeyring()
            when {
                remote == null -> {
                    uploadKeyring(local)
                    KeyringSync.InSync
                }
                remote.keyIds.containsAll(local.keyIds) && remote.createdAtUtcMillis >= local.createdAtUtcMillis -> KeyringSync.InSync
                local.keyIds.containsAll(remote.keyIds) && local.createdAtUtcMillis > remote.createdAtUtcMillis -> {
                    uploadKeyring(local)
                    KeyringSync.InSync
                }
                else -> KeyringSync.NeedsRecoveryCode
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KeyringSync.Error(e.message ?: e.javaClass.simpleName)
        }
        _keyringState.value = result
        return result
    }

    // ---------- helpers ----------

    private suspend fun deleteCloudCopy(record: CaptureRecordEntity): Boolean {
        val path = record.cloudPath ?: return true
        val uid = firebase.currentUid() ?: return false
        if (!CloudPaths.belongsTo(uid, path)) return false
        val storage = firebase.storage ?: return false
        val firestore = firebase.firestore ?: return false
        return try {
            withTimeout(30_000) {
                try {
                    storage.reference.child(path).delete().await()
                } catch (e: StorageException) {
                    if (e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw e
                }
                firestore.document(CloudPaths.captureDocument(uid, record.captureId)).delete().await()
            }
            true
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) false else throw e
        } catch (e: Exception) {
            diagnostics.log(DiagnosticType.UPLOAD_FAILURE, "Cloud deletion will be retried", e.toString(), record.captureId)
            false
        }
    }

    private suspend fun deleteFolder(ref: StorageReference): Int {
        val listing = ref.listAll().await()
        var count = 0
        for (item in listing.items) {
            item.delete().await()
            count++
        }
        for (prefix in listing.prefixes) count += deleteFolder(prefix)
        return count
    }

    private companion object {
        const val MAX_DOWNLOAD_BYTES = 40L * 1024 * 1024
    }
}

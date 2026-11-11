package com.phonecapture.tracker.capture.cloud

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.phonecapture.tracker.capture.data.db.CaptureRecordEntity
import com.phonecapture.tracker.capture.security.EncryptedFileStore
import com.phonecapture.tracker.core.cloud.CloudPaths
import com.phonecapture.tracker.core.crypto.EncryptedEnvelope
import com.phonecapture.tracker.core.crypto.Hashing
import com.phonecapture.tracker.core.policy.UploadErrorKind
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

sealed interface UploadOutcome {
    data class Uploaded(val cloudPath: String) : UploadOutcome
    data class Failed(val kind: UploadErrorKind, val detail: String) : UploadOutcome
}

/**
 * Uploads one encrypted capture and proves it arrived intact:
 *  1. reads the local *encrypted* file and refuses anything that is not an encrypted envelope;
 *  2. uploads it to the user's private folder with putBytes (progress reported);
 *  3. reads back the object metadata and compares size and MD5 with the local bytes;
 *  4. writes the metadata document to Firestore.
 * Only when all four steps succeed is the capture marked UPLOADED + VERIFIED.
 */
class CloudUploader(
    private val firebase: FirebaseGateway,
    private val fileStore: EncryptedFileStore,
) {
    suspend fun upload(record: CaptureRecordEntity, onProgress: (Int) -> Unit): UploadOutcome {
        val uid = firebase.currentUid() ?: return UploadOutcome.Failed(UploadErrorKind.NOT_SIGNED_IN, "Not signed in")
        val storage = firebase.storage ?: return UploadOutcome.Failed(UploadErrorKind.NOT_SIGNED_IN, FirebaseGateway.NOT_CONFIGURED)
        val firestore = firebase.firestore ?: return UploadOutcome.Failed(UploadErrorKind.NOT_SIGNED_IN, FirebaseGateway.NOT_CONFIGURED)
        val localPath = record.localPath
        if (localPath == null || record.localImageDeleted || !fileStore.exists(localPath)) {
            return UploadOutcome.Failed(UploadErrorKind.LOCAL_FILE_MISSING, "The encrypted file is no longer on the phone")
        }
        val bytes = try {
            fileStore.readEnvelope(localPath)
        } catch (e: IllegalStateException) {
            return UploadOutcome.Failed(UploadErrorKind.NOT_ENCRYPTED, e.message ?: "Not encrypted")
        } catch (e: Exception) {
            return UploadOutcome.Failed(UploadErrorKind.LOCAL_FILE_MISSING, e.toString())
        }
        check(EncryptedEnvelope.isEnvelope(bytes)) // defence in depth: never upload plaintext

        val objectPath = CloudPaths.objectPath(uid, record.category, record.capturedAtUtc, record.captureId)
        val ref = storage.reference.child(objectPath)
        val metadata = StorageMetadata.Builder()
            .setContentType(CONTENT_TYPE)
            .setCustomMetadata("captureId", record.captureId)
            .setCustomMetadata("category", record.category.name)
            .setCustomMetadata("capturedAtUtc", record.capturedAtUtc.toString())
            .setCustomMetadata("encryption", ENCRYPTION_LABEL)
            .build()

        return try {
            withTimeout<UploadOutcome>(UPLOAD_TIMEOUT_MILLIS) {
                val task = ref.putBytes(bytes, metadata)
                task.addOnProgressListener { snapshot ->
                    val total = snapshot.totalByteCount
                    if (total > 0) onProgress((snapshot.bytesTransferred * 100 / total).toInt())
                }
                task.await()

                val remote = ref.metadata.await()
                val sizeMatches = remote.sizeBytes == bytes.size.toLong()
                val md5Matches = remote.md5Hash == Hashing.md5Base64(bytes)
                if (!sizeMatches || !md5Matches) {
                    runCatching { ref.delete().await() }
                    return@withTimeout UploadOutcome.Failed(
                        UploadErrorKind.VERIFICATION_MISMATCH,
                        "Remote size ${remote.sizeBytes} / local ${bytes.size}, MD5 match: $md5Matches",
                    )
                }

                firestore.document(CloudPaths.captureDocument(uid, record.captureId))
                    .set(metadataDocument(record, objectPath, bytes))
                    .await()
                UploadOutcome.Uploaded(objectPath)
            }
        } catch (e: TimeoutCancellationException) {
            UploadOutcome.Failed(UploadErrorKind.TIMEOUT, "Upload did not finish within ${UPLOAD_TIMEOUT_MILLIS / 1000}s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UploadOutcome.Failed(classify(e), e.toString().take(300))
        }
    }

    /** Metadata only. No image content, no location, no device identifiers. */
    private fun metadataDocument(record: CaptureRecordEntity, objectPath: String, bytes: ByteArray): Map<String, Any?> = mapOf(
        "captureId" to record.captureId,
        "category" to record.category.name,
        "capturedAtUtc" to record.capturedAtUtc,
        "slotStartUtc" to record.slotStartUtc,
        "storagePath" to objectPath,
        "sizeBytes" to bytes.size.toLong(),
        "ciphertextSha256" to Hashing.sha256Hex(bytes),
        "keyId" to EncryptedEnvelope.keyIdOf(bytes),
        "encryption" to ENCRYPTION_LABEL,
        "widthPx" to record.widthPx,
        "heightPx" to record.heightPx,
        "uploadedAt" to FieldValue.serverTimestamp(),
    )

    companion object {
        const val CONTENT_TYPE = "application/octet-stream"
        const val ENCRYPTION_LABEL = "AES-256-GCM/PCTE1"
        private const val UPLOAD_TIMEOUT_MILLIS = 5 * 60_000L

        fun classify(e: Exception): UploadErrorKind = when (e) {
            is StorageException -> when (e.errorCode) {
                StorageException.ERROR_QUOTA_EXCEEDED -> UploadErrorKind.QUOTA_EXCEEDED
                StorageException.ERROR_NOT_AUTHENTICATED -> UploadErrorKind.NOT_SIGNED_IN
                StorageException.ERROR_NOT_AUTHORIZED -> UploadErrorKind.PERMISSION_DENIED
                StorageException.ERROR_RETRY_LIMIT_EXCEEDED -> UploadErrorKind.NETWORK
                StorageException.ERROR_INVALID_CHECKSUM -> UploadErrorKind.VERIFICATION_MISMATCH
                else -> if (e.cause is IOException) UploadErrorKind.NETWORK else UploadErrorKind.SERVER
            }
            is FirebaseFirestoreException -> when (e.code) {
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                -> UploadErrorKind.NETWORK
                FirebaseFirestoreException.Code.PERMISSION_DENIED -> UploadErrorKind.PERMISSION_DENIED
                FirebaseFirestoreException.Code.UNAUTHENTICATED -> UploadErrorKind.NOT_SIGNED_IN
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> UploadErrorKind.QUOTA_EXCEEDED
                else -> UploadErrorKind.SERVER
            }
            is FirebaseNetworkException, is IOException -> UploadErrorKind.NETWORK
            else -> UploadErrorKind.UNKNOWN
        }
    }
}

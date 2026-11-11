package com.phonecapture.tracker.core

import com.phonecapture.tracker.core.cloud.CloudPaths
import com.phonecapture.tracker.core.model.CaptureCategory
import com.phonecapture.tracker.core.model.UploadStatus
import com.phonecapture.tracker.core.model.VerificationStatus
import com.phonecapture.tracker.core.policy.CloudQuotaState
import com.phonecapture.tracker.core.policy.LocalStorageState
import com.phonecapture.tracker.core.policy.NetworkKind
import com.phonecapture.tracker.core.policy.NetworkRequirement
import com.phonecapture.tracker.core.policy.RetentionItem
import com.phonecapture.tracker.core.policy.RetentionPolicy
import com.phonecapture.tracker.core.policy.RetentionSettings
import com.phonecapture.tracker.core.policy.StoragePolicy
import com.phonecapture.tracker.core.policy.UploadDecision
import com.phonecapture.tracker.core.policy.UploadErrorKind
import com.phonecapture.tracker.core.policy.UploadPolicy
import com.phonecapture.tracker.core.policy.UploadRetryPolicy
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PolicyTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()

    private fun item(
        id: String,
        ageDays: Long,
        upload: UploadStatus = UploadStatus.NOT_QUEUED,
        verified: VerificationStatus = VerificationStatus.NOT_VERIFIED,
        cloud: Boolean = false,
        local: Boolean = true,
    ) = RetentionItem(id, now - ageDays * day, local, local, upload, verified, cloud)

    @Test
    fun keepForeverDeletesNothing() {
        val plan = RetentionPolicy.plan(now, listOf(item("a", 900)), RetentionSettings(0, 0, false, false))
        assertTrue(plan.isEmpty)
    }

    @Test
    fun localOnlyModeDeletesExpiredLocalFiles() {
        val plan = RetentionPolicy.plan(
            now,
            listOf(item("old", 31), item("new", 5)),
            RetentionSettings(localRetentionDays = 30, cloudRetentionDays = 0, deleteLocalAfterVerifiedUpload = false, cloudSyncEnabled = false),
        )
        assertEquals(setOf("old"), plan.deleteLocalEverything)
    }

    @Test
    fun neverDeletesOnlyCopyWhileCloudSyncIsOn() {
        val items = listOf(
            item("pending", 40, UploadStatus.UPLOAD_PENDING),
            item("failed", 40, UploadStatus.UPLOAD_FAILED),
            item("mismatch", 40, UploadStatus.UPLOADED, VerificationStatus.MISMATCH, cloud = true),
            item("safe", 40, UploadStatus.UPLOADED, VerificationStatus.VERIFIED, cloud = true),
        )
        val plan = RetentionPolicy.plan(now, items, RetentionSettings(30, 0, deleteLocalAfterVerifiedUpload = true, cloudSyncEnabled = true))
        assertEquals(setOf("safe"), plan.deleteLocalEverything)
        assertTrue(plan.deleteLocalImageOnly.isEmpty())
    }

    @Test
    fun cleanupAfterVerifiedUploadOnlyWhenEnabled() {
        val items = listOf(item("a", 1, UploadStatus.UPLOADED, VerificationStatus.VERIFIED, cloud = true))
        assertTrue(RetentionPolicy.plan(now, items, RetentionSettings(0, 0, false, true)).isEmpty)
        assertEquals(setOf("a"), RetentionPolicy.plan(now, items, RetentionSettings(0, 0, true, true)).deleteLocalImageOnly)
    }

    @Test
    fun cloudRetentionDeletesOnlyExpiredCloudCopies() {
        val items = listOf(
            item("old", 400, UploadStatus.UPLOADED, VerificationStatus.VERIFIED, cloud = true, local = false),
            item("young", 10, UploadStatus.UPLOADED, VerificationStatus.VERIFIED, cloud = true),
            item("localOnly", 400),
        )
        val plan = RetentionPolicy.plan(now, items, RetentionSettings(0, 365, false, true))
        assertEquals(setOf("old"), plan.deleteCloud)
    }

    @Test
    fun retryPolicyBacksOffAndGivesUp() {
        val policy = UploadRetryPolicy(maxAttempts = 4, baseDelayMillis = 1_000, maxDelayMillis = 5_000)
        assertEquals(1_000, policy.backoffMillis(1))
        assertEquals(2_000, policy.backoffMillis(2))
        assertEquals(5_000, policy.backoffMillis(10))
        assertIs<UploadDecision.Retry>(policy.decide(UploadErrorKind.NETWORK, 1))
        assertIs<UploadDecision.GiveUp>(policy.decide(UploadErrorKind.NETWORK, 4))
        assertIs<UploadDecision.GiveUp>(policy.decide(UploadErrorKind.QUOTA_EXCEEDED, 1))
        assertIs<UploadDecision.GiveUp>(policy.decide(UploadErrorKind.NOT_ENCRYPTED, 1))
        assertEquals(6 * 60 * 60 * 1000L, UploadRetryPolicy().backoffMillis(1000))
    }

    @Test
    fun wifiOnlyByDefault() {
        assertEquals(NetworkRequirement.UNMETERED, UploadPolicy.networkRequirement(allowMobileData = false))
        assertEquals(NetworkRequirement.CONNECTED, UploadPolicy.networkRequirement(allowMobileData = true))
        assertFalse(UploadPolicy.canUploadNow(NetworkKind.METERED, allowMobileData = false))
        assertTrue(UploadPolicy.canUploadNow(NetworkKind.METERED, allowMobileData = true))
        assertTrue(UploadPolicy.canUploadNow(NetworkKind.UNMETERED, allowMobileData = false))
        assertFalse(UploadPolicy.canUploadNow(NetworkKind.NONE, allowMobileData = true))
    }

    @Test
    fun storageThresholds() {
        val mb = 1024L * 1024
        assertEquals(LocalStorageState.EXHAUSTED, StoragePolicy.localState(100 * mb))
        assertEquals(LocalStorageState.LOW, StoragePolicy.localState(500 * mb))
        assertEquals(LocalStorageState.OK, StoragePolicy.localState(5000 * mb))
        assertEquals(CloudQuotaState.OK, StoragePolicy.cloudState(1, 100))
        assertEquals(CloudQuotaState.WARNING, StoragePolicy.cloudState(80, 100))
        assertEquals(CloudQuotaState.EXHAUSTED, StoragePolicy.cloudState(100, 100))
    }

    @Test
    fun cloudPathsFollowRequestedFolderStructure() {
        val t = Instant.parse("2026-11-11T23:30:05Z").toEpochMilli()
        assertEquals(
            "PhoneCaptureTracker/uid123/2026/11/11/Front/20261111T233005Z_front_0f8c-11.jpg.enc",
            CloudPaths.objectPath("uid123", CaptureCategory.FRONT, t, "0f8c-11"),
        )
        assertTrue(CloudPaths.objectPath("u", CaptureCategory.SCREENSHOT, t, "id").contains("/Screenshots/"))
        assertTrue(CloudPaths.objectPath("u", CaptureCategory.REAR, t, "id").contains("/Rear/"))
        assertEquals("users/u/captures/id", CloudPaths.captureDocument("u", "id"))
        assertEquals("users/u/vault/keyring", CloudPaths.keyringDocument("u"))
    }

    @Test
    fun cloudPathsRejectInjection() {
        assertFailsWith<IllegalArgumentException> { CloudPaths.objectPath("../other", CaptureCategory.FRONT, 0, "id") }
        assertFailsWith<IllegalArgumentException> { CloudPaths.objectPath("u", CaptureCategory.FRONT, 0, "a/b") }
        assertTrue(CloudPaths.belongsTo("u", "PhoneCaptureTracker/u/2026/x.enc"))
        assertFalse(CloudPaths.belongsTo("u", "PhoneCaptureTracker/uv/2026/x.enc"))
        assertFalse(CloudPaths.belongsTo("u", "PhoneCaptureTracker/u/../v/x.enc"))
    }
}

package com.phonecapture.tracker.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonecapture.tracker.capture.security.EncryptedFileStore
import com.phonecapture.tracker.capture.security.KeystoreKeyWrapper
import com.phonecapture.tracker.capture.security.VaultManager
import com.phonecapture.tracker.capture.security.VaultState
import com.phonecapture.tracker.core.crypto.WrongRecoveryCodeException
import com.phonecapture.tracker.core.model.CaptureCategory
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Encryption tests against the real Android Keystore on the device. */
@RunWith(AndroidJUnit4::class)
class EncryptionInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keystore = KeystoreKeyWrapper("pct_test_wrapping_key")
    private lateinit var vault: VaultManager

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(20_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        File(context.noBackupFilesDir, "vault").deleteRecursively()
        keystore.delete()
        vault = VaultManager(context, keystore)
    }

    @After
    fun tearDown() {
        vault.destroy()
        EncryptedFileStore(context, vault).deleteAll()
    }

    @Test
    fun filesOnDiskAreEncryptedAndDecryptable() {
        vault.setUpNewVault()
        assertTrue(vault.state.value is VaultState.Ready)
        val store = EncryptedFileStore(context, vault)
        val stored = store.save("cap-1", CaptureCategory.FRONT, System.currentTimeMillis(), jpeg, jpeg.copyOf(500))
        val onDisk = File(stored.imagePath).readBytes()
        assertFalse("JPEG marker must not be visible on disk", onDisk[0] == 0xFF.toByte() && onDisk[1] == 0xD8.toByte())
        assertArrayEquals(jpeg, store.readImage(stored.imagePath, "cap-1"))
        assertArrayEquals(jpeg.copyOf(500), store.readThumbnail(stored.thumbnailPath, "cap-1"))
    }

    @Test
    fun recoveryCodeRestoresKeysAfterTheKeystoreKeyIsLost() {
        val code = vault.setUpNewVault()
        val keyring = vault.localKeyring()!!
        val store = EncryptedFileStore(context, vault)
        val stored = store.save("cap-2", CaptureCategory.REAR, System.currentTimeMillis(), jpeg, jpeg.copyOf(10))
        val envelope = store.readEnvelope(stored.imagePath)

        // Simulate a new phone: Keystore key and vault files gone.
        vault.destroy()
        val newVault = VaultManager(context, keystore)
        assertTrue(newVault.state.value is VaultState.NotSetUp)

        try {
            newVault.restore(keyring, "0000-0000-0000-0000-0000-0000-0000-0000")
            fail("A wrong recovery code must be rejected")
        } catch (expected: WrongRecoveryCodeException) {
        }
        newVault.restore(keyring, code)
        assertTrue(newVault.state.value is VaultState.Ready)
        assertArrayEquals(jpeg, EncryptedFileStore(context, newVault).decryptImageEnvelope(envelope, "cap-2"))
        vault = newVault
    }

    @Test
    fun keystoreWrappingRoundTrip() {
        val secret = ByteArray(32) { it.toByte() }
        val wrapped = keystore.wrap(secret)
        assertFalse(wrapped.contentEquals(secret))
        assertArrayEquals(secret, keystore.unwrap(wrapped))
    }
}

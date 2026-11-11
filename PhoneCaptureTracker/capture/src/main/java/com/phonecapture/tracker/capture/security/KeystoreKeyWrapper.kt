package com.phonecapture.tracker.capture.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreKeyMissingException : Exception("The Android Keystore wrapping key does not exist")

/**
 * Wraps small secrets (the image data-encryption keys) with a non-exportable AES-256-GCM key held
 * in the Android Keystore – in StrongBox when the phone has it, otherwise in the TEE. The raw
 * wrapping key never enters app memory and cannot be copied off the device.
 *
 * Deliberately NOT set: user-authentication-required and unlocked-device-required, because
 * scheduled captures must be encrypted while the phone is locked.
 */
class KeystoreKeyWrapper(private val alias: String = DEFAULT_ALIAS) {

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    @Synchronized
    private fun getOrCreateKey(): SecretKey =
        existingKey() ?: generate(strongBox = true) ?: generate(strongBox = false)
            ?: throw KeyStoreException("Could not create the Keystore wrapping key")

    private fun generate(strongBox: Boolean): SecretKey? = try {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec)
        generator.generateKey()
    } catch (e: StrongBoxUnavailableException) {
        null
    } catch (e: ProviderException) {
        if (strongBox) null else throw e
    }

    /** Output: [ivLength][iv][ciphertext+tag]. The Keystore chooses the IV (randomized encryption). */
    fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + ciphertext
    }

    /** @throws KeystoreKeyMissingException if the Keystore key was deleted (e.g. data restored). */
    fun unwrap(blob: ByteArray): ByteArray {
        val key = existingKey() ?: throw KeystoreKeyMissingException()
        val ivLength = blob[0].toInt() and 0xFF
        require(ivLength in 12..16 && blob.size > 1 + ivLength) { "Malformed wrapped key" }
        val iv = blob.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(blob, 1 + ivLength, blob.size - 1 - ivLength)
    }

    fun hasKey(): Boolean = keyStore().containsAlias(alias)

    fun delete() {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    companion object {
        const val DEFAULT_ALIAS = "pct_vault_wrapping_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

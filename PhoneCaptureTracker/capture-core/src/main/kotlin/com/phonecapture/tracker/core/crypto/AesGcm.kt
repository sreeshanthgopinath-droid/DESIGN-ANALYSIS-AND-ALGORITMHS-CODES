package com.phonecapture.tracker.core.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM with a fresh random 96-bit nonce per message. A new [Cipher] instance is created for
 * every call, so a (key, nonce) pair is never reused by this code.
 */
object AesGcm {
    const val KEY_SIZE_BYTES = 32
    const val NONCE_SIZE_BYTES = 12
    const val TAG_SIZE_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun newKey(random: SecureRandom = SecureRandom()): SecretKey {
        val bytes = ByteArray(KEY_SIZE_BYTES)
        random.nextBytes(bytes)
        return SecretKeySpec(bytes, "AES")
    }

    fun keyFromBytes(bytes: ByteArray): SecretKey {
        require(bytes.size == KEY_SIZE_BYTES) { "AES-256 key must be $KEY_SIZE_BYTES bytes" }
        return SecretKeySpec(bytes.copyOf(), "AES")
    }

    fun newNonce(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(NONCE_SIZE_BYTES).also(random::nextBytes)

    fun encrypt(key: SecretKey, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(nonce.size == NONCE_SIZE_BYTES) { "Nonce must be $NONCE_SIZE_BYTES bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** @throws javax.crypto.AEADBadTagException when the data, nonce, AAD or key do not match. */
    fun decrypt(key: SecretKey, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        require(nonce.size == NONCE_SIZE_BYTES) { "Nonce must be $NONCE_SIZE_BYTES bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }
}

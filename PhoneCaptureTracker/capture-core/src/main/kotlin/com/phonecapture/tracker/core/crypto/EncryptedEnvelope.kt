package com.phonecapture.tracker.core.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.SecretKey

open class EnvelopeException(message: String, cause: Throwable? = null) : Exception(message, cause)
class NotAnEnvelopeException(message: String) : EnvelopeException(message)
class UnknownKeyException(val keyId: String) : EnvelopeException("No key available for key id '$keyId'")
class TamperedEnvelopeException(cause: Throwable) :
    EnvelopeException("Authentication failed: wrong key, wrong record, or modified data", cause)

/**
 * Self-describing encrypted container used for every image and thumbnail, locally and in the cloud.
 *
 * ```
 * offset  size  field
 * 0       4     magic "PCTE"
 * 4       1     format version (1)
 * 5       1     key-id length n (1..64)
 * 6       n     key id (ASCII)
 * 6+n     12    nonce
 * 18+n    ...   AES-256-GCM ciphertext followed by the 16-byte tag
 * ```
 *
 * The authenticated data is the header (magic..key id) plus a caller-supplied context string such
 * as "capture:<captureId>". Swapping a file between records, editing the header, or using the wrong
 * key makes decryption fail instead of returning wrong data.
 */
object EncryptedEnvelope {
    private val MAGIC = byteArrayOf(0x50, 0x43, 0x54, 0x45) // "PCTE"
    const val VERSION: Byte = 1
    private val KEY_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun seal(
        key: SecretKey,
        keyId: String,
        plaintext: ByteArray,
        context: String,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(KEY_ID_PATTERN.matches(keyId)) { "Invalid key id" }
        val header = header(keyId)
        val nonce = AesGcm.newNonce(random)
        val ciphertext = AesGcm.encrypt(key, nonce, plaintext, aad(header, context))
        return header + nonce + ciphertext
    }

    fun open(envelope: ByteArray, context: String, keyLookup: (String) -> SecretKey?): ByteArray {
        val parsed = parse(envelope)
        val key = keyLookup(parsed.keyId) ?: throw UnknownKeyException(parsed.keyId)
        return try {
            AesGcm.decrypt(key, parsed.nonce, parsed.ciphertext, aad(parsed.header, context))
        } catch (e: GeneralSecurityException) {
            throw TamperedEnvelopeException(e)
        }
    }

    fun isEnvelope(bytes: ByteArray): Boolean = runCatching { parse(bytes) }.isSuccess

    fun keyIdOf(envelope: ByteArray): String = parse(envelope).keyId

    /** Bytes added on top of the plaintext size. */
    fun overhead(keyId: String): Int = 6 + keyId.length + AesGcm.NONCE_SIZE_BYTES + AesGcm.TAG_SIZE_BITS / 8

    private class Parsed(val header: ByteArray, val keyId: String, val nonce: ByteArray, val ciphertext: ByteArray)

    private fun parse(bytes: ByteArray): Parsed {
        if (bytes.size < 6) throw NotAnEnvelopeException("Too short")
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) throw NotAnEnvelopeException("Bad magic")
        if (bytes[4] != VERSION) throw NotAnEnvelopeException("Unsupported version ${bytes[4]}")
        val keyIdLength = bytes[5].toInt() and 0xFF
        if (keyIdLength !in 1..64) throw NotAnEnvelopeException("Bad key id length")
        val headerLength = 6 + keyIdLength
        val minimum = headerLength + AesGcm.NONCE_SIZE_BYTES + AesGcm.TAG_SIZE_BITS / 8
        if (bytes.size < minimum) throw NotAnEnvelopeException("Truncated")
        val keyId = String(bytes, 6, keyIdLength, Charsets.US_ASCII)
        if (!KEY_ID_PATTERN.matches(keyId)) throw NotAnEnvelopeException("Bad key id")
        return Parsed(
            header = bytes.copyOfRange(0, headerLength),
            keyId = keyId,
            nonce = bytes.copyOfRange(headerLength, headerLength + AesGcm.NONCE_SIZE_BYTES),
            ciphertext = bytes.copyOfRange(headerLength + AesGcm.NONCE_SIZE_BYTES, bytes.size),
        )
    }

    private fun header(keyId: String): ByteArray {
        val id = keyId.toByteArray(Charsets.US_ASCII)
        return MAGIC + byteArrayOf(VERSION, id.size.toByte()) + id
    }

    private fun aad(header: ByteArray, context: String): ByteArray = header + context.toByteArray(Charsets.UTF_8)
}

package com.phonecapture.tracker.core.crypto

import java.io.StringReader
import java.io.StringWriter
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongRecoveryCodeException(cause: Throwable? = null) :
    Exception("The recovery code does not unlock this keyring", cause)

/** One data-encryption key, wrapped (AES-256-GCM) with the key derived from the recovery code. */
data class KeyringEntry(val keyId: String, val nonceB64: String, val wrappedKeyB64: String)

/**
 * Everything needed to recover the image keys on a new phone, except the recovery code itself.
 * It contains no plaintext keys, so it can be stored next to the encrypted images in Firestore.
 * Without the user's recovery code the images in the cloud cannot be decrypted by anyone,
 * including the developer.
 */
data class RecoveryKeyring(
    val version: Int,
    val kdf: String,
    val saltB64: String,
    val iterations: Int,
    val currentKeyId: String,
    val createdAtUtcMillis: Long,
    val entries: List<KeyringEntry>,
) {
    val keyIds: List<String> get() = entries.map { it.keyId }

    fun toMap(): Map<String, String> {
        val keyringEntries = entries // not the map builder's `entries` below
        return buildMap { putKeyringFields(this, keyringEntries) }
    }

    private fun putKeyringFields(target: MutableMap<String, String>, keyringEntries: List<KeyringEntry>) = with(target) {
        put("version", version.toString())
        put("kdf", kdf)
        put("salt", saltB64)
        put("iterations", iterations.toString())
        put("currentKeyId", currentKeyId)
        put("createdAtUtc", createdAtUtcMillis.toString())
        put("count", keyringEntries.size.toString())
        keyringEntries.forEachIndexed { i, e ->
            put("entry.$i.keyId", e.keyId)
            put("entry.$i.nonce", e.nonceB64)
            put("entry.$i.wrapped", e.wrappedKeyB64)
        }
    }

    fun serialize(): String {
        val props = Properties()
        toMap().forEach { (k, v) -> props.setProperty(k, v) }
        return StringWriter().also { props.store(it, null) }.toString()
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val KDF_PBKDF2_SHA256 = "PBKDF2WithHmacSHA256"

        fun fromMap(map: Map<String, String>): RecoveryKeyring {
            fun req(key: String) = map[key] ?: throw IllegalArgumentException("Keyring is missing '$key'")
            val count = req("count").toInt()
            require(count in 1..64) { "Invalid keyring entry count" }
            val version = req("version").toInt()
            require(version == FORMAT_VERSION) { "Unsupported keyring version $version" }
            return RecoveryKeyring(
                version = version,
                kdf = req("kdf"),
                saltB64 = req("salt"),
                iterations = req("iterations").toInt(),
                currentKeyId = req("currentKeyId"),
                createdAtUtcMillis = req("createdAtUtc").toLong(),
                entries = (0 until count).map { i ->
                    KeyringEntry(req("entry.$i.keyId"), req("entry.$i.nonce"), req("entry.$i.wrapped"))
                },
            )
        }

        fun deserialize(text: String): RecoveryKeyring {
            val props = Properties().apply { load(StringReader(text)) }
            return fromMap(props.stringPropertyNames().associateWith { props.getProperty(it) })
        }
    }
}

/**
 * Creates and opens [RecoveryKeyring]s.
 *
 * Key hierarchy:
 *  - Data-encryption keys (DEKs, AES-256) encrypt images. On the phone they are stored wrapped by a
 *    non-exportable Android Keystore key (see the :capture module).
 *  - For recovery, each DEK is also wrapped by a key-encryption key derived from the recovery code
 *    with PBKDF2-HMAC-SHA256 and a random 128-bit salt.
 */
object RecoveryVault {
    const val DEFAULT_ITERATIONS = 310_000
    private const val SALT_BYTES = 16
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    fun create(
        keys: Map<String, SecretKey>,
        currentKeyId: String,
        recoveryCode: String,
        nowUtcMillis: Long,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): RecoveryKeyring {
        require(keys.isNotEmpty()) { "At least one key is required" }
        require(currentKeyId in keys) { "Current key must be part of the keyring" }
        val code = RecoveryCode.normalize(recoveryCode) ?: throw IllegalArgumentException("Malformed recovery code")
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val kek = deriveKek(code, salt, iterations)
        return RecoveryKeyring(
            version = RecoveryKeyring.FORMAT_VERSION,
            kdf = RecoveryKeyring.KDF_PBKDF2_SHA256,
            saltB64 = b64e.encodeToString(salt),
            iterations = iterations,
            currentKeyId = currentKeyId,
            createdAtUtcMillis = nowUtcMillis,
            entries = keys.entries.sortedBy { it.key }.map { (id, key) -> wrap(kek, id, key, random) },
        )
    }

    /** @return all keys in the keyring, by key id. */
    fun unlock(keyring: RecoveryKeyring, recoveryCode: String): Map<String, SecretKey> {
        val code = RecoveryCode.normalize(recoveryCode) ?: throw WrongRecoveryCodeException()
        require(keyring.kdf == RecoveryKeyring.KDF_PBKDF2_SHA256) { "Unsupported KDF ${keyring.kdf}" }
        val kek = deriveKek(code, b64d.decode(keyring.saltB64), keyring.iterations)
        return keyring.entries.associate { entry ->
            val plain = try {
                AesGcm.decrypt(kek, b64d.decode(entry.nonceB64), b64d.decode(entry.wrappedKeyB64), aad(entry.keyId))
            } catch (e: GeneralSecurityException) {
                throw WrongRecoveryCodeException(e)
            }
            entry.keyId to AesGcm.keyFromBytes(plain)
        }
    }

    /** Re-creates the keyring with additional keys, using the same recovery code. */
    fun withAdditionalKeys(
        keyring: RecoveryKeyring,
        recoveryCode: String,
        extraKeys: Map<String, SecretKey>,
        currentKeyId: String,
        nowUtcMillis: Long,
        random: SecureRandom = SecureRandom(),
    ): RecoveryKeyring {
        val all = unlock(keyring, recoveryCode) + extraKeys
        return create(all, currentKeyId, recoveryCode, nowUtcMillis, keyring.iterations, random)
    }

    fun deriveKek(normalizedCode: String, salt: ByteArray, iterations: Int): SecretKey {
        require(iterations >= 1_000) { "Too few PBKDF2 iterations" }
        val spec = PBEKeySpec(normalizedCode.toCharArray(), salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance(RecoveryKeyring.KDF_PBKDF2_SHA256).generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun wrap(kek: SecretKey, keyId: String, key: SecretKey, random: SecureRandom): KeyringEntry {
        val nonce = AesGcm.newNonce(random)
        val wrapped = AesGcm.encrypt(kek, nonce, key.encoded, aad(keyId))
        return KeyringEntry(keyId, b64e.encodeToString(nonce), b64e.encodeToString(wrapped))
    }

    private fun aad(keyId: String) = "pct-recovery-v1:$keyId".toByteArray(Charsets.UTF_8)
}

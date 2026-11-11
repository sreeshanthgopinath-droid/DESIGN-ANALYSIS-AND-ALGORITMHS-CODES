package com.phonecapture.tracker.capture.security

import android.content.Context
import com.phonecapture.tracker.core.crypto.AesGcm
import com.phonecapture.tracker.core.crypto.RecoveryCode
import com.phonecapture.tracker.core.crypto.RecoveryKeyring
import com.phonecapture.tracker.core.crypto.RecoveryVault
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VaultUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface VaultState {
    /** No key yet: onboarding creates one (or restores from a recovery code). */
    data object NotSetUp : VaultState

    data class Ready(val currentKeyId: String, val keyCount: Int) : VaultState

    /** Keys exist but cannot be unwrapped (Keystore key lost). The recovery code fixes this. */
    data class Locked(val reason: String) : VaultState
}

/**
 * Owns the image data-encryption keys (DEKs).
 *
 *  - Each DEK is a random AES-256 key, stored on disk only in Keystore-wrapped form
 *    (no-backup app storage, so it is never included in device backups).
 *  - A recovery keyring (DEKs wrapped with a key derived from the user's recovery code) is kept
 *    locally and, when cloud sync is on, in Firestore. With the recovery code, a new phone can
 *    decrypt every image previously uploaded. Without it, nobody can.
 *  - The recovery code is shown to the user once and is never stored.
 */
class VaultManager(
    context: Context,
    private val keystore: KeystoreKeyWrapper,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val dir = File(context.noBackupFilesDir, "vault")
    private val keysDir = File(dir, "keys")
    private val currentFile = File(dir, "current_key_id")
    private val keyringFile = File(dir, "recovery_keyring.properties")
    private val cache = ConcurrentHashMap<String, SecretKey>()

    private val _state = MutableStateFlow<VaultState>(VaultState.NotSetUp)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    init {
        refresh()
    }

    @Synchronized
    fun refresh(): VaultState {
        val current = currentKeyIdOrNull()
        val next = when {
            current == null -> VaultState.NotSetUp
            keyFor(current) != null -> VaultState.Ready(current, localKeyIds().size)
            else -> VaultState.Locked("This phone's encryption key could not be unlocked.")
        }
        _state.value = next
        return next
    }

    /** Creates the first key and returns the recovery code to show to the user once. */
    @Synchronized
    fun setUpNewVault(): String {
        check(currentKeyIdOrNull() == null) { "The vault is already set up" }
        val keyId = newKeyId()
        val dek = AesGcm.newKey(random)
        storeKey(keyId, dek)
        writeText(currentFile, keyId)
        val code = RecoveryCode.generate(random)
        writeText(keyringFile, RecoveryVault.create(mapOf(keyId to dek), keyId, code, clock()).serialize())
        refresh()
        return code
    }

    /** @throws VaultUnavailableException when no usable key exists. */
    @Synchronized
    fun currentKey(): Pair<String, SecretKey> {
        val id = currentKeyIdOrNull() ?: throw VaultUnavailableException("Encryption is not set up yet")
        val key = keyFor(id) ?: throw VaultUnavailableException("The encryption key is locked; use the recovery code")
        return id to key
    }

    @Synchronized
    fun keyFor(keyId: String): SecretKey? {
        cache[keyId]?.let { return it }
        val file = keyFile(keyId)
        if (!file.isFile) return null
        return try {
            AesGcm.keyFromBytes(keystore.unwrap(file.readBytes())).also { cache[keyId] = it }
        } catch (e: Exception) {
            null
        }
    }

    fun localKeyring(): RecoveryKeyring? =
        runCatching { RecoveryKeyring.deserialize(keyringFile.readText()) }.getOrNull()

    /**
     * Restores keys from a keyring (usually downloaded from the cloud) with the recovery code.
     * Keys that exist only on this phone are added to the keyring, so nothing becomes unreadable.
     *
     * @return the merged keyring, which should be uploaded again.
     * @throws com.phonecapture.tracker.core.crypto.WrongRecoveryCodeException
     */
    @Synchronized
    fun restore(keyring: RecoveryKeyring, recoveryCode: String): RecoveryKeyring {
        val restored = RecoveryVault.unlock(keyring, recoveryCode)
        val localOnly = localKeyIds()
            .filter { it !in restored }
            .mapNotNull { id -> keyFor(id)?.let { id to it } }
            .toMap()
        restored.forEach { (id, key) -> storeKey(id, key) }
        writeText(currentFile, keyring.currentKeyId)
        val merged = if (localOnly.isEmpty()) {
            keyring
        } else {
            RecoveryVault.withAdditionalKeys(keyring, recoveryCode, localOnly, keyring.currentKeyId, clock())
        }
        writeText(keyringFile, merged.serialize())
        refresh()
        return merged
    }

    /** Issues a new recovery code (e.g. if the old one was exposed). The old code stops working. */
    @Synchronized
    fun regenerateRecoveryCode(): Pair<String, RecoveryKeyring> {
        val (currentId, _) = currentKey()
        val keys = localKeyIds().associateWith { id ->
            keyFor(id) ?: throw VaultUnavailableException("Key $id cannot be unlocked on this phone")
        }
        val code = RecoveryCode.generate(random)
        val keyring = RecoveryVault.create(keys, currentId, code, clock())
        writeText(keyringFile, keyring.serialize())
        return code to keyring
    }

    /** Deletes every key. Encrypted images on this phone become permanently unreadable. */
    @Synchronized
    fun destroy() {
        keysDir.deleteRecursively()
        currentFile.delete()
        keyringFile.delete()
        cache.clear()
        keystore.delete()
        refresh()
    }

    private fun currentKeyIdOrNull(): String? =
        runCatching { currentFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun localKeyIds(): List<String> =
        keysDir.listFiles()?.filter { it.name.endsWith(".bin") }?.map { it.name.removeSuffix(".bin") }.orEmpty()

    private fun keyFile(keyId: String): File {
        require(keyId.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))) { "Invalid key id" }
        return File(keysDir, "$keyId.bin")
    }

    private fun storeKey(keyId: String, key: SecretKey) {
        keysDir.mkdirs()
        writeBytes(keyFile(keyId), keystore.wrap(key.encoded))
        cache[keyId] = key
    }

    private fun newKeyId(): String {
        val bytes = ByteArray(8).also(random::nextBytes)
        return "k" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun writeText(file: File, text: String) = writeBytes(file, text.toByteArray(Charsets.UTF_8))

    private fun writeBytes(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not write ${file.name}" }
        }
    }
}

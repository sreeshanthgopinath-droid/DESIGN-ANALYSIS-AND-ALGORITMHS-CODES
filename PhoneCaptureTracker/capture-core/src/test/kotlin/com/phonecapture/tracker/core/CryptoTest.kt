package com.phonecapture.tracker.core

import com.phonecapture.tracker.core.crypto.AesGcm
import com.phonecapture.tracker.core.crypto.EncryptedEnvelope
import com.phonecapture.tracker.core.crypto.Hashing
import com.phonecapture.tracker.core.crypto.NotAnEnvelopeException
import com.phonecapture.tracker.core.crypto.RecoveryCode
import com.phonecapture.tracker.core.crypto.RecoveryKeyring
import com.phonecapture.tracker.core.crypto.RecoveryVault
import com.phonecapture.tracker.core.crypto.TamperedEnvelopeException
import com.phonecapture.tracker.core.crypto.UnknownKeyException
import com.phonecapture.tracker.core.crypto.WrongRecoveryCodeException
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoTest {
    private val random = SecureRandom()
    private val key = AesGcm.newKey(random)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(5000) { it.toByte() }
    private val iterations = 1_000 // production uses RecoveryVault.DEFAULT_ITERATIONS; fewer here for speed

    @Test
    fun envelopeRoundTrip() {
        val sealed = EncryptedEnvelope.seal(key, "k1", jpeg, "capture:abc")
        assertContentEquals(jpeg, EncryptedEnvelope.open(sealed, "capture:abc") { if (it == "k1") key else null })
        assertEquals("k1", EncryptedEnvelope.keyIdOf(sealed))
        assertEquals(jpeg.size + EncryptedEnvelope.overhead("k1"), sealed.size)
    }

    @Test
    fun ciphertextDoesNotContainJpegMarkerAndNoncesAreUnique() {
        val a = EncryptedEnvelope.seal(key, "k1", jpeg, "capture:abc")
        val b = EncryptedEnvelope.seal(key, "k1", jpeg, "capture:abc")
        assertFalse(a.copyOfRange(0, 3).contentEquals(jpeg.copyOfRange(0, 3)))
        assertFalse(a.contentEquals(b), "same plaintext must give different ciphertext (unique nonces)")
        val nonces = (1..2000).map { EncryptedEnvelope.seal(key, "k1", byteArrayOf(1), "c").copyOfRange(8, 20).toList() }
        assertEquals(nonces.size, nonces.toSet().size)
    }

    @Test
    fun envelopeIsBoundToItsRecord() {
        val sealed = EncryptedEnvelope.seal(key, "k1", jpeg, "capture:abc")
        assertFailsWith<TamperedEnvelopeException> { EncryptedEnvelope.open(sealed, "capture:other") { key } }
    }

    @Test
    fun tamperingAndWrongKeysAreDetected() {
        val sealed = EncryptedEnvelope.seal(key, "k1", jpeg, "ctx")
        val flipped = sealed.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 1).toByte() }
        assertFailsWith<TamperedEnvelopeException> { EncryptedEnvelope.open(flipped, "ctx") { key } }
        assertFailsWith<TamperedEnvelopeException> { EncryptedEnvelope.open(sealed, "ctx") { AesGcm.newKey() } }
        assertFailsWith<UnknownKeyException> { EncryptedEnvelope.open(sealed, "ctx") { null } }
    }

    @Test
    fun plainJpegIsNotAnEnvelope() {
        assertFalse(EncryptedEnvelope.isEnvelope(jpeg))
        assertFailsWith<NotAnEnvelopeException> { EncryptedEnvelope.open(jpeg, "ctx") { key } }
        assertTrue(EncryptedEnvelope.isEnvelope(EncryptedEnvelope.seal(key, "k1", jpeg, "ctx")))
    }

    @Test
    fun recoveryCodeFormatAndTypoTolerance() {
        val code = RecoveryCode.generate(random)
        assertEquals(39, code.length) // 32 chars + 7 dashes
        assertTrue(code.matches(Regex("^([0-9A-HJKMNP-TV-Z]{4}-){7}[0-9A-HJKMNP-TV-Z]{4}$")))
        val normalized = RecoveryCode.normalize(code)!!
        assertEquals(normalized, RecoveryCode.normalize(code.lowercase().replace("-", " ")))
        assertEquals("0" + "1".repeat(31), RecoveryCode.normalize("o" + "il".repeat(15) + "I"))
        assertNull(RecoveryCode.normalize("too-short"))
        assertNull(RecoveryCode.normalize("U".repeat(32))) // U is not in Crockford's alphabet
    }

    @Test
    fun base32RoundTrip() {
        repeat(50) {
            val bytes = ByteArray(RecoveryCode.ENTROPY_BYTES).also(random::nextBytes)
            assertContentEquals(bytes, RecoveryCode.decode(RecoveryCode.encode(bytes)))
        }
    }

    @Test
    fun keyringRecoversKeysOnANewDevice() {
        val keys = mapOf("k1" to key, "k2" to AesGcm.newKey(random))
        val code = RecoveryCode.generate(random)
        val keyring = RecoveryVault.create(keys, "k2", code, nowUtcMillis = 1L, iterations = iterations)

        // Simulate storage in Firestore (string map) and on disk (properties text).
        val fromCloud = RecoveryKeyring.fromMap(keyring.toMap())
        val fromDisk = RecoveryKeyring.deserialize(keyring.serialize())
        assertEquals(keyring, fromCloud)
        assertEquals(keyring, fromDisk)

        val sealed = EncryptedEnvelope.seal(key, "k1", jpeg, "capture:x")
        val restored = RecoveryVault.unlock(fromCloud, code.lowercase())
        assertEquals(setOf("k1", "k2"), restored.keys)
        assertContentEquals(jpeg, EncryptedEnvelope.open(sealed, "capture:x") { restored[it] })
        assertEquals("k2", fromCloud.currentKeyId)
    }

    @Test
    fun wrongRecoveryCodeIsRejected() {
        val keyring = RecoveryVault.create(mapOf("k1" to key), "k1", RecoveryCode.generate(random), 1L, iterations)
        assertFailsWith<WrongRecoveryCodeException> { RecoveryVault.unlock(keyring, RecoveryCode.generate(random)) }
        assertFailsWith<WrongRecoveryCodeException> { RecoveryVault.unlock(keyring, "not a code") }
    }

    @Test
    fun keyringCanBeExtendedWithSameCodeAndUsesFreshSalt() {
        val code = RecoveryCode.generate(random)
        val keyring = RecoveryVault.create(mapOf("k1" to key), "k1", code, 1L, iterations)
        val extra = AesGcm.newKey(random)
        val extended = RecoveryVault.withAdditionalKeys(keyring, code, mapOf("k9" to extra), "k9", 2L)
        assertEquals(listOf("k1", "k9"), extended.keyIds)
        assertNotEquals(keyring.saltB64, extended.saltB64)
        assertContentEquals(extra.encoded, RecoveryVault.unlock(extended, code)["k9"]!!.encoded)
    }

    @Test
    fun tooFewIterationsAreRefused() {
        assertFailsWith<IllegalArgumentException> {
            RecoveryVault.create(mapOf("k1" to key), "k1", RecoveryCode.generate(random), 1L, iterations = 10)
        }
    }

    @Test
    fun hashingMatchesKnownVectors() {
        val abc = "abc".toByteArray()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256Hex(abc))
        assertEquals("kAFQmDzST7DWlj99KOF/cg==", Hashing.md5Base64(abc))
    }
}

package com.phonecapture.tracker.core.crypto

import java.security.SecureRandom

/**
 * A user-held recovery code: 160 random bits written as 32 Crockford Base32 characters in groups
 * of four, e.g. `7Q2M-K9XD-...`. Crockford's alphabet avoids I, L, O and U, and the parser accepts
 * common mistakes (lower case, O for 0, I/L for 1, spaces or missing dashes).
 */
object RecoveryCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val ENTROPY_BYTES = 20
    const val LENGTH = ENTROPY_BYTES * 8 / 5 // 32 characters

    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(ENTROPY_BYTES).also(random::nextBytes)
        return format(encode(bytes))
    }

    /** Returns the canonical 32-character form, or null if the input cannot be a valid code. */
    fun normalize(input: String): String? {
        val builder = StringBuilder(LENGTH)
        for (raw in input) {
            if (raw == '-' || raw.isWhitespace()) continue
            val c = when (val upper = raw.uppercaseChar()) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> upper
            }
            if (ALPHABET.indexOf(c) < 0) return null
            builder.append(c)
        }
        return builder.toString().takeIf { it.length == LENGTH }
    }

    fun format(normalized: String): String = normalized.chunked(4).joinToString("-")

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    fun decode(normalized: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in normalized) {
            val value = ALPHABET.indexOf(c)
            require(value >= 0) { "Invalid character" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}

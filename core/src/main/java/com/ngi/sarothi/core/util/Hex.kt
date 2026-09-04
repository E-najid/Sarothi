package com.ngi.sarothi.core.util

/**
 * Lowercase hex encoding/decoding.
 *
 * Used for salts, verifier hashes and the wrapped biometric key — all of which
 * are stored as text (manifest.json is JSON, EncryptedSharedPreferences has no
 * binary type). Kept as one small object so the encoding is identical everywhere.
 */
object Hex {

    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            out[index * 2] = DIGITS[value ushr 4]
            out[index * 2 + 1] = DIGITS[value and 0x0F]
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have an even length, got ${hex.length}" }
        return ByteArray(hex.length / 2) { index ->
            val high = Character.digit(hex[index * 2], 16)
            val low = Character.digit(hex[index * 2 + 1], 16)
            require(high >= 0 && low >= 0) {
                "invalid hex character at offset ${index * 2} in '$hex'"
            }
            ((high shl 4) or low).toByte()
        }
    }

    fun isHex(text: String): Boolean =
        text.isNotEmpty() && text.length % 2 == 0 && text.all { Character.digit(it, 16) >= 0 }
}

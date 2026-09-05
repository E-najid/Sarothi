package com.ngi.sarothi.core.crypto

import java.io.ByteArrayOutputStream

/**
 * Passphrase handling that avoids ever materialising an immutable `String`.
 *
 * A Kotlin/Java `String` cannot be zeroed — it survives in the heap until the GC
 * gets to it, and may be interned. Sarothi takes the passphrase as a
 * `CharArray` from the UI, encodes it straight to UTF-8 bytes, and wipes both
 * buffers as soon as the key is derived.
 *
 * The encoder is written out by hand (rather than `String(chars).toByteArray()`)
 * precisely so no intermediate String exists. Surrogate pairs are handled, so a
 * passphrase containing emoji or characters outside the BMP derives the same key
 * as any other UTF-8 implementation.
 */
object PasswordBytes {

    fun encodeUtf8(password: CharArray): ByteArray {
        val out = ByteArrayOutputStream(password.size * 2)
        var index = 0
        while (index < password.size) {
            val code = password[index].code
            when {
                code < 0x80 -> out.write(code)

                code < 0x800 -> {
                    out.write(0xC0 or (code shr 6))
                    out.write(0x80 or (code and 0x3F))
                }

                // High surrogate followed by a low surrogate -> one code point.
                code in 0xD800..0xDBFF &&
                    index + 1 < password.size &&
                    password[index + 1].code in 0xDC00..0xDFFF -> {
                    val codePoint =
                        0x10000 + ((code - 0xD800) shl 10) + (password[index + 1].code - 0xDC00)
                    out.write(0xF0 or (codePoint shr 18))
                    out.write(0x80 or ((codePoint shr 12) and 0x3F))
                    out.write(0x80 or ((codePoint shr 6) and 0x3F))
                    out.write(0x80 or (codePoint and 0x3F))
                    index++
                }

                else -> {
                    out.write(0xE0 or (code shr 12))
                    out.write(0x80 or ((code shr 6) and 0x3F))
                    out.write(0x80 or (code and 0x3F))
                }
            }
            index++
        }
        return out.toByteArray()
    }

    /** Overwrites a buffer so the passphrase/key does not linger in the heap. */
    fun wipe(buffer: ByteArray?) {
        if (buffer == null) return
        buffer.fill(0)
    }

    fun wipe(buffer: CharArray?) {
        if (buffer == null) return
        buffer.fill('\u0000')
    }

    /**
     * Runs [block] with the passphrase encoded, guaranteeing both the CharArray
     * and the derived byte copy are wiped even if [block] throws.
     *
     * **This consumes [password].** When it returns, every element is `'\u0000'`, so it
     * must be the *last* thing the caller does with that array. Composing two calls that
     * each use this helper over one array is the bug it is easiest to make here and the
     * hardest to notice anywhere else: the second call encodes NUL characters instead of
     * the passphrase and still produces a perfectly good-looking key. That is exactly what
     * `MasterKeyManager.unlock` did, which is why it now encodes once and derives twice
     * inside a single block.
     */
    inline fun <T> withPasswordBytes(password: CharArray, block: (ByteArray) -> T): T {
        val bytes = encodeUtf8(password)
        try {
            return block(bytes)
        } finally {
            wipe(bytes)
            wipe(password)
        }
    }

    /** Minimum passphrase length enforced at setup. A 4-digit PIN is allowed too,
     *  because the spec asks for "password/PIN"; the strength warning is shown in
     *  the UI rather than silently accepting a weak secret. */
    const val MIN_LENGTH = 4

    fun strength(passphrase: CharArray): Strength {
        val length = passphrase.size
        val classes = listOf(
            passphrase.any { it in 'a'..'z' },
            passphrase.any { it in 'A'..'Z' },
            passphrase.any { it in '0'..'9' },
            passphrase.any { it.code > 0x7F || !it.isLetterOrDigit() },
        ).count { it }
        return when {
            length < MIN_LENGTH -> Strength.REJECTED
            length < 6 || classes <= 1 -> Strength.WEAK
            length < 10 || classes == 2 -> Strength.FAIR
            length < 14 || classes == 3 -> Strength.GOOD
            else -> Strength.STRONG
        }
    }

    enum class Strength { REJECTED, WEAK, FAIR, GOOD, STRONG }
}

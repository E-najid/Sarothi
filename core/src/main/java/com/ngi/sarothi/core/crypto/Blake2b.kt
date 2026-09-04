package com.ngi.sarothi.core.crypto

/**
 * BLAKE2b (RFC 7693), implemented in pure Kotlin.
 *
 * Why not BouncyCastle: Android's boot classpath ships an old `org.bouncycastle`
 * package, and the platform copy wins class loading over an APK-bundled one.
 * Mixing a bundled `bcprov` with the platform's subset is a well-known source of
 * NoSuchMethodError at runtime. Argon2id only needs BLAKE2b, so Sarothi carries
 * its own small, auditable implementation and proves it against RFC test vectors
 * in `core/src/test/.../Blake2bTest.kt`.
 *
 * Supports arbitrary digest lengths (1..64 bytes) and keyed hashing, plus the
 * streaming update/digest pattern the downloader and Argon2 both need.
 */
class Blake2b(
    private val digestLength: Int,
    key: ByteArray = EMPTY_KEY,
) {
    init {
        require(digestLength in 1..DIGEST_LENGTH) {
            "BLAKE2b digest length must be in 1..$DIGEST_LENGTH, was $digestLength"
        }
        require(key.size <= KEY_LENGTH) {
            "BLAKE2b key must be at most $KEY_LENGTH bytes, was ${key.size}"
        }
    }

    private val h = LongArray(8)
    private val buffer = ByteArray(BLOCK_LENGTH)
    private var bufferLength = 0

    /** 128-bit byte counter: t0 is the low word fed into the compression function. */
    private var counterLow = 0L
    private var counterHigh = 0L

    // Working vectors, kept as fields so hashing does not allocate per block.
    private val v = LongArray(16)
    private val m = LongArray(16)

    private var finished = false

    init {
        System.arraycopy(IV, 0, h, 0, 8)
        // Parameter block: fanout=1, depth=1, leafLength=0, nodeOffset=0,
        // nodeDepth=0, innerLength=0, reserved, salt/personal empty.
        // Word 0 = digestLength | (keyLength << 8) | (fanout << 16) | (depth << 24)
        h[0] = h[0] xor (
            digestLength.toLong() and 0xFFL or
                ((key.size.toLong() and 0xFFL) shl 8) or
                (0x01L shl 16) or
                (0x01L shl 24)
            )
        if (key.isNotEmpty()) {
            val keyBlock = ByteArray(BLOCK_LENGTH)
            System.arraycopy(key, 0, keyBlock, 0, key.size)
            update(keyBlock, 0, BLOCK_LENGTH)
        }
    }

    fun update(input: ByteArray, offset: Int = 0, length: Int = input.size): Blake2b {
        check(!finished) { "Blake2b has already been finalised" }
        require(offset >= 0 && length >= 0 && offset + length <= input.size) {
            "update range out of bounds"
        }
        var cursor = offset
        var remaining = length
        while (remaining > 0) {
            if (bufferLength == BLOCK_LENGTH) {
                // The final block must never be compressed here: BLAKE2b needs to
                // know which block is last, so a full buffer is only compressed
                // once more input actually arrives.
                addCounter(BLOCK_LENGTH.toLong())
                compress(lastBlock = false)
                bufferLength = 0
            }
            val copyLength = minOf(BLOCK_LENGTH - bufferLength, remaining)
            System.arraycopy(input, cursor, buffer, bufferLength, copyLength)
            bufferLength += copyLength
            cursor += copyLength
            remaining -= copyLength
        }
        return this
    }

    fun digest(): ByteArray {
        check(!finished) { "Blake2b has already been finalised" }
        finished = true
        addCounter(bufferLength.toLong())
        for (index in bufferLength until BLOCK_LENGTH) buffer[index] = 0
        compress(lastBlock = true)

        val output = ByteArray(digestLength)
        var produced = 0
        var word = 0
        while (produced < digestLength) {
            val value = h[word++]
            for (byte in 0 until 8) {
                if (produced >= digestLength) break
                output[produced++] = ((value ushr (8 * byte)) and 0xFFL).toByte()
            }
        }
        return output
    }

    private fun addCounter(amount: Long) {
        val previous = counterLow
        counterLow += amount
        // Unsigned overflow of the low word carries into the high word.
        if (java.lang.Long.compareUnsigned(counterLow, previous) < 0) counterHigh++
    }

    private fun compress(lastBlock: Boolean) {
        // Load the block as 16 little-endian 64-bit words.
        for (i in 0 until 16) {
            val base = i * 8
            var word = 0L
            for (byte in 0 until 8) {
                word = word or ((buffer[base + byte].toLong() and 0xFFL) shl (8 * byte))
            }
            m[i] = word
        }

        System.arraycopy(h, 0, v, 0, 8)
        v[8] = IV[0]
        v[9] = IV[1]
        v[10] = IV[2]
        v[11] = IV[3]
        v[12] = IV[4] xor counterLow
        v[13] = IV[5] xor counterHigh
        v[14] = if (lastBlock) IV[6] xor ALL_BITS else IV[6]
        v[15] = IV[7]

        for (round in 0 until ROUNDS) {
            val s = SIGMA[round]
            mix(v[0], v[4], v[8], v[12], m[s[0]], m[s[1]])
            mix(v[1], v[5], v[9], v[13], m[s[2]], m[s[3]])
            mix(v[2], v[6], v[10], v[14], m[s[4]], m[s[5]])
            mix(v[3], v[7], v[11], v[15], m[s[6]], m[s[7]])
            mix(v[0], v[5], v[10], v[15], m[s[8]], m[s[9]])
            mix(v[1], v[6], v[11], v[12], m[s[10]], m[s[11]])
            mix(v[2], v[7], v[8], v[13], m[s[12]], m[s[13]])
            mix(v[3], v[4], v[9], v[14], m[s[14]], m[s[15]])
        }

        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    /**
     * The G mixing function. Kotlin passes Long by value, so this writes back into
     * [state] through the four indices instead of taking parameters by reference.
     */
    private fun mix(state: LongArray, ia: Int, ib: Int, ic: Int, id: Int, x: Long, y: Long) {
        var a = state[ia]
        var b = state[ib]
        var c = state[ic]
        var d = state[id]

        a += b + x
        d = (d xor a).rotateRight(32)
        c += d
        b = (b xor c).rotateRight(24)
        a += b + y
        d = (d xor a).rotateRight(16)
        c += d
        b = (b xor c).rotateRight(63)

        state[ia] = a
        state[ib] = b
        state[ic] = c
        state[id] = d
    }

    companion object {
        const val DIGEST_LENGTH = 64
        const val BLOCK_LENGTH = 128
        const val KEY_LENGTH = 64
        private const val ROUNDS = 12
        private val EMPTY_KEY = ByteArray(0)
        private const val ALL_BITS = -1L // 0xFFFFFFFFFFFFFFFF

        /** SHA-512 initial hash values, reused by BLAKE2b as the IV. */
        private val IV = longArrayOf(
            0x6A09E667F3BCC908L, 0xBB67AE8584CAA73BL,
            0x3C6EF372FE94F82BL, 0xA54FF53A5F1D36F1L,
            0x510E527FADE682D1L, 0x9B05688C2B3E6C1FL,
            0x1F83D9ABFB41BD6BL, 0x5BE0CD19137E2179L,
        )

        /** BLAKE2b message-word permutation schedule; rounds 10 and 11 repeat 0 and 1. */
        private val SIGMA = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        )

        /** One-shot convenience for the common 64-byte digest. */
        fun digest64(input: ByteArray): ByteArray = Blake2b(DIGEST_LENGTH).update(input).digest()

        fun digest(input: ByteArray, length: Int): ByteArray = Blake2b(length).update(input).digest()

        fun keyedDigest(key: ByteArray, input: ByteArray, length: Int): ByteArray =
            Blake2b(length, key).update(input).digest()
    }
}

/**
 * `H'` from RFC 9106 §3.3: the variable-length BLAKE2b construction Argon2
 * uses for the seed hash, for the first two blocks of every lane, and for the
 * final tag. Mirrors the reference `blake2b_long()` byte for byte.
 */
internal object Blake2bLong {

    fun hash(outputLength: Int, input: ByteArray, inputOffset: Int = 0, inputLength: Int = input.size - inputOffset): ByteArray {
        require(outputLength > 0) { "output length must be positive" }
        val out = ByteArray(outputLength)
        hashInto(out, input, inputOffset, inputLength)
        return out
    }

    fun hashInto(out: ByteArray, input: ByteArray, inputOffset: Int = 0, inputLength: Int = input.size - inputOffset) {
        val outLen = out.size
        val lengthPrefix = byteArrayOf(
            (outLen and 0xFF).toByte(),
            ((outLen ushr 8) and 0xFF).toByte(),
            ((outLen ushr 16) and 0xFF).toByte(),
            ((outLen ushr 24) and 0xFF).toByte(),
        )

        if (outLen <= Blake2b.DIGEST_LENGTH) {
            val state = Blake2b(outLen)
            state.update(lengthPrefix)
            state.update(input, inputOffset, inputLength)
            state.digest().copyInto(out)
            return
        }

        // Long output: chain 64-byte digests, emitting the first 32 bytes of each.
        var outBuffer = Blake2b(Blake2b.DIGEST_LENGTH)
            .update(lengthPrefix)
            .update(input, inputOffset, inputLength)
            .digest()

        var written = 0
        outBuffer.copyInto(out, 0, 0, Blake2b.DIGEST_LENGTH / 2)
        written += Blake2b.DIGEST_LENGTH / 2

        var toProduce = outLen - Blake2b.DIGEST_LENGTH / 2
        while (toProduce > Blake2b.DIGEST_LENGTH) {
            val previous = outBuffer
            outBuffer = Blake2b(Blake2b.DIGEST_LENGTH).update(previous).digest()
            outBuffer.copyInto(out, written, 0, Blake2b.DIGEST_LENGTH / 2)
            written += Blake2b.DIGEST_LENGTH / 2
            toProduce -= Blake2b.DIGEST_LENGTH / 2
        }

        val finalDigest = Blake2b(toProduce).update(outBuffer).digest()
        finalDigest.copyInto(out, written, 0, toProduce)
    }
}

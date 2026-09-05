package com.ngi.sarothi.core.crypto

/**
 * Argon2id, exactly as specified in RFC 9106.
 *
 * Implemented in Kotlin on purpose (see [Blake2b] for why BouncyCastle is avoided
 * on Android). Correctness is not taken on faith: `Argon2Test` asserts the
 * official RFC 9106 §5.3 vectors — the H0 pre-hashing digest, two intermediate
 * memory blocks after each pass, and the final tag — so a bug in the block
 * permutation, the segment indexing or the H' construction fails the build.
 *
 * Only Argon2id (type 2) is exposed: Sarothi's master key must resist both
 * time-space trade-off and side-channel attacks, and RFC 9106 §7.4 names
 * Argon2id the FIRST RECOMMENDED variant.
 */
class Argon2id(
    /** Memory cost in kibibytes. RFC 9106 requires at least 8 * parallelism. */
    val memoryKiB: Int,
    /** Number of passes over the memory. */
    val iterations: Int,
    /** Degree of parallelism (lanes). Sarothi computes sequentially, so 1 lane. */
    val parallelism: Int,
    /** Protocol version; RFC 9106 mandates 0x13. */
    val version: Int = VERSION,
) {
    init {
        require(parallelism >= 1) { "parallelism must be >= 1" }
        require(iterations >= 1) { "iterations must be >= 1" }
        require(memoryKiB >= 8 * parallelism) {
            "memory must be at least 8*parallelism KiB (RFC 9106 §3.1)"
        }
        require(version == VERSION) { "only Argon2 version 0x13 is supported" }
    }

    /** Number of 1024-byte blocks actually allocated (m' = 4*p*floor(m/(4p))). */
    val memoryBlocks: Int

    /** Blocks per lane (q). */
    val laneLength: Int

    /** Blocks per segment (q / SL). */
    val segmentLength: Int

    init {
        var blocks = memoryKiB
        if (blocks < 2 * SYNC_POINTS * parallelism) blocks = 2 * SYNC_POINTS * parallelism
        var lane = blocks / parallelism
        lane -= lane % SYNC_POINTS
        laneLength = lane
        memoryBlocks = lane * parallelism
        segmentLength = laneLength / SYNC_POINTS
    }

    /**
     * Derives [outputLength] bytes of key material.
     *
     * @param secret optional pepper (K). Sarothi passes none: passphrase + the
     *   per-vault salt is the whole secret, which is precisely what lets the same
     *   password open the vault on a different device with no device-bound input.
     * @param associatedData optional AD (X).
     */
    fun deriveKey(
        password: ByteArray,
        salt: ByteArray,
        outputLength: Int = DEFAULT_OUTPUT_LENGTH,
        secret: ByteArray = EMPTY,
        associatedData: ByteArray = EMPTY,
    ): ByteArray {
        require(outputLength in 4..MAX_TAG_LENGTH) { "tag length must be in 4..$MAX_TAG_LENGTH" }
        require(salt.size >= MIN_SALT_LENGTH) {
            "salt must be at least $MIN_SALT_LENGTH bytes (RFC 9106 §4)"
        }

        val h0 = initialHash(password, salt, outputLength, secret, associatedData)

        // Flat block array: block b occupies words [b*128, b*128+128).
        val memory = LongArray(memoryBlocks * BLOCK_WORDS)

        // RFC 9106 §3.2 steps 3-4: the first two blocks of every lane come from H'.
        val seedInput = ByteArray(Blake2b.DIGEST_LENGTH + 8)
        val blockBytes = ByteArray(BLOCK_LENGTH)
        h0.copyInto(seedInput)
        for (lane in 0 until parallelism) {
            putLe32(seedInput, Blake2b.DIGEST_LENGTH, 0)
            putLe32(seedInput, Blake2b.DIGEST_LENGTH + 4, lane)
            Blake2bLong.hashInto(blockBytes, seedInput)
            loadBlock(memory, lane * laneLength, blockBytes)

            putLe32(seedInput, Blake2b.DIGEST_LENGTH, 1)
            putLe32(seedInput, Blake2b.DIGEST_LENGTH + 4, lane)
            Blake2bLong.hashInto(blockBytes, seedInput)
            loadBlock(memory, lane * laneLength + 1, blockBytes)
        }

        // Steps 5-6: slice-wise, so every sync point is honoured even though lanes
        // are computed sequentially rather than in parallel threads.
        val zeroBlock = LongArray(BLOCK_WORDS)
        val inputBlock = LongArray(BLOCK_WORDS)
        val tempBlock = LongArray(BLOCK_WORDS)
        val addressBlock = LongArray(BLOCK_WORDS)
        val pseudoRands = LongArray(segmentLength)

        for (pass in 0 until iterations) {
            for (slice in 0 until SYNC_POINTS) {
                for (lane in 0 until parallelism) {
                    val dataIndependent = usesArgon2iAddressing(pass, slice)
                    if (dataIndependent) {
                        generateAddresses(
                            pass, slice, lane,
                            zeroBlock, inputBlock, tempBlock, addressBlock, pseudoRands,
                        )
                    }
                    fillSegment(pass, slice, lane, dataIndependent, pseudoRands, memory)
                }
            }
        }

        // Step 7: C = XOR of the last block of every lane.
        val finalBlock = LongArray(BLOCK_WORDS)
        for (lane in 0 until parallelism) {
            val offset = (lane * laneLength + laneLength - 1) * BLOCK_WORDS
            for (word in 0 until BLOCK_WORDS) {
                finalBlock[word] = finalBlock[word] xor memory[offset + word]
            }
        }

        // Step 8: tag = H'^T(C)
        val finalBytes = ByteArray(BLOCK_LENGTH)
        storeBlock(finalBlock, finalBytes)
        val tag = ByteArray(outputLength)
        Blake2bLong.hashInto(tag, finalBytes)
        return tag
    }

    /** H0 from RFC 9106 §3.2 step 1. */
    fun initialHash(
        password: ByteArray,
        salt: ByteArray,
        outputLength: Int,
        secret: ByteArray = EMPTY,
        associatedData: ByteArray = EMPTY,
    ): ByteArray {
        val header = ByteArray(28)
        putLe32(header, 0, parallelism)
        putLe32(header, 4, outputLength)
        putLe32(header, 8, memoryKiB)
        putLe32(header, 12, iterations)
        putLe32(header, 16, version)
        putLe32(header, 20, TYPE_ID)
        putLe32(header, 24, password.size)

        return Blake2b(Blake2b.DIGEST_LENGTH)
            .update(header)
            .update(password)
            .update(le32(salt.size)).update(salt)
            .update(le32(secret.size)).update(secret)
            .update(le32(associatedData.size)).update(associatedData)
            .digest()
    }

    /**
     * Argon2id uses Argon2i's data-independent addressing only for the first two
     * slices of the first pass (RFC 9106 §3.4.1.3).
     */
    private fun usesArgon2iAddressing(pass: Int, slice: Int): Boolean =
        pass == 0 && slice < SYNC_POINTS / 2

    /**
     * Produces the pseudo-random values that select reference blocks for a
     * data-independent segment: G(ZERO, G(ZERO, Z||LE64(i)||ZERO)) counter chain.
     */
    private fun generateAddresses(
        pass: Int,
        slice: Int,
        lane: Int,
        zeroBlock: LongArray,
        inputBlock: LongArray,
        tempBlock: LongArray,
        addressBlock: LongArray,
        output: LongArray,
    ) {
        inputBlock.fill(0L)
        inputBlock[0] = pass.toLong()
        inputBlock[1] = lane.toLong()
        inputBlock[2] = slice.toLong()
        inputBlock[3] = memoryBlocks.toLong()
        inputBlock[4] = iterations.toLong()
        inputBlock[5] = TYPE_ID.toLong()
        // inputBlock[6] is the counter; it is incremented before each address block.

        for (index in 0 until segmentLength) {
            if (index % ADDRESSES_PER_BLOCK == 0) {
                inputBlock[6] += 1
                compressStandalone(zeroBlock, inputBlock, tempBlock)
                compressStandalone(zeroBlock, tempBlock, addressBlock)
            }
            output[index] = addressBlock[index % ADDRESSES_PER_BLOCK]
        }
    }

    private fun fillSegment(
        pass: Int,
        slice: Int,
        lane: Int,
        dataIndependent: Boolean,
        pseudoRands: LongArray,
        memory: LongArray,
    ) {
        val segmentStart = slice * segmentLength
        // Blocks 0 and 1 of a lane on pass 0 are seeded from H', not compressed.
        val startingIndex = if (pass == 0 && slice == 0) 2 else 0

        for (index in startingIndex until segmentLength) {
            val positionInLane = segmentStart + index
            val currentBlock = lane * laneLength + positionInLane
            val previousBlock =
                lane * laneLength + if (positionInLane == 0) laneLength - 1 else positionInLane - 1

            val pseudoRand = if (dataIndependent) {
                pseudoRands[index]
            } else {
                // Argon2d/Argon2id data-dependent mode: J1||J2 are the first 64
                // bits of the previous block.
                memory[previousBlock * BLOCK_WORDS]
            }

            val refLane = if (pass == 0 && slice == 0) {
                lane
            } else {
                (((pseudoRand ushr 32) and 0xFFFFFFFFL) % parallelism).toInt()
            }
            val refIndex = indexAlpha(pass, slice, index, pseudoRand and 0xFFFFFFFFL, refLane == lane)
            val referenceBlock = refLane * laneLength + refIndex

            compressInPlace(memory, previousBlock, referenceBlock, currentBlock, withXor = pass != 0)
        }
    }

    /** RFC 9106 §3.4.2: maps J1 to an index inside the referenceable window W. */
    private fun indexAlpha(pass: Int, slice: Int, index: Int, j1: Long, sameLane: Boolean): Int {
        val referenceAreaSize: Long = if (pass == 0) {
            when {
                slice == 0 -> (index - 1).toLong()
                sameLane -> (slice * segmentLength + index - 1).toLong()
                else -> (slice * segmentLength + if (index == 0) -1 else 0).toLong()
            }
        } else {
            if (sameLane) {
                (laneLength - segmentLength + index - 1).toLong()
            } else {
                (laneLength - segmentLength + if (index == 0) -1 else 0).toLong()
            }
        }

        val area = referenceAreaSize and 0xFFFFFFFFL
        var relative = j1 and 0xFFFFFFFFL
        relative = (relative * relative) ushr 32
        // area*relative can exceed Long.MAX_VALUE; the bit pattern is still the
        // correct unsigned product, and `ushr` shifts it logically. The result of
        // the subtraction is provably >= 0 because floor(area*rel/2^32) <= area-1.
        relative = (area - 1) - ((area * relative) ushr 32)

        val startPosition = if (pass != 0) {
            if (slice == SYNC_POINTS - 1) 0 else (slice + 1) * segmentLength
        } else {
            0
        }
        return ((startPosition + relative) % laneLength).toInt()
    }

    /** G over two blocks in the flat [memory] array, writing into [destIndex]. */
    private fun compressInPlace(
        memory: LongArray,
        prevIndex: Int,
        refIndex: Int,
        destIndex: Int,
        withXor: Boolean,
    ) {
        val prevBase = prevIndex * BLOCK_WORDS
        val refBase = refIndex * BLOCK_WORDS
        val destBase = destIndex * BLOCK_WORDS

        // R = ref XOR prev. It is kept unpermuted because the output is R XOR P(R).
        val r = scratch.get()
        val permuted = scratch2.get()
        for (i in 0 until BLOCK_WORDS) r[i] = memory[refBase + i] xor memory[prevBase + i]
        System.arraycopy(r, 0, permuted, 0, BLOCK_WORDS)
        permute(permuted)

        for (i in 0 until BLOCK_WORDS) {
            var value = r[i] xor permuted[i]
            if (withXor) value = value xor memory[destBase + i]
            memory[destBase + i] = value
        }
    }

    /** G over standalone arrays, used only by the address-generation chain. */
    private fun compressStandalone(prev: LongArray, ref: LongArray, dest: LongArray) {
        val r = scratch.get()
        val permuted = scratch2.get()
        for (i in 0 until BLOCK_WORDS) r[i] = ref[i] xor prev[i]
        System.arraycopy(r, 0, permuted, 0, BLOCK_WORDS)
        permute(permuted)
        for (i in 0 until BLOCK_WORDS) dest[i] = r[i] xor permuted[i]
    }

    /**
     * The P permutation of RFC 9106 §3.5: the BLAKE2b round function (without
     * message words) applied to each 128-byte row, then to each 128-byte column.
     */
    private fun permute(block: LongArray) {
        for (row in 0 until 8) {
            val base = row * 16
            round(
                block,
                base, base + 1, base + 2, base + 3,
                base + 4, base + 5, base + 6, base + 7,
                base + 8, base + 9, base + 10, base + 11,
                base + 12, base + 13, base + 14, base + 15,
            )
        }
        val columns = columnScratch.get()
        for (column in 0 until 8) {
            val start = column * 2
            for (k in 0 until 8) {
                columns[k * 2] = block[start + k * 16]
                columns[k * 2 + 1] = block[start + k * 16 + 1]
            }
            round(columns, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
            for (k in 0 until 8) {
                block[start + k * 16] = columns[k * 2]
                block[start + k * 16 + 1] = columns[k * 2 + 1]
            }
        }
    }

    /** One BLAKE2b round over 16 words addressed indirectly through [state]. */
    private fun round(
        state: LongArray,
        i0: Int, i1: Int, i2: Int, i3: Int,
        i4: Int, i5: Int, i6: Int, i7: Int,
        i8: Int, i9: Int, i10: Int, i11: Int,
        i12: Int, i13: Int, i14: Int, i15: Int,
    ) {
        mix(state, i0, i4, i8, i12)
        mix(state, i1, i5, i9, i13)
        mix(state, i2, i6, i10, i14)
        mix(state, i3, i7, i11, i15)
        mix(state, i0, i5, i10, i15)
        mix(state, i1, i6, i11, i12)
        mix(state, i2, i7, i8, i13)
        mix(state, i3, i4, i9, i14)
    }

    /** Argon2's G mixing function (BLAKE2b's, with the fBlaMka multiply step). */
    private fun mix(state: LongArray, ia: Int, ib: Int, ic: Int, id: Int) {
        var a = state[ia]
        var b = state[ib]
        var c = state[ic]
        var d = state[id]

        a = blaMka(a, b)
        d = (d xor a).rotateRight(32)
        c = blaMka(c, d)
        b = (b xor c).rotateRight(24)
        a = blaMka(a, b)
        d = (d xor a).rotateRight(16)
        c = blaMka(c, d)
        b = (b xor c).rotateRight(63)

        state[ia] = a
        state[ib] = b
        state[ic] = c
        state[id] = d
    }

    /** fBlaMka(a,b) = a + b + 2 * trunc(a) * trunc(b), wrapping at 64 bits as in C. */
    private fun blaMka(a: Long, b: Long): Long {
        val lowA = a and 0xFFFFFFFFL
        val lowB = b and 0xFFFFFFFFL
        return a + b + 2 * lowA * lowB
    }

    /** Little-endian 1024 bytes -> 128 words, written into the flat memory array. */
    private fun loadBlock(memory: LongArray, blockIndex: Int, bytes: ByteArray) {
        val base = blockIndex * BLOCK_WORDS
        for (i in 0 until BLOCK_WORDS) {
            var word = 0L
            for (byte in 0 until 8) {
                word = word or ((bytes[i * 8 + byte].toLong() and 0xFFL) shl (8 * byte))
            }
            memory[base + i] = word
        }
    }

    private fun storeBlock(words: LongArray, destination: ByteArray) {
        for (i in words.indices) {
            val value = words[i]
            for (byte in 0 until 8) {
                destination[i * 8 + byte] = ((value ushr (8 * byte)) and 0xFFL).toByte()
            }
        }
    }

    private fun putLe32(destination: ByteArray, offset: Int, value: Int) {
        destination[offset] = (value and 0xFF).toByte()
        destination[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        destination[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        destination[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun le32(value: Int): ByteArray = ByteArray(4).also { putLe32(it, 0, value) }

    // Reused scratch buffers: Argon2 allocates one 1024-byte block per G call in
    // the reference C code, and on a 3 GB phone that GC pressure is measurable.
    private val scratch = ThreadLocal.withInitial { LongArray(BLOCK_WORDS) }
    private val scratch2 = ThreadLocal.withInitial { LongArray(BLOCK_WORDS) }
    private val columnScratch = ThreadLocal.withInitial { LongArray(16) }

    companion object {
        const val VERSION = 0x13
        const val TYPE_ID = 2 // Argon2id
        const val SYNC_POINTS = 4
        const val BLOCK_LENGTH = 1024
        const val BLOCK_WORDS = BLOCK_LENGTH / 8
        const val ADDRESSES_PER_BLOCK = 128
        const val MIN_SALT_LENGTH = 8
        const val MAX_TAG_LENGTH = 1024
        const val DEFAULT_OUTPUT_LENGTH = 32
        private val EMPTY = ByteArray(0)

        /**
         * Sarothi's default parameters.
         *
         * RFC 9106 §7.4 recommends t=1 with 2 GiB, or t=3 with 64 MiB for
         * memory-constrained environments. Sarothi targets phones with 3 GB of
         * *total* RAM, where a 64 MiB scratch allocation during unlock makes the
         * process a prime low-memory-kill target. The default therefore matches
         * OWASP's Argon2id minimum of m=12 MiB, t=3, p=1 — still memory-hard,
         * with a bounded 12 MiB peak. The chosen values are recorded in
         * manifest.json so verification always uses the parameters this vault was
         * created with, and users can raise them on capable hardware.
         */
        const val DEFAULT_MEMORY_KIB = 12 * 1024
        const val DEFAULT_ITERATIONS = 3
        const val DEFAULT_PARALLELISM = 1

        fun default(): Argon2id = Argon2id(DEFAULT_MEMORY_KIB, DEFAULT_ITERATIONS, DEFAULT_PARALLELISM)
    }
}

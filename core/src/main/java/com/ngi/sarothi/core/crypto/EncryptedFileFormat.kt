package com.ngi.sarothi.core.crypto

/**
 * On-disk layout for every file under the vault's `/memories/` tree.
 *
 * ```
 * offset  size  field
 * 0       4     magic   "SRT1"
 * 4       1     format version
 * 5       1     flags   (bit 0: gzip-compressed plaintext before encryption)
 * 6       12    GCM nonce
 * 18      ...   ciphertext || 16-byte GCM tag
 * ```
 *
 * The salt and KDF parameters are deliberately *not* in this header: they live in
 * the vault's plaintext `manifest.json`, exactly as the storage spec requires.
 * Model files under `/models/` are never wrapped in this format — they are not
 * secret and paying the decrypt cost on a 200 MB GGUF would be pointless.
 */
object EncryptedFileFormat {

    const val MAGIC = "SRT1"
    const val FORMAT_VERSION: Byte = 1
    const val FLAG_COMPRESSED: Byte = 0x01
    const val HEADER_LENGTH = 4 + 1 + 1 + AesGcm.NONCE_LENGTH_BYTES

    fun magicBytes(): ByteArray = MAGIC.toByteArray(Charsets.US_ASCII)

    /** Additional authenticated data: binds a ciphertext to its vault-relative path. */
    fun associatedDataFor(vaultRelativePath: String): ByteArray =
        "$MAGIC|$FORMAT_VERSION|$vaultRelativePath".toByteArray(Charsets.UTF_8)

    fun seal(
        key: ByteArray,
        vaultRelativePath: String,
        plaintext: ByteArray,
        compress: Boolean = true,
    ): ByteArray {
        val payload = if (compress) deflate(plaintext) else plaintext
        val flags: Byte = if (compress) FLAG_COMPRESSED else 0
        val blob = AesGcm.encrypt(key, payload, associatedDataFor(vaultRelativePath))

        val out = ByteArray(HEADER_LENGTH + blob.ciphertext.size)
        magicBytes().copyInto(out, 0)
        out[4] = FORMAT_VERSION
        out[5] = flags
        blob.nonce.copyInto(out, 6)
        blob.ciphertext.copyInto(out, HEADER_LENGTH)
        return out
    }

    /**
     * @throws IllegalArgumentException on a malformed or foreign header.
     * @throws javax.crypto.AEADBadTagException on a wrong key or tampered content.
     */
    fun open(key: ByteArray, vaultRelativePath: String, sealed: ByteArray): ByteArray {
        require(sealed.size >= HEADER_LENGTH) {
            "File '$vaultRelativePath' is too small to be a Sarothi encrypted file (${sealed.size} bytes)"
        }
        val magic = sealed.copyOfRange(0, 4).toString(Charsets.US_ASCII)
        require(magic == MAGIC) {
            "File '$vaultRelativePath' is not a Sarothi encrypted file (magic '$magic', expected '$MAGIC'). " +
                "If this file came from another app, it cannot be read here."
        }
        val version = sealed[4]
        require(version.toInt() == FORMAT_VERSION.toInt()) {
            "File '$vaultRelativePath' uses format version $version; this build understands $FORMAT_VERSION."
        }
        val flags = sealed[5]
        val nonce = sealed.copyOfRange(6, 6 + AesGcm.NONCE_LENGTH_BYTES)
        val ciphertext = sealed.copyOfRange(HEADER_LENGTH, sealed.size)

        val plaintext = AesGcm.decrypt(key, nonce, ciphertext, associatedDataFor(vaultRelativePath))
        return if (flags.toInt() and FLAG_COMPRESSED.toInt() != 0) inflate(plaintext) else plaintext
    }

    /** True when the bytes look like a Sarothi sealed file (used by restore detection). */
    fun isSealed(data: ByteArray): Boolean =
        data.size >= HEADER_LENGTH && data.copyOfRange(0, 4).toString(Charsets.US_ASCII) == MAGIC

    private fun deflate(input: ByteArray): ByteArray =
        java.io.ByteArrayOutputStream(input.size).use { buffer ->
            java.util.zip.DeflaterOutputStream(buffer, java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED))
                .use { it.write(input) }
            buffer.toByteArray()
        }

    private fun inflate(input: ByteArray): ByteArray =
        java.io.ByteArrayOutputStream(input.size * 2).use { buffer ->
            java.util.zip.InflaterInputStream(input.inputStream()).use { it.copyTo(buffer) }
            buffer.toByteArray()
        }
}

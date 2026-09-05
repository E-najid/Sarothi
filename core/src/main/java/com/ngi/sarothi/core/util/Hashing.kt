package com.ngi.sarothi.core.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Streaming SHA-256 helpers used by the model downloader and the vault verifier. */
object Hashing {

    private const val BUFFER_SIZE = 64 * 1024

    fun sha256Hex(bytes: ByteArray): String = digest().let { md ->
        md.update(bytes)
        md.digest().toHex()
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /** Hashes a whole file without ever holding it in memory — models are hundreds of MB. */
    fun sha256Hex(file: File, onProgress: ((Long) -> Unit)? = null): String {
        file.inputStream().use { input -> return sha256Hex(input, file.length(), onProgress) }
    }

    fun sha256Hex(input: InputStream, knownLength: Long = -1L, onProgress: ((Long) -> Unit)? = null): String {
        val md = digest()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            md.update(buffer, 0, read)
            total += read
            onProgress?.invoke(total)
        }
        return md.digest().toHex()
    }

    /** A MessageDigest that can be fed incrementally while a download streams to disk. */
    fun newSha256(): MessageDigest = digest()

    private fun digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xFF
            if (value < 0x10) append('0')
            append(value.toString(16))
        }
    }

    /**
     * Git's blob digest: SHA-1 over `"blob <length>\0<content>"`.
     *
     * Hugging Face publishes a SHA-256 (the LFS object id) only for Git-LFS files.
     * Small non-LFS files such as a Piper voice's `.onnx.json` config get a Git
     * blob SHA-1 instead, which is still a real digest of the exact bytes — so
     * Sarothi can verify them too rather than falling back to a bare size check.
     */
    fun gitBlobSha1(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        val prefix = "blob ${file.length()}\u0000".toByteArray(Charsets.US_ASCII)
        md.update(prefix)
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().toHex()
    }

    /** Same, for data already in memory. */
    fun gitBlobSha1(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
        md.update(bytes)
        return md.digest().toHex()
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun constantTimeEqualsHex(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
}

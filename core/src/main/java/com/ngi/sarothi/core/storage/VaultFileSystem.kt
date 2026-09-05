package com.ngi.sarothi.core.storage

import java.io.InputStream
import java.io.OutputStream

/** One entry in a vault directory listing. */
data class VaultEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long,
)

/**
 * Filesystem abstraction over the user-chosen vault folder.
 *
 * Everything in Sarothi addresses the vault through this interface with
 * forward-slash relative paths (`memories/notes.json`). The production
 * implementation is [SafVaultFileSystem], which resolves those paths against a
 * Storage Access Framework tree URI. Keeping the interface path-based means the
 * rest of the codebase never handles `content://` URIs, and unit tests can run
 * against an in-memory or plain-`java.io.File` implementation.
 */
interface VaultFileSystem {

    /** Human-readable location, for the settings screen. Never a secret. */
    val displayName: String

    fun exists(path: String): Boolean

    fun isDirectory(path: String): Boolean

    /** Creates [path] and any missing parents. Idempotent. */
    fun createDirectories(path: String)

    fun readFile(path: String): ByteArray

    /**
     * Writes [bytes] to [path] crash-safely: the payload lands in a temporary file
     * in the same directory, then the previous file is removed and the temporary is
     * renamed into place. A power cut therefore leaves either the old file or the
     * new one, never a half-written mix.
     */
    fun writeFile(path: String, bytes: ByteArray)

    fun deleteFile(path: String): Boolean

    fun listFiles(directory: String): List<VaultEntry>

    fun fileSize(path: String): Long

    fun lastModified(path: String): Long

    /**
     * Streaming write, used by the model downloader.
     *
     * @param append when true the stream continues after the existing bytes, which
     *   is what makes an interrupted download resumable instead of restarting.
     */
    fun openOutputStream(path: String, append: Boolean = false): OutputStream

    fun openInputStream(path: String): InputStream

    /**
     * Renames/moves a file inside the vault. Returns false when the provider
     * refuses (some SD-card providers implement rename as copy+delete and can fail
     * on large files); callers must handle that instead of assuming success.
     */
    fun rename(oldPath: String, newName: String): Boolean

    /**
     * Content URI of a vault file, or null when this filesystem has no URI concept.
     *
     * Native libraries (llama.cpp, whisper.cpp, ONNX Runtime) cannot open
     * `content://` URIs at all — they need a POSIX path. Sarothi therefore opens a
     * file descriptor from this URI and hands native code `/proc/self/fd/N`, which
     * is a real seekable path to the same bytes, falling back to a private-storage
     * copy when a provider refuses. See `VaultModelFile`.
     */
    fun contentUriFor(path: String): String?

    /** Absolute path when this filesystem is backed by real files, otherwise null. */
    fun absolutePathFor(path: String): String? = null
}

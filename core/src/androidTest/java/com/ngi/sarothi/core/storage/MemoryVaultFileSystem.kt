package com.ngi.sarothi.core.storage

import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * A [VaultFileSystem] held in a map, so vault lifecycle code can be exercised on a device
 * without a Storage Access Framework tree URI -- which cannot be obtained without a person
 * tapping through the system folder picker.
 *
 * This is a test double for a seam the interface documents for exactly this purpose, not a
 * substitute implementation shipping in the app: production goes through
 * [SafVaultFileSystem] and the real `content://` permissions, and the instrumented suite
 * that covers those is `SecretStoreInstrumentedTest` and the manifest suite. What lives
 * here is the path algebra -- relative paths, directory listings, append streams, rename
 * within a directory -- which is the part vault rotation depends on and the part a
 * `content://` URI makes impossible to set up mid-flight.
 *
 * Behaviour matches the contract in [VaultFileSystem] rather than being convenient:
 * [writeFile] replaces whole files, [openOutputStream] with `append` continues after the
 * existing bytes, [rename] takes a leaf name inside the same directory and returns false
 * when there is nothing to move, and reading a path that is not there throws instead of
 * returning an empty array.
 */
internal class MemoryVaultFileSystem(
    override val displayName: String = "memory://sarothi-vault",
) : VaultFileSystem {

    private val files = LinkedHashMap<String, ByteArray>()
    private val directories = LinkedHashSet<String>()
    private val modifiedAt = HashMap<String, Long>()

    /** Every path currently held, for assertions about what a run left behind. */
    fun paths(): Set<String> = files.keys.toSet() + directories

    /** A copy of the bytes at [path], for asserting that a run left them alone. */
    fun raw(path: String): ByteArray =
        files[path]?.copyOf() ?: throw FileNotFoundException("no vault file at '$path'")

    override fun exists(path: String): Boolean = files.containsKey(path) || directories.contains(path)

    override fun isDirectory(path: String): Boolean = directories.contains(path)

    override fun createDirectories(path: String) {
        val normalised = path.trim('/')
        if (normalised.isEmpty()) return
        val parts = normalised.split('/')
        for (index in parts.indices) {
            directories += parts.subList(0, index + 1).joinToString("/")
        }
    }

    override fun readFile(path: String): ByteArray =
        files[path]?.copyOf() ?: throw FileNotFoundException("no vault file at '$path'")

    override fun writeFile(path: String, bytes: ByteArray) {
        parentOf(path)?.let { createDirectories(it) }
        files[path] = bytes.copyOf()
        modifiedAt[path] = System.currentTimeMillis()
    }

    override fun deleteFile(path: String): Boolean {
        modifiedAt.remove(path)
        return files.remove(path) != null || directories.remove(path)
    }

    override fun listFiles(directory: String): List<VaultEntry> {
        val prefix = directory.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val names = LinkedHashSet<String>()
        val entries = mutableListOf<VaultEntry>()

        fun add(path: String, isDirectory: Boolean) {
            if (!path.startsWith(prefix) || path == prefix.trimEnd('/')) return
            val remainder = path.removePrefix(prefix)
            if (remainder.isEmpty()) return
            // A nested path contributes its first segment as a directory entry once.
            val segment = remainder.substringBefore('/')
            val childPath = prefix + segment
            if (!names.add(childPath)) return
            entries += VaultEntry(
                name = segment,
                isDirectory = isDirectory || remainder.contains('/'),
                sizeBytes = files[childPath]?.size?.toLong() ?: 0L,
                lastModifiedEpochMillis = modifiedAt[childPath] ?: 0L,
            )
        }

        files.keys.forEach { add(it, false) }
        directories.forEach { add(it, true) }
        return entries
    }

    override fun fileSize(path: String): Long = files[path]?.size?.toLong() ?: -1L

    override fun lastModified(path: String): Long = modifiedAt[path] ?: 0L

    override fun openOutputStream(path: String, append: Boolean): OutputStream {
        val already = if (append) files[path]?.copyOf() ?: ByteArray(0) else ByteArray(0)
        return object : ByteArrayOutputStream() {
            override fun close() {
                writeFile(path, already + toByteArray())
                super.close()
            }
        }
    }

    override fun openInputStream(path: String): InputStream =
        files[path]?.inputStream() ?: throw FileNotFoundException("no vault file at '$path'")

    override fun rename(oldPath: String, newName: String): Boolean {
        val bytes = files[oldPath] ?: return false
        val parent = parentOf(oldPath)
        val target = if (parent == null) newName.trim('/') else "$parent/${newName.trim('/')}"
        files.remove(oldPath)
        modifiedAt.remove(oldPath)
        writeFile(target, bytes)
        return true
    }

    override fun contentUriFor(path: String): String? = null

    private fun parentOf(path: String): String? {
        val trimmed = path.trim('/')
        val index = trimmed.lastIndexOf('/')
        return if (index <= 0) null else trimmed.substring(0, index)
    }
}

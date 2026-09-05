package com.ngi.sarothi.core.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Process
import androidx.documentfile.provider.DocumentFile
import com.ngi.sarothi.core.error.VaultNotInitializedException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * [VaultFileSystem] over a Storage Access Framework tree URI.
 *
 * The URI comes from `ACTION_OPEN_DOCUMENT_TREE` and must have been granted with
 * `takePersistableUriPermission(...)` — [com.ngi.sarothi.core.storage.VaultManager]
 * does that when the folder is chosen, which is what makes the vault survive a
 * reboot, an app restart and an app update.
 *
 * All access goes through `DocumentFile`/`ContentResolver`. Sarothi never converts
 * the tree URI to a `java.io.File` path: on scoped storage that path is not
 * writable, and pretending otherwise would produce failures that look like data
 * loss.
 */
class SafVaultFileSystem(
    private val context: Context,
    val treeUri: Uri,
) : VaultFileSystem {

    private val resolver: ContentResolver = context.contentResolver
    private val root: DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw VaultNotInitializedException(
                "The chosen storage folder ($treeUri) could not be opened. It may have been " +
                    "unmounted, or Sarothi's access to it was revoked in system settings.",
            )

    private val tempCounter = AtomicLong(0)

    override val displayName: String = root.name ?: treeUri.toString()

    init {
        if (!root.exists() || !root.canRead() || !root.canWrite()) {
            throw VaultNotInitializedException(
                "Sarothi does not have read+write access to the chosen folder ($displayName). " +
                    "Re-pick the folder, or grant 'All files access' to the provider if it needs it.",
            )
        }
    }

    // ------------------------------------------------------------------ paths

    private fun resolve(path: String): DocumentFile? {
        if (path.isEmpty() || path == "/") return root
        var current = root
        for (segment in path.split('/').filter { it.isNotEmpty() && it != "." }) {
            if (segment == "..") {
                throw IllegalArgumentException("Vault paths must not escape the vault root: '$path'")
            }
            val child = current.findFile(segment) ?: return null
            current = child
        }
        return current
    }

    private fun resolveParentDirectory(path: String, create: Boolean): DocumentFile {
        val parentPath = VaultPaths.parentDir(path)
        if (parentPath.isEmpty()) return root
        val existing = resolve(parentPath)
        if (existing != null) {
            if (existing.isDirectory) return existing
            throw IllegalStateException("'$parentPath' exists but is not a directory")
        }
        if (!create) {
            throw VaultNotInitializedException("Vault directory '$parentPath' does not exist")
        }
        createDirectories(parentPath)
        return resolve(parentPath)
            ?: throw IllegalStateException("Failed to create vault directory '$parentPath'")
    }

    /**
     * Creates a file and defends against providers that append an extension based
     * on the MIME type: if the resulting display name is not what we asked for, it
     * is renamed to the requested name.
     */
    private fun createFileIn(directory: DocumentFile, name: String): DocumentFile {
        val created = directory.createFile(OCTET_STREAM, name)
            ?: throw IllegalStateException("The storage provider refused to create '$name'")
        if (created.name != name) {
            val renamed = DocumentsContract.renameDocument(resolver, created.uri, name)
            if (renamed != null) {
                return DocumentFile.fromSingleUri(context, renamed)
                    ?: created
            }
        }
        return created
    }

    // ------------------------------------------------------------- interface

    override fun exists(path: String): Boolean = resolve(path)?.exists() == true

    override fun isDirectory(path: String): Boolean = resolve(path)?.isDirectory == true

    override fun createDirectories(path: String) {
        var current = root
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            val child = current.findFile(segment)
            current = when {
                child == null -> current.createDirectory(segment)
                    ?: throw IllegalStateException("Could not create vault directory '$segment'")
                child.isDirectory -> child
                else -> throw IllegalStateException(
                    "'$segment' already exists in the vault but is a file, not a directory",
                )
            }
        }
    }

    override fun readFile(path: String): ByteArray = openInputStream(path).use { it.readBytes() }

    override fun writeFile(path: String, bytes: ByteArray) {
        val name = VaultPaths.fileName(path)
        require(name.isNotEmpty()) { "Cannot write to the vault root" }
        val directory = resolveParentDirectory(path, create = true)

        val tempName = "$name.tmp-${Process.myPid()}-${tempCounter.incrementAndGet()}"
        val tempFile = createFileIn(directory, tempName)
        try {
            resolver.openOutputStream(tempFile.uri, "wt")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw IllegalStateException("Could not open '$tempName' for writing")

            val target = resolve(path)
            if (target != null) {
                // Rotate the current file aside first, so that a failed final rename
                // still leaves a recoverable copy instead of no file at all.
                val backupName = "$name.bak"
                val backupPath = VaultPaths.join(VaultPaths.parentDir(path), backupName)
                resolve(backupPath)?.delete()
                val renamedAside = DocumentsContract.renameDocument(resolver, target.uri, backupName)
                if (renamedAside == null) {
                    // This provider does not implement rename; fall back to
                    // delete-then-rename. The temp file still holds the new data.
                    if (!target.delete()) {
                        throw IllegalStateException("Could not replace '$path'")
                    }
                }
            }

            val moved = DocumentsContract.renameDocument(resolver, tempFile.uri, name)
            if (moved == null) {
                throw IllegalStateException(
                    "The storage provider could not finalise '$path'. The data is still present " +
                        "as '$tempName' in the same folder.",
                )
            }
            // Clean up the backup left by the rotation above.
            resolve(VaultPaths.join(VaultPaths.parentDir(path), "$name.bak"))?.delete()
        } catch (failure: Exception) {
            runCatching { tempFile.delete() }
            throw failure
        }
    }

    override fun deleteFile(path: String): Boolean = resolve(path)?.delete() ?: false

    override fun listFiles(directory: String): List<VaultEntry> {
        val target = resolve(directory) ?: return emptyList()
        if (!target.isDirectory) return emptyList()
        return target.listFiles().map { file ->
            VaultEntry(
                name = file.name ?: "",
                isDirectory = file.isDirectory,
                sizeBytes = file.length(),
                lastModifiedEpochMillis = file.lastModified(),
            )
        }.filter { it.name.isNotEmpty() }
    }

    override fun fileSize(path: String): Long = resolve(path)?.takeIf { it.isFile }?.length() ?: -1L

    override fun lastModified(path: String): Long = resolve(path)?.lastModified() ?: 0L

    override fun openOutputStream(path: String, append: Boolean): OutputStream {
        val existing = resolve(path)
        val document = if (existing != null && existing.isFile) {
            existing
        } else {
            if (existing != null && existing.isDirectory) {
                throw IllegalStateException("'$path' is a directory")
            }
            createFileIn(resolveParentDirectory(path, create = true), VaultPaths.fileName(path))
        }
        val mode = if (append) "wa" else "wt"
        return resolver.openOutputStream(document.uri, mode)
            ?: throw IllegalStateException("Could not open '$path' for writing (mode '$mode')")
    }

    override fun openInputStream(path: String): InputStream {
        val document = resolve(path)
            ?: throw VaultNotInitializedException("'$path' does not exist in the vault")
        return resolver.openInputStream(document.uri)
            ?: throw IllegalStateException("Could not open '$path' for reading")
    }

    override fun rename(oldPath: String, newName: String): Boolean {
        val document = resolve(oldPath) ?: return false
        // Rename changes the document's URI. Nothing in this class caches URIs
        // across calls, so there is no stale state to invalidate.
        return DocumentsContract.renameDocument(resolver, document.uri, newName) != null
    }

    /** URI of a vault path, for sharing or opening a file in another app. */
    fun uriFor(path: String): Uri? = resolve(path)?.uri

    override fun contentUriFor(path: String): String? = resolve(path)?.uri?.toString()

    companion object {
        private const val OCTET_STREAM = "application/octet-stream"
    }
}

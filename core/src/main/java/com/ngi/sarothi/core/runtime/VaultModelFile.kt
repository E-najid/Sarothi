package com.ngi.sarothi.core.runtime

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import com.ngi.sarothi.core.error.ModelNotInstalledException
import com.ngi.sarothi.core.storage.VaultFileSystem
import java.io.Closeable
import java.io.File

/** How a model file reached native code. Surfaced in the UI because it affects disk use. */
enum class NativeFileAccess {
    /** The vault is on a real filesystem, so the path was used directly. */
    DIRECT_PATH,

    /**
     * An open file descriptor on the SAF document, addressed as `/proc/self/fd/N`.
     * No copy is made and no extra disk is used; the descriptor must stay open for
     * as long as the model is loaded.
     */
    FILE_DESCRIPTOR,

    /**
     * The provider would not give a usable descriptor (some cloud-backed or
     * restrictive providers), so the model was copied into app-private storage.
     * This costs disk equal to the model size and does not survive an uninstall —
     * but the vault copy does, so the cache is rebuilt automatically.
     */
    PRIVATE_COPY,
}

/**
 * Makes a model that lives in the SAF vault loadable by native code.
 *
 * llama.cpp, whisper.cpp and ONNX Runtime all take a POSIX path; none of them can
 * open a `content://` URI. Rather than forcing the user to move models out of the
 * portable vault, Sarothi opens a `ParcelFileDescriptor` and passes
 * `/proc/self/fd/<n>`, which is a genuine seekable path to the same bytes and
 * therefore mmap-able. Only if that is impossible does it fall back to a private
 * copy — and it reports which path was taken, because the fallback uses real disk.
 */
class VaultModelFile private constructor(
    val vaultPath: String,
    /** Path safe to hand to a native `fopen()`. */
    val nativePath: String,
    val access: NativeFileAccess,
    val sizeBytes: Long,
    private val descriptor: ParcelFileDescriptor?,
    private val privateCopy: File?,
) : Closeable {

    override fun close() {
        runCatching { descriptor?.close() }
        // The private copy is deliberately kept: re-copying a 200 MB model on every
        // load would be slow and pointless. It is purged by [purgePrivateCopies].
    }

    /** Deletes a cached private copy, e.g. after the vault model is replaced. */
    fun deletePrivateCopy() {
        privateCopy?.delete()
    }

    companion object {

        private const val FD_PATH_PREFIX = "/proc/self/fd/"

        fun open(
            context: Context,
            fileSystem: VaultFileSystem,
            vaultPath: String,
            expectedSizeBytes: Long,
            onCopyProgress: ((Long, Long) -> Unit)? = null,
        ): VaultModelFile {
            if (!fileSystem.exists(vaultPath)) {
                throw ModelNotInstalledException(
                    modelId = vaultPath,
                    expectedFileName = vaultPath.substringAfterLast('/'),
                    reason = "the file is not present in the vault's models folder",
                )
            }

            // 1. A vault backed by real files (tests, or a user who granted
            //    all-files access) needs no descriptor at all.
            fileSystem.absolutePathFor(vaultPath)?.let { direct ->
                val file = File(direct)
                if (file.isFile && file.canRead()) {
                    return VaultModelFile(
                        vaultPath = vaultPath,
                        nativePath = direct,
                        access = NativeFileAccess.DIRECT_PATH,
                        sizeBytes = file.length(),
                        descriptor = null,
                        privateCopy = null,
                    )
                }
            }

            // 2. Open a descriptor on the SAF document and address it via /proc.
            val uriString = fileSystem.contentUriFor(vaultPath)
            if (uriString != null) {
                val descriptor = runCatching {
                    context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
                }.getOrNull()
                if (descriptor != null) {
                    val fdPath = FD_PATH_PREFIX + descriptor.fd
                    val probe = File(fdPath)
                    val length = probe.length()
                    if (probe.canRead() && (expectedSizeBytes <= 0L || length == expectedSizeBytes)) {
                        return VaultModelFile(
                            vaultPath = vaultPath,
                            nativePath = fdPath,
                            access = NativeFileAccess.FILE_DESCRIPTOR,
                            sizeBytes = length,
                            descriptor = descriptor,
                            privateCopy = null,
                        )
                    }
                    runCatching { descriptor.close() }
                }
            }

            // 3. Last resort: copy into app-private storage.
            return copyToPrivateStorage(context, fileSystem, vaultPath, expectedSizeBytes, onCopyProgress)
        }

        private fun copyToPrivateStorage(
            context: Context,
            fileSystem: VaultFileSystem,
            vaultPath: String,
            expectedSizeBytes: Long,
            onCopyProgress: ((Long, Long) -> Unit)?,
        ): VaultModelFile {
            val fileName = vaultPath.substringAfterLast('/')
            val targetDir = File(context.filesDir, "model_cache").apply { mkdirs() }
            val target = File(targetDir, fileName)

            val availableBytes = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }
                .getOrDefault(0L)
            if (expectedSizeBytes > 0 && availableBytes in 1 until expectedSizeBytes) {
                throw ModelNotInstalledException(
                    modelId = vaultPath,
                    expectedFileName = fileName,
                    reason = "this storage provider does not allow Sarothi to open the model " +
                        "directly, so it must be cached in app-private storage — but only " +
                        "${availableBytes / (1024 * 1024)} MB is free there and the model needs " +
                        "${expectedSizeBytes / (1024 * 1024)} MB. Free up space, or choose a vault " +
                        "folder on storage that permits direct file access.",
                )
            }

            if (target.isFile && expectedSizeBytes > 0 && target.length() == expectedSizeBytes) {
                return VaultModelFile(vaultPath, target.absolutePath, NativeFileAccess.PRIVATE_COPY,
                    target.length(), null, target)
            }

            var copied = 0L
            fileSystem.openInputStream(vaultPath).use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onCopyProgress?.invoke(copied, expectedSizeBytes)
                    }
                }
            }
            if (expectedSizeBytes > 0 && copied != expectedSizeBytes) {
                target.delete()
                throw ModelNotInstalledException(
                    modelId = vaultPath,
                    expectedFileName = fileName,
                    reason = "caching the model into app-private storage stopped after $copied of " +
                        "$expectedSizeBytes bytes",
                )
            }
            return VaultModelFile(vaultPath, target.absolutePath, NativeFileAccess.PRIVATE_COPY,
                copied, null, target)
        }

        /** Removes every cached private copy (Settings → Storage → Clear model cache). */
        fun purgePrivateCopies(context: Context): Int {
            val dir = File(context.filesDir, "model_cache")
            if (!dir.isDirectory) return 0
            return dir.listFiles()?.count { it.delete() } ?: 0
        }

        fun privateCacheBytes(context: Context): Long {
            val dir = File(context.filesDir, "model_cache")
            if (!dir.isDirectory) return 0L
            return dir.listFiles()?.sumOf { it.length() } ?: 0L
        }
    }
}

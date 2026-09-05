package com.ngi.sarothi.core.model

import com.ngi.sarothi.core.net.HttpClient
import com.ngi.sarothi.core.net.NetworkPolicy
import com.ngi.sarothi.core.storage.ManifestModelEntry
import com.ngi.sarothi.core.storage.ModelState
import com.ngi.sarothi.core.storage.VaultFileSystem
import com.ngi.sarothi.core.storage.VaultManager
import com.ngi.sarothi.core.storage.VaultPaths
import com.ngi.sarothi.core.util.Hashing
import java.time.Instant

/** One source's outcome, kept so failures can be reported honestly to the user. */
data class SourceAttempt(
    val source: ModelSource,
    val outcome: String,
    val bytesWritten: Long,
)

data class DownloadProgress(
    val model: CatalogModel,
    val bytesWritten: Long,
    val totalBytes: Long,
    val sourceIndex: Int,
    val sourceCount: Int,
    val sourceLabel: String,
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (bytesWritten.toFloat() / totalBytes).coerceIn(0f, 1f)
}

sealed interface DownloadOutcome {
    /** Downloaded (or already present) and integrity-proven. */
    data class Success(val entry: ManifestModelEntry, val downloadedNow: Boolean) : DownloadOutcome

    /** Never started: the user's network policy forbids it right now. */
    data class BlockedByNetworkPolicy(val reason: String) : DownloadOutcome

    /** Every source failed. Carries the manual-install fallback text. */
    data class Failed(
        val model: CatalogModel,
        val attempts: List<SourceAttempt>,
        val manualInstructions: String,
    ) : DownloadOutcome

    /** The bytes arrived but did not match the digest upstream publishes. */
    data class ChecksumRejected(
        val model: CatalogModel,
        val expected: String,
        val actual: String,
    ) : DownloadOutcome

    data class Cancelled(val model: CatalogModel, val bytesWritten: Long) : DownloadOutcome
}

/**
 * Downloads model files into the vault's `models/` directory.
 *
 * Behaviours the storage/download spec requires, all implemented here:
 *  - **Resumable**: an existing partial file is measured and requested with
 *    `Range: bytes=N-`. If a server ignores the range and replies 200, the partial
 *    file is truncated and restarted rather than appended to (which would corrupt it).
 *  - **Ordered sources**: each [CatalogModel] lists primary and mirror URLs; they
 *    are tried in order, and a resume continues against the next source.
 *  - **Checksummed**: SHA-256 (Git-LFS object id) or a Git blob SHA-1 for small
 *    non-LFS files. A mismatch deletes the file — a corrupt model is never marked
 *    usable.
 *  - **Wi-Fi-only by default**, with an explicit opt-in for mobile data.
 *  - **Manual fallback**: when every source fails, the exact file name and where to
 *    put it are returned so the UI can show real instructions.
 *
 * It does *not* own the foreground service or the notification; [ModelDownloadService]
 * in the app module wraps it so that the OS keeps the download alive with the screen
 * off. That split keeps `:core` free of Android component lifecycle concerns.
 */
class ModelDownloader(
    private val http: HttpClient,
    private val networkPolicy: NetworkPolicy,
    private val vaultManager: VaultManager,
) {

    /**
     * @param allowMobileData the user's "download over mobile data" setting.
     * @param onProgress throttled progress callback, invoked on the calling dispatcher.
     * @param shouldContinue return false to abort; the partial file is left in place
     *   so the next attempt resumes instead of restarting.
     */
    suspend fun download(
        model: CatalogModel,
        allowMobileData: Boolean,
        onProgress: (DownloadProgress) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ): DownloadOutcome {
        networkPolicy.blockReason(allowMobileData)?.let {
            return DownloadOutcome.BlockedByNetworkPolicy(it)
        }

        val fs = vaultManager.requireFileSystem()
        fs.createDirectories(VaultPaths.MODELS_DIR)
        val path = model.vaultPath

        // A restore must not re-download anything: if the file is already complete
        // and verifies, it is accepted as-is.
        val existingSize = if (fs.exists(path)) fs.fileSize(path) else 0L
        if (existingSize == model.sizeBytes) {
            return verifyAndRecord(model, fs, downloadedNow = false)
        }
        if (existingSize > model.sizeBytes) {
            // Larger than upstream publishes: cannot be resumed, and keeping it
            // would produce a corrupt model. Remove and start over.
            fs.deleteFile(path)
        }

        var offset = if (existingSize in 1 until model.sizeBytes) existingSize else 0L
        if (offset == 0L && fs.exists(path)) fs.deleteFile(path)

        val attempts = mutableListOf<SourceAttempt>()
        var lastProgressAt = 0L

        for ((index, source) in model.sources.withIndex()) {
            if (!shouldContinue()) {
                return DownloadOutcome.Cancelled(model, bytesOnDisk(fs, path))
            }

            val resumeFrom = offset
            var written = 0L
            val result = try {
                fs.openOutputStream(path, append = resumeFrom > 0).use { sink ->
                    http.streamTo(
                        url = source.url,
                        sink = sink,
                        startByte = resumeFrom,
                        expectedTotalBytes = model.sizeBytes,
                        onChunk = { chunkTotal, total ->
                            written = chunkTotal
                            val now = System.currentTimeMillis()
                            if (now - lastProgressAt >= PROGRESS_INTERVAL_MILLIS || chunkTotal >= total) {
                                lastProgressAt = now
                                onProgress(
                                    DownloadProgress(
                                        model = model,
                                        bytesWritten = resumeFrom + chunkTotal,
                                        totalBytes = if (total > 0) total else model.sizeBytes,
                                        sourceIndex = index,
                                        sourceCount = model.sources.size,
                                        sourceLabel = source.label,
                                    ),
                                )
                            }
                            shouldContinue()
                        },
                    )
                }
            } catch (failure: Exception) {
                attempts += SourceAttempt(source, "${failure.javaClass.simpleName}: ${failure.message}", written)
                offset = bytesOnDisk(fs, path)
                continue
            }

            when (result) {
                is HttpClient.StreamResult.Completed -> {
                    attempts += SourceAttempt(source, "completed", written)
                    val size = bytesOnDisk(fs, path)
                    if (size == model.sizeBytes) {
                        return verifyAndRecord(model, fs, downloadedNow = true)
                    }
                    // Stream ended early (connection reset without an exception):
                    // keep the bytes and try the next source, resuming.
                    offset = size
                    if (offset >= model.sizeBytes) {
                        return verifyAndRecord(model, fs, downloadedNow = true)
                    }
                }

                is HttpClient.StreamResult.Cancelled ->
                    return DownloadOutcome.Cancelled(model, bytesOnDisk(fs, path))

                HttpClient.StreamResult.RangeNotHonoured -> {
                    attempts += SourceAttempt(source, "server ignored Range; restarting from byte 0", written)
                    // Truncate, then retry this same source from the beginning.
                    runCatching { fs.openOutputStream(path, append = false).use { } }
                    offset = 0L
                    val restarted = retryFromScratch(model, source, fs, onProgress, shouldContinue)
                    when (restarted) {
                        is RestartOutcome.Complete -> return verifyAndRecord(model, fs, downloadedNow = true)
                        is RestartOutcome.Cancelled -> return DownloadOutcome.Cancelled(model, bytesOnDisk(fs, path))
                        is RestartOutcome.Failed -> {
                            attempts += SourceAttempt(source, restarted.reason, bytesOnDisk(fs, path))
                            offset = bytesOnDisk(fs, path)
                        }
                    }
                }

                is HttpClient.StreamResult.HttpError -> {
                    attempts += SourceAttempt(source, "HTTP ${result.statusCode} ${result.message}", written)
                    offset = bytesOnDisk(fs, path)
                }

                is HttpClient.StreamResult.TransportError -> {
                    attempts += SourceAttempt(
                        source,
                        "${result.cause.javaClass.simpleName}: ${result.cause.message}",
                        written,
                    )
                    offset = bytesOnDisk(fs, path)
                }
            }

            // Re-check the network between sources: it may have dropped to cellular.
            networkPolicy.blockReason(allowMobileData)?.let {
                return DownloadOutcome.BlockedByNetworkPolicy(it)
            }
        }

        return DownloadOutcome.Failed(model, attempts, model.manualInstructions)
    }

    private sealed interface RestartOutcome {
        data object Complete : RestartOutcome
        data object Cancelled : RestartOutcome
        data class Failed(val reason: String) : RestartOutcome
    }

    private suspend fun retryFromScratch(
        model: CatalogModel,
        source: ModelSource,
        fs: VaultFileSystem,
        onProgress: (DownloadProgress) -> Unit,
        shouldContinue: () -> Boolean,
    ): RestartOutcome {
        val result = try {
            fs.openOutputStream(model.vaultPath, append = false).use { sink ->
                http.streamTo(
                    url = source.url,
                    sink = sink,
                    startByte = 0L,
                    expectedTotalBytes = model.sizeBytes,
                    onChunk = { written, total ->
                        if (total <= 0 || written % PROGRESS_BYTES == 0L || written >= total) {
                            onProgress(
                                DownloadProgress(
                                    model = model,
                                    bytesWritten = written,
                                    totalBytes = if (total > 0) total else model.sizeBytes,
                                    sourceIndex = 0,
                                    sourceCount = model.sources.size,
                                    sourceLabel = source.label,
                                ),
                            )
                        }
                        shouldContinue()
                    },
                )
            }
        } catch (failure: Exception) {
            return RestartOutcome.Failed("${failure.javaClass.simpleName}: ${failure.message}")
        }
        return when (result) {
            is HttpClient.StreamResult.Completed ->
                if (bytesOnDisk(fs, model.vaultPath) >= model.sizeBytes) RestartOutcome.Complete
                else RestartOutcome.Failed("stream ended after ${result.bytesWritten} of ${model.sizeBytes} bytes")
            is HttpClient.StreamResult.Cancelled -> RestartOutcome.Cancelled
            HttpClient.StreamResult.RangeNotHonoured -> RestartOutcome.Failed("server ignored Range twice")
            is HttpClient.StreamResult.HttpError -> RestartOutcome.Failed("HTTP ${result.statusCode} ${result.message}")
            is HttpClient.StreamResult.TransportError ->
                RestartOutcome.Failed("${result.cause.javaClass.simpleName}: ${result.cause.message}")
        }
    }

    private fun bytesOnDisk(fs: VaultFileSystem, path: String): Long =
        if (fs.exists(path)) fs.fileSize(path).coerceAtLeast(0L) else 0L

    /**
     * Hashes the finished file and either records it in the manifest or deletes it.
     * A file that does not match its published digest is never marked usable.
     */
    private fun verifyAndRecord(
        model: CatalogModel,
        fs: VaultFileSystem,
        downloadedNow: Boolean,
    ): DownloadOutcome {
        val path = model.vaultPath
        val size = bytesOnDisk(fs, path)
        if (size != model.sizeBytes) {
            return DownloadOutcome.Failed(
                model,
                listOf(SourceAttempt(ModelSource("vault", "local file"), "size $size != expected ${model.sizeBytes}", size)),
                model.manualInstructions,
            )
        }

        val digest = fs.openInputStream(path).use { input ->
            when (model.checksumPolicy) {
                ChecksumPolicy.SHA256_PINNED -> Hashing.sha256Hex(input)
                ChecksumPolicy.GIT_BLOB_SHA1_PINNED -> Hashing.gitBlobSha1(input.readBytes())
                // No upstream digest exists; still record what we computed so a
                // later restore can detect changes, but never claim verification.
                ChecksumPolicy.SIZE_ONLY -> Hashing.sha256Hex(input)
            }
        }

        val expected = when (model.checksumPolicy) {
            ChecksumPolicy.SHA256_PINNED -> model.sha256
            ChecksumPolicy.GIT_BLOB_SHA1_PINNED -> model.gitBlobSha1
            ChecksumPolicy.SIZE_ONLY -> null
        }

        if (expected != null && !Hashing.constantTimeEqualsHex(digest, expected)) {
            fs.deleteFile(path)
            return DownloadOutcome.ChecksumRejected(model, expected, digest)
        }

        val entry = ManifestModelEntry(
            catalogId = model.id,
            name = model.fileName,
            path = path,
            sizeBytes = size,
            sha256 = if (model.checksumPolicy == ChecksumPolicy.SHA256_PINNED) digest else model.sha256,
            gitBlobSha1 = if (model.checksumPolicy == ChecksumPolicy.GIT_BLOB_SHA1_PINNED) digest else null,
            checksumPolicy = model.checksumPolicy.name,
            computedDigest = digest,
            checksumVerified = expected != null,
            downloadedAt = if (downloadedNow) Instant.now().toString() else null,
            source = model.sources.firstOrNull()?.url,
        )
        runCatching { vaultManager.recordModel(entry) }
        return DownloadOutcome.Success(entry, downloadedNow)
    }

    /** Re-verifies a model already in the vault without downloading anything. */
    fun verifyExisting(model: CatalogModel): ModelState = vaultManager.verifyModel(model)

    companion object {
        const val PROGRESS_INTERVAL_MILLIS = 250L
        private const val PROGRESS_BYTES = 2L * 1024 * 1024
    }
}

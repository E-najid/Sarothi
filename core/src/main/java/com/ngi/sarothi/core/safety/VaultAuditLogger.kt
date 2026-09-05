package com.ngi.sarothi.core.safety

import android.util.Base64
import android.util.Log
import com.ngi.sarothi.core.crypto.EncryptedFileFormat
import com.ngi.sarothi.core.storage.VaultManager
import com.ngi.sarothi.core.storage.VaultPaths
import com.ngi.sarothi.core.util.Ids
import com.ngi.sarothi.core.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Append-only, encrypted action log.
 *
 * Each line of `logs/actions-YYYY-MM-DD.jsonl` is an independently sealed record
 * (the vault's SRT1 envelope, base64-encoded so the file stays line-oriented).
 * That is what makes the log genuinely append-only under encryption: a single
 * sealed file would have to be decrypted, extended and re-sealed on every action,
 * which on a `content://` SD-card tree would be slow and would leave a window
 * where a crash loses the whole day.
 *
 * Consequence worth stating: records are individually authenticated but the file
 * as a whole is not, so a line truncated by a crash is skipped and counted rather
 * than silently dropped. [integrity] reports that.
 */
class VaultAuditLogger(private val vault: VaultManager) : AuditLogger {

    private val mutex = Mutex()

    override suspend fun record(entry: AuditEntry) {
        // Auditing must never be the reason an action failed.
        runCatching {
            withContext(Dispatchers.IO) {
                mutex.withLock { appendLocked(entry) }
            }
        }.onFailure { Log.w(TAG, "Could not write audit entry ${entry.action}", it) }
    }

    private fun appendLocked(entry: AuditEntry) {
        val key = vault.requireKey()
        val date = entry.timestamp.substringBefore('T').ifBlank { Ids.todayIso() }
        val path = VaultPaths.logPath(date)
        val fs = vault.requireFileSystem()
        fs.createDirectories(VaultPaths.LOGS_DIR)

        val plaintext = Json.stringify(entry.toJson()).toByteArray(Charsets.UTF_8)
        val sealed = EncryptedFileFormat.seal(key, path, plaintext)
        val line = Base64.encodeToString(sealed, Base64.NO_WRAP or Base64.NO_PADDING) + "\n"

        fs.openOutputStream(path, append = true).use { output ->
            output.write(line.toByteArray(Charsets.US_ASCII))
            output.flush()
        }
    }

    override suspend fun recent(limit: Int): List<AuditEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val collected = ArrayList<AuditEntry>(limit)
            for (path in logFilesNewestFirst()) {
                if (collected.size >= limit) break
                collected += readLocked(path).asReversed()
            }
            collected.take(limit)
        }
    }

    override suspend fun forTask(taskId: String): List<AuditEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val collected = ArrayList<AuditEntry>()
            for (path in logFilesNewestFirst()) {
                collected += readLocked(path).filter { it.taskId == taskId }
            }
            // Log files are newest-first; a task's own entries should read oldest-first.
            collected.asReversed()
        }
    }

    override suspend fun count(): Long = withContext(Dispatchers.IO) {
        mutex.withLock {
            var total = 0L
            for (path in logFilesNewestFirst()) total += readLocked(path).size
            total
        }
    }

    /** How many stored lines could be decoded, and how many could not. */
    suspend fun integrity(): LogIntegrity = withContext(Dispatchers.IO) {
        mutex.withLock {
            var good = 0L
            var bad = 0L
            for (path in logFilesNewestFirst()) {
                val (entries, skipped) = readWithSkipsLocked(path)
                good += entries.size
                bad += skipped
            }
            LogIntegrity(readableEntries = good, undecodableLines = bad)
        }
    }

    private fun logFilesNewestFirst(): List<String> {
        val fs = vault.requireFileSystem()
        if (!fs.exists(VaultPaths.LOGS_DIR)) return emptyList()
        return fs.listFiles(VaultPaths.LOGS_DIR)
            .filter { !it.isDirectory && it.name.startsWith("actions-") && it.name.endsWith(".jsonl") }
            .sortedByDescending { it.name }
            .map { "${VaultPaths.LOGS_DIR}/${it.name}" }
    }

    private fun readLocked(path: String): List<AuditEntry> = readWithSkipsLocked(path).first

    private fun readWithSkipsLocked(path: String): Pair<List<AuditEntry>, Long> {
        val fs = vault.requireFileSystem()
        if (!fs.exists(path)) return emptyList<AuditEntry>() to 0L
        val key = runCatching { vault.requireKey() }.getOrNull() ?: return emptyList<AuditEntry>() to 0L
        val bytes = runCatching { fs.readFile(path) }.getOrNull() ?: return emptyList<AuditEntry>() to 0L
        val text = bytes.toString(Charsets.US_ASCII)

        val entries = ArrayList<AuditEntry>()
        var skipped = 0L
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val sealed = runCatching { Base64.decode(line, Base64.NO_WRAP or Base64.NO_PADDING) }.getOrNull()
            if (sealed == null) {
                skipped++
                continue
            }
            val plaintext = runCatching { EncryptedFileFormat.open(key, path, sealed) }.getOrNull()
            if (plaintext == null) {
                // A torn final line from a crash, or a file copied from another
                // vault (different key). Both are reported, neither is guessed at.
                skipped++
                continue
            }
            val json = runCatching { Json.parseObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            val entry = json?.let { runCatching { AuditEntry.fromJson(it) }.getOrNull() }
            if (entry == null) skipped++ else entries += entry
        }
        return entries to skipped
    }

    /** Deletes log files older than [epochMillis]. Returns how many were removed. */
    suspend fun pruneOlderThan(epochMillis: Long): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val fs = vault.requireFileSystem()
            if (!fs.exists(VaultPaths.LOGS_DIR)) return@withLock 0
            fs.listFiles(VaultPaths.LOGS_DIR)
                .filter { !it.isDirectory && it.lastModifiedEpochMillis < epochMillis }
                .count { entry -> fs.deleteFile("${VaultPaths.LOGS_DIR}/${entry.name}") }
        }
    }

    data class LogIntegrity(val readableEntries: Long, val undecodableLines: Long) {
        val isClean: Boolean get() = undecodableLines == 0L
        val description: String
            get() = if (isClean) {
                "$readableEntries audit entries, all readable."
            } else {
                "$readableEntries audit entries readable, $undecodableLines line(s) could not be " +
                    "decoded (torn by a crash, or written with a different vault key)."
            }
    }

    companion object {
        private const val TAG = "SarothiAudit"
    }
}

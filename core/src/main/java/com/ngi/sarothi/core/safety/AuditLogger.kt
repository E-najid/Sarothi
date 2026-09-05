package com.ngi.sarothi.core.safety

import com.ngi.sarothi.core.plugin.Sensitivity

/**
 * Append-only record of every action Sarothi takes.
 *
 * Implementations write to the vault's `logs/` directory (so the history travels
 * with the SD card) and expose enough of it for the History screen.
 */
interface AuditLogger {
    /** Appends one entry. Must never throw: failing to log must not abort an action. */
    suspend fun record(entry: AuditEntry)

    /** Convenience wrapper used at action sites. */
    suspend fun record(
        actor: ActorKind,
        actorName: String,
        category: String,
        action: String,
        sensitivity: Sensitivity,
        outcome: ActionOutcome,
        summary: String,
        target: String? = null,
        taskId: String? = null,
        stepId: String? = null,
        parameterDigest: String? = null,
        errorClass: String? = null,
        errorMessage: String? = null,
        durationMillis: Long = 0L,
        undoable: Boolean = false,
    ) = record(
        AuditEntry(
            timestamp = AuditEntry.now(),
            taskId = taskId,
            stepId = stepId,
            actor = actor,
            actorName = actorName,
            category = category,
            action = action,
            target = target,
            sensitivity = sensitivity,
            outcome = outcome,
            summary = summary,
            parameterDigest = parameterDigest,
            errorClass = errorClass,
            errorMessage = errorMessage,
            durationMillis = durationMillis,
            undoable = undoable,
        ),
    )

    /** Most recent [limit] entries, newest first. */
    suspend fun recent(limit: Int = 200): List<AuditEntry>

    /** Entries belonging to one task, oldest first. */
    suspend fun forTask(taskId: String): List<AuditEntry>

    /** Total number of log lines Sarothi currently holds. */
    suspend fun count(): Long
}

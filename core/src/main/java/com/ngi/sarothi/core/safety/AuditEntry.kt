package com.ngi.sarothi.core.safety

import com.google.gson.JsonObject
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import java.time.Instant

/** Who did something. Every audited action names exactly one actor kind. */
enum class ActorKind { USER, MODEL, PLUGIN, SYSTEM, SCHEDULE }

/** What happened to an audited action. */
enum class ActionOutcome { COMPLETED, CONFIRMED_AND_COMPLETED, DENIED_BY_USER, DENIED_BY_POLICY, FAILED, ROLLED_BACK, CANCELLED }

/**
 * One line of Sarothi's action log.
 *
 * This record is the only durable proof of what the agent did. It deliberately
 * stores a redacted summary rather than raw parameters: credentials, message
 * bodies and personal identifiers never reach the log, only a stable digest so
 * two entries can be correlated without leaking content.
 */
data class AuditEntry(
    val timestamp: String,
    val taskId: String?,
    val stepId: String?,
    val actor: ActorKind,
    val actorName: String,
    val category: String,
    val action: String,
    val target: String?,
    val sensitivity: Sensitivity,
    val outcome: ActionOutcome,
    val summary: String,
    /** SHA-256 of the canonical parameter JSON — correlation without disclosure. */
    val parameterDigest: String?,
    val errorClass: String?,
    val errorMessage: String?,
    val durationMillis: Long,
    /** True when an undo handle was produced for this action. */
    val undoable: Boolean,
) {
    fun toJson(): JsonObject = Json.obj {
        addProperty("timestamp", timestamp)
        taskId?.let { addProperty("task_id", it) }
        stepId?.let { addProperty("step_id", it) }
        addProperty("actor", actor.name.lowercase())
        addProperty("actor_name", actorName)
        addProperty("category", category)
        addProperty("action", action)
        target?.let { addProperty("target", it) }
        addProperty("sensitivity", sensitivity.name.lowercase())
        addProperty("outcome", outcome.name.lowercase())
        addProperty("summary", summary)
        parameterDigest?.let { addProperty("parameter_digest", it) }
        errorClass?.let { addProperty("error_class", it) }
        errorMessage?.let { addProperty("error", it) }
        addProperty("duration_millis", durationMillis)
        addProperty("undoable", undoable)
    }

    companion object {
        fun now(): String = Instant.now().toString()

        fun fromJson(json: JsonObject): AuditEntry = AuditEntry(
            timestamp = json.stringOrNull("timestamp") ?: now(),
            taskId = json.stringOrNull("task_id"),
            stepId = json.stringOrNull("step_id"),
            actor = runCatching { ActorKind.valueOf((json.stringOrNull("actor") ?: "system").uppercase()) }
                .getOrDefault(ActorKind.SYSTEM),
            actorName = json.stringOrNull("actor_name") ?: "unknown",
            category = json.stringOrNull("category") ?: "other",
            action = json.stringOrNull("action") ?: "unknown",
            target = json.stringOrNull("target"),
            sensitivity = Sensitivity.fromJson(json.stringOrNull("sensitivity")) ?: Sensitivity.NORMAL,
            outcome = runCatching { ActionOutcome.valueOf((json.stringOrNull("outcome") ?: "failed").uppercase()) }
                .getOrDefault(ActionOutcome.FAILED),
            summary = json.stringOrNull("summary") ?: "",
            parameterDigest = json.stringOrNull("parameter_digest"),
            errorClass = json.stringOrNull("error_class"),
            errorMessage = json.stringOrNull("error"),
            durationMillis = json.get("duration_millis")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            undoable = json.get("undoable")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
        )
    }
}

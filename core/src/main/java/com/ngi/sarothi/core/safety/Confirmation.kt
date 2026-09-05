package com.ngi.sarothi.core.safety

import com.google.gson.JsonObject
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.util.Json

/** Why Sarothi stopped to ask. Shown verbatim in the dialog title area. */
enum class ConfirmationReason {
    /** Money is about to move. Never rememberable. */
    PAYMENT,
    /** Something is about to be deleted or overwritten. Never rememberable. */
    DELETION,
    /** A message leaves the phone to a person. */
    OUTBOUND_MESSAGE,
    /** A system setting, an app, or another app's data is about to change. */
    DESTRUCTIVE_SYSTEM_ACTION,
    /** Cannot be taken back at all: an install, a factory-style reset, a format. */
    IRREVERSIBLE_INSTALL,
    /** A saved credential, token or account number is about to be used. */
    CREDENTIAL_USE,
    /** One action affecting many items at once. */
    BULK_ACTION,
    /** The first time this plugin is used, whatever it does. */
    FIRST_USE_OF_PLUGIN,
    /**
     * Sarothi will act on its own later, without anyone watching — a schedule or a
     * notification rule. Approved once here, and the unattended run itself is still
     * refused any sensitive step.
     */
    UNATTENDED_ACTION,
    /**
     * Personal data is about to be written down — an address, a number, an account.
     * Approved explicitly, because it will be filled into future tasks automatically.
     */
    PERSONAL_DATA,
    /** The model asked for confirmation that the safety layer did not require. */
    MODEL_DECIDED,
}

/** What the user chose. */
enum class ConfirmationDecision {
    ALLOW_ONCE,
    /** Allow, and remember the choice for this plugin+action for the session. */
    ALLOW_FOR_SESSION,
    /** Allow, and remember it durably in the vault's plugin config. */
    ALLOW_ALWAYS,
    DENY,
    /** No answer obtained (timeout, task cancelled, screen dismissed). */
    NO_ANSWER,
}

/**
 * Everything the confirmation dialog needs.
 *
 * [detailLines] is a redacted, human-readable rendering of the exact parameters
 * that will be used — the user must be able to see the amount, the recipient or
 * the file name before agreeing. It is never derived from the model's prose.
 */
data class ConfirmationRequest(
    val pluginName: String,
    val action: String,
    val reason: ConfirmationReason,
    val sensitivity: Sensitivity,
    val title: String,
    val detailLines: List<String>,
    /** The parameters as they will actually be executed, for the audit digest. */
    val parameters: JsonObject,
    val undoable: Boolean,
    /** When true the dialog offers no "always" button (payments, deletions). */
    val allowRemember: Boolean,
    val taskId: String? = null,
    val stepId: String? = null,
    /**
     * True when no human is watching (a schedule fired, or a notification rule
     * matched). An unattended task can never be granted a sensitive action: the
     * gate refuses and the step is recorded as `DENIED_BY_POLICY` so the user sees
     * exactly what was skipped and why.
     */
    val unattended: Boolean = false,
) {
    fun toJson(): JsonObject = Json.obj {
        addProperty("plugin", pluginName)
        addProperty("action", action)
        addProperty("reason", reason.name.lowercase())
        addProperty("sensitivity", sensitivity.name.lowercase())
        addProperty("title", title)
        add("details", Json.arr { detailLines.forEach { add(it) } })
        add("parameters", parameters)
        addProperty("undoable", undoable)
        addProperty("allow_remember", allowRemember)
        addProperty("unattended", unattended)
    }
}

/**
 * Asks the user before an action runs.
 *
 * The gate is the single choke point: [com.ngi.sarothi.core.agent.TaskExecutor]
 * routes every sensitive step through it, and plugins that want to escalate a
 * normal action call it directly. No implementation may auto-approve.
 */
interface SafetyGate {
    suspend fun requireConfirmation(request: ConfirmationRequest): ConfirmationDecision

    /** True when a durable "always allow" was previously stored for this pair. */
    fun isRemembered(pluginName: String, action: String): Boolean

    /** Clears every remembered approval (Settings → Safety → Forget all). */
    suspend fun forgetAllRemembered()

    /** Lists remembered approvals so the user can review and revoke them. */
    suspend fun remembered(): List<RememberedApproval>
}

data class RememberedApproval(
    val pluginName: String,
    val action: String,
    val grantedAt: String,
    val scope: String,
)

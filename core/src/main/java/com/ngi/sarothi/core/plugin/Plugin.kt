package com.ngi.sarothi.core.plugin

import com.google.gson.JsonObject

/**
 * The contract every Sarothi capability implements — built-in Kotlin plugins and
 * the ones users add later share it exactly.
 *
 * ```
 * interface Plugin {
 *     val name: String
 *     val description: String
 *     val parameters: JsonSchema
 *     suspend fun execute(params: JsonObject): PluginResult
 * }
 * ```
 *
 * The four members above are the required shape. The remaining members have
 * defaults only where a default cannot be wrong ([availability], [requiredPermissions],
 * [supportsUndo]); [category] and [sensitivity] must be stated by every plugin,
 * because the safety layer and the UI both act on them.
 */
interface Plugin {

    /** Stable snake_case id. Used in tool calls, config files and the audit log. */
    val name: String

    /**
     * One sentence, written for the model. Must say when to use it and when not
     * to; a 350 M model routes almost entirely off this string.
     */
    val description: String

    /** What the model must supply. Validated before [execute] is ever called. */
    val parameters: JsonSchema

    /**
     * Runs the action. Must not show UI itself and must not decide whether it is
     * allowed to run — [com.ngi.sarothi.core.plugin.PluginContext] gives it the
     * safety gate and the audit logger for that.
     */
    suspend fun execute(params: JsonObject): PluginResult

    val category: PluginCategory

    /** Declared maximum harm. See [Sensitivity]; there is deliberately no default. */
    val sensitivity: Sensitivity

    /**
     * Android permissions this plugin needs. Checked by `permission_guard` before
     * execution, so a missing permission produces a clear refusal instead of a
     * SecurityException from deep inside an SDK.
     */
    val requiredPermissions: List<String> get() = emptyList()

    /**
     * Hardware/service preconditions: no SIM, no account, no model, no network,
     * no accessibility service.
     *
     * Receives the same capability bundle [execute] would, minus a real task
     * ([PluginContext.task] is [TaskContext.NONE]) — an availability check has to
     * be able to look at the device, the vault and the screen controller to answer
     * honestly. A tool that reports unavailable is greyed out in the UI and is
     * marked UNAVAILABLE in the planner's catalogue, so the model stops proposing
     * steps that cannot run.
     */
    suspend fun availability(context: PluginContext): PluginAvailability = PluginAvailability.READY

    /** True when [execute] can return an undo token that [undo] understands. */
    val supportsUndo: Boolean get() = false

    /**
     * Reverses a previous successful execution.
     *
     * Only called when [supportsUndo] is true. A plugin that cannot truly reverse
     * an action must leave this at its default so the UI never offers an Undo
     * button that does nothing.
     */
    suspend fun undo(undoToken: String): PluginResult = PluginResult.Failure(
        summaryForUser = "'$name' cannot undo this action.",
        errorClass = "UnsupportedOperationException",
    )

    /** Short example call, included in the prompt only when the model has room. */
    val example: String? get() = null

    /**
     * Human-readable rendering of what is about to happen, for the confirmation
     * dialog.
     *
     * Every plugin whose [sensitivity] is [Sensitivity.SENSITIVE] or
     * [Sensitivity.CRITICAL] must override this and show the real recipient,
     * amount, file name or command. Returning null makes the manager fall back to
     * a deliberately unhelpful preview that lists parameter *names* only and
     * forbids "always allow" — so forgetting this is visible to the user rather
     * than silently leaking values into a dialog nobody wrote.
     */
    fun describeForConfirmation(params: JsonObject): ConfirmationPreview? = null
}

/**
 * What the confirmation dialog shows.
 *
 * [detailLines] must be derived from [com.google.gson.JsonObject] parameters, not
 * from model prose: the user has to be approving the thing that will actually run.
 */
data class ConfirmationPreview(
    val title: String,
    val detailLines: List<String>,
    val reason: com.ngi.sarothi.core.safety.ConfirmationReason,
    /** False for anything that must be re-approved every time. */
    val allowRemember: Boolean = true,
)

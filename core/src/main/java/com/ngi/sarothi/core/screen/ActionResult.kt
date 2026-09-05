package com.ngi.sarothi.core.screen

/**
 * Outcome of one screen action.
 *
 * Actions never throw for expected conditions (service not connected, node gone,
 * app closed itself): they return a value the agent can reason about and decide
 * whether to replan.
 */
sealed interface ActionResult {
    val detail: String

    /**
     * The action was performed. `verified` is true only when Sarothi re-read the
     * screen or the node afterwards and saw the expected change; Android's
     * `performAction` returning true is not by itself proof anything happened.
     */
    data class Done(override val detail: String, val verified: Boolean = false) : ActionResult

    data class Failed(override val detail: String, val retriable: Boolean = true) : ActionResult

    /** Cannot be attempted at all on this device/state. Never retriable. */
    data class Unavailable(override val detail: String) : ActionResult
}

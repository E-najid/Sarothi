package com.ngi.sarothi.core.agent

import com.google.gson.JsonObject
import com.ngi.sarothi.core.util.Json

/** What the orchestrator decided to do about a request. */
enum class DecisionKind {
    /** Use tools; [Plan.steps] is non-empty. */
    PLAN,

    /** No tool is needed — reply directly (a question, small talk, arithmetic). */
    ANSWER,

    /** The request cannot proceed without something only the user has. */
    ASK_USER,

    /** The request is outside what Sarothi does, and it says so. */
    REFUSE,
}

/** What to do when a step fails. Chosen by the model, enforced by the executor. */
enum class OnStepFailure {
    /** Ask the model for a fresh plan given what has already happened. */
    REPLAN,

    /** Continue with the remaining steps. */
    SKIP,

    /** Stop the task. */
    ABORT,

    /** Stop and ask the user what to do. */
    ASK_USER,
}

data class PlanStep(
    val id: String,
    val index: Int,
    /** One line, in the user's language, shown in the checklist. */
    val intent: String,
    val tool: String,
    val args: JsonObject,
    val onFailure: OnStepFailure,
) {
    fun toJson(): JsonObject = Json.obj {
        addProperty("id", id)
        addProperty("index", index)
        addProperty("intent", intent)
        addProperty("tool", tool)
        add("args", args)
        addProperty("on_failure", onFailure.name.lowercase())
    }
}

/**
 * A structured plan produced by the text orchestrator.
 *
 * [thought] is kept and shown in the task detail screen: an agent that will not
 * show its reasoning cannot be debugged by the person it is acting for.
 */
data class Plan(
    val kind: DecisionKind,
    val thought: String,
    val steps: List<PlanStep>,
    /** Direct reply text when [kind] is [DecisionKind.ANSWER] or [DecisionKind.REFUSE]. */
    val answer: String?,
    /** The question when [kind] is [DecisionKind.ASK_USER]. */
    val ask: UserQuestionSpec?,
    /** Facts the model assumed. Shown to the user, because an assumption is a guess. */
    val assumptions: List<String>,
    /** Raw model output, kept for the task record and for diagnosing bad plans. */
    val rawOutput: String,
) {
    val isActionable: Boolean get() = kind == DecisionKind.PLAN && steps.isNotEmpty()
}

data class UserQuestionSpec(
    val field: String,
    val question: String,
    val choices: List<String>,
    val secret: Boolean,
)

/** Why a plan could not be used. Always reported verbatim to the user. */
sealed interface PlanParseOutcome {
    data class Parsed(val plan: Plan) : PlanParseOutcome
    data class Unparseable(val raw: String, val reason: String) : PlanParseOutcome
}

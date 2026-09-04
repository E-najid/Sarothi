package com.ngi.sarothi.core.agent

import com.google.gson.JsonObject
import com.ngi.sarothi.core.data.StepStatus
import com.ngi.sarothi.core.data.TaskStatus
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.util.Json

/** One row of the live checklist the user watches while a task runs. */
data class StepView(
    val id: String,
    val index: Int,
    val intent: String,
    val tool: String?,
    val status: StepStatus,
    /** What actually happened, in words the user can read. */
    val detail: String?,
    val startedAtEpochMillis: Long?,
    val finishedAtEpochMillis: Long?,
    val sensitivity: Sensitivity,
    val canUndo: Boolean,
    /** True once this step needed a confirmation and the user answered it. */
    val confirmed: Boolean,
) {
    fun toJson(): JsonObject = Json.obj {
        addProperty("id", id)
        addProperty("index", index)
        addProperty("intent", intent)
        tool?.let { addProperty("tool", it) }
        addProperty("status", status.name.lowercase())
        detail?.let { addProperty("detail", it) }
        addProperty("sensitivity", sensitivity.name.lowercase())
        addProperty("can_undo", canUndo)
        addProperty("confirmed", confirmed)
    }
}

/**
 * A question Sarothi is waiting on.
 *
 * This is the visible half of "pause and ask": the task stops, this appears in the
 * UI, and nothing else runs until [SarothiAgent.answerQuestion] is called. The
 * agent is not permitted to substitute a guess — [field] is the exact parameter
 * the answer will be written into.
 */
data class UserQuestion(
    val id: String,
    val question: String,
    val field: String,
    val choices: List<String>,
    /** Mask the input and keep the answer out of the model's context and the log. */
    val secret: Boolean,
    val stepId: String?,
    val askedAtEpochMillis: Long,
)

/**
 * Everything the UI needs to render a running task.
 *
 * Published as a `StateFlow` by [SarothiAgent] and updated at every transition, so
 * the checklist is a live view of real state rather than a post-hoc summary.
 */
data class TaskState(
    val taskId: String,
    val request: String,
    val status: TaskStatus,
    val steps: List<StepView>,
    val currentStepId: String?,
    /** The agent's own running commentary, most recent last. */
    val log: List<String>,
    val question: UserQuestion?,
    val replanCount: Int,
    val stepsUsed: Int,
    val stepBudget: Int,
    val tokensUsed: Int,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long?,
    val finalMessage: String?,
    val failureReason: String?,
    val unattended: Boolean,
    val confirmationCount: Int,
    /** True once the task had to stop and ask the user for something. */
    val neededUserInput: Boolean = false,
) {
    val elapsedMillis: Long
        get() = (finishedAtEpochMillis ?: System.currentTimeMillis()) - startedAtEpochMillis

    val isTerminal: Boolean
        get() = status == TaskStatus.COMPLETED || status == TaskStatus.FAILED ||
            status == TaskStatus.CANCELLED || status == TaskStatus.PARTIALLY_COMPLETED

    /** Progress for a determinate bar; -1 when there is no plan yet. */
    val progress: Float
        get() = if (steps.isEmpty()) -1f
        else steps.count { it.status == StepStatus.DONE || it.status == StepStatus.SKIPPED }
            .toFloat() / steps.size.toFloat()

    fun toJson(): JsonObject = Json.obj {
        addProperty("task_id", taskId)
        addProperty("request", request)
        addProperty("status", status.name.lowercase())
        add("steps", Json.arr { steps.forEach { add(it.toJson()) } })
        currentStepId?.let { addProperty("current_step", it) }
        add("log", Json.arr { log.forEach { add(it) } })
        question?.let { question ->
            add("question", Json.obj {
                addProperty("id", question.id)
                addProperty("text", question.question)
                addProperty("field", question.field)
                add("choices", Json.arr { question.choices.forEach { add(it) } })
                addProperty("secret", question.secret)
            })
        }
        addProperty("replan_count", replanCount)
        addProperty("steps_used", stepsUsed)
        addProperty("step_budget", stepBudget)
        addProperty("tokens_used", tokensUsed)
        addProperty("elapsed_millis", elapsedMillis)
        finalMessage?.let { addProperty("final_message", it) }
        failureReason?.let { addProperty("failure_reason", it) }
        addProperty("unattended", unattended)
        addProperty("confirmation_count", confirmationCount)
        addProperty("needed_user_input", neededUserInput)
    }

    companion object {
        fun initial(taskId: String, request: String, stepBudget: Int, unattended: Boolean) = TaskState(
            taskId = taskId,
            request = request,
            status = TaskStatus.PLANNING,
            steps = emptyList(),
            currentStepId = null,
            log = emptyList(),
            question = null,
            replanCount = 0,
            stepsUsed = 0,
            stepBudget = stepBudget,
            tokensUsed = 0,
            startedAtEpochMillis = System.currentTimeMillis(),
            finishedAtEpochMillis = null,
            finalMessage = null,
            failureReason = null,
            unattended = unattended,
            confirmationCount = 0,
            neededUserInput = false,
        )
    }
}

/** How a task ended, returned to the caller. */
sealed interface AgentOutcome {
    val taskId: String
    val message: String

    data class Completed(
        override val taskId: String,
        override val message: String,
        val stepsExecuted: Int,
        val tokensUsed: Int,
        val memorable: List<String>,
    ) : AgentOutcome

    /** Some steps succeeded, then the task could not continue. */
    data class Partial(
        override val taskId: String,
        override val message: String,
        val completedSteps: Int,
        val failedStep: String?,
    ) : AgentOutcome

    data class Failed(
        override val taskId: String,
        override val message: String,
        val reason: String,
    ) : AgentOutcome

    /** Stopped because the user was asked something and did not answer. */
    data class WaitingForUser(
        override val taskId: String,
        override val message: String,
        val question: UserQuestion,
    ) : AgentOutcome

    data class Cancelled(override val taskId: String, override val message: String) : AgentOutcome

    /**
     * The request was answered directly with no tool use — a question, not a task.
     * Kept separate so the UI does not show an empty checklist for "what's 12% of 800".
     */
    data class DirectAnswer(
        override val taskId: String,
        override val message: String,
        val tokensUsed: Int,
    ) : AgentOutcome
}

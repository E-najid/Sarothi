package com.ngi.sarothi.core.agent

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.ngi.sarothi.core.capability.Notifier
import com.ngi.sarothi.core.data.ChatMessage
import com.ngi.sarothi.core.data.ChatRole
import com.ngi.sarothi.core.data.DataStores
import com.ngi.sarothi.core.data.MemoryKind
import com.ngi.sarothi.core.data.StepStatus
import com.ngi.sarothi.core.data.TaskRecord
import com.ngi.sarothi.core.data.TaskStatus
import com.ngi.sarothi.core.data.TaskStepRecord
import com.ngi.sarothi.core.data.TaskTrigger
import com.ngi.sarothi.core.persona.Persona
import com.ngi.sarothi.core.plugin.PluginManager
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.plugin.TaskContext
import com.ngi.sarothi.core.runtime.GenerationParams
import com.ngi.sarothi.core.runtime.LlamaRuntime
import com.ngi.sarothi.core.runtime.ModelSessionManager
import com.ngi.sarothi.core.runtime.RamPolicy
import com.ngi.sarothi.core.safety.ActionOutcome
import com.ngi.sarothi.core.safety.ActorKind
import com.ngi.sarothi.core.safety.AuditLogger
import com.ngi.sarothi.core.safety.InteractiveSafetyGate
import com.ngi.sarothi.core.screen.ScreenController
import com.ngi.sarothi.core.util.Ids
import com.ngi.sarothi.core.voice.SpeakOutcome
import com.ngi.sarothi.core.voice.VoiceController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The agent: plans, executes step by step, replans on failure, and stops to ask
 * the user whenever it does not know something.
 *
 * ### The loop
 *
 * ```
 * request → prompt (persona + tools + facts + memories + device)
 *         → orchestrator generates ONE JSON object
 *         → PlanParser turns it into PLAN / ANSWER / ASK_USER / REFUSE
 *         → PLAN: run steps in order through PluginManager
 *                 each step: validate → permission → confirm → execute → audit
 *                 on failure: honour that step's on_failure (replan/skip/abort)
 *         → when steps are done: one final generation summarises the outcome
 *         → persist task_history, conversation, memories
 * ```
 *
 * ### What it refuses to do
 *
 * - It never invents a personal value. Missing data becomes `kind=ask_user`, the
 *   task moves to [TaskStatus.WAITING_FOR_USER], and nothing else runs until
 *   [answerQuestion] is called. An unattended task that would need to ask fails
 *   with that reason recorded instead of guessing.
 * - It never executes a tool the model invented; unknown names are a hard failure
 *   and the tool list is returned to the model so it can replan.
 * - It never treats an unanswered confirmation as approval.
 * - It never exceeds [AgentLimits]; hitting a ceiling ends the task with an
 *   explanation that goes into the record and the UI.
 */
class SarothiAgent(
    private val appContext: Context,
    private val plugins: PluginManager,
    private val models: ModelSessionManager,
    private val llama: LlamaRuntime,
    private val stores: DataStores,
    private val screen: ScreenController,
    private val voice: VoiceController,
    private val safety: InteractiveSafetyGate,
    private val audit: AuditLogger,
    private val notifier: Notifier,
    private val ramPolicy: RamPolicy,
    private val scope: CoroutineScope,
    private val personaProvider: () -> Persona,
    private val limits: AgentLimits = AgentLimits.forTier(ramPolicy.tier),
) {

    private val runMutex = Mutex()

    private val _state = MutableStateFlow<TaskState?>(null)

    /** The live checklist. Observed by the task screen and the overlay. */
    val state: StateFlow<TaskState?> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Step-level events, for toasts and the notification progress line. */
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    @Volatile
    private var pendingQuestion: CompletableDeferred<String?>? = null

    private val cancelled = AtomicBoolean(false)

    /** The task currently running, if any. */
    val activeTaskId: String? get() = _state.value?.takeIf { !it.isTerminal }?.taskId

    /**
     * Runs one request end to end.
     *
     * Only one task runs at a time: two concurrent agents would fight over the
     * accessibility service and the single resident model, and neither would be
     * able to tell the user what was happening.
     */
    suspend fun run(
        request: String,
        trigger: TaskTrigger = TaskTrigger.USER_TEXT,
        conversationId: String? = null,
        unattended: Boolean = false,
        screenContext: String? = null,
    ): AgentOutcome {
        require(request.isNotBlank()) { "There is nothing to do" }
        runMutex.withLock {
            cancelled.set(false)
            val taskId = Ids.taskId()
            val persona = personaProvider()
            // Reading the context size needs the model loaded; if it is not
            // installed yet, fall back to a conservative prompt budget rather than
            // failing here — the generation below reports the real reason.
            val contextTokens = runCatching { models.orchestrator().contextTokens }
                .getOrDefault(DEFAULT_CONTEXT_TOKENS)
                .coerceAtLeast(MIN_CONTEXT_TOKENS)
            val budget = AgentPromptBuilder.budgetFor(contextTokens)
            val promptBuilder = AgentPromptBuilder(budgetChars = budget)
            val startedAt = System.currentTimeMillis()

            publish(
                TaskState.initial(taskId, request, limits.maxStepsPerTask, unattended)
                    .copy(log = listOf("Task created")),
            )

            val runner = TaskRun(
                taskId = taskId,
                request = request.trim(),
                trigger = trigger,
                persona = persona,
                unattended = unattended,
                conversationId = conversationId,
                promptBuilder = promptBuilder,
                startedAt = startedAt,
                screenContext = screenContext,
            )
            return try {
                withTimeoutOrNull(limits.taskTimeoutMillis) { runner.execute() }
                    ?: runner.finishTimedOut()
            } catch (failure: Exception) {
                Log.e(TAG, "Task $taskId failed", failure)
                runner.finishFailed("${failure.javaClass.simpleName}: ${failure.message}")
            }
        }
    }

    /** Cancels the running task. Safe to call when nothing is running. */
    fun cancel() {
        cancelled.set(true)
        // Breaks an in-flight native generation; the loop then notices the flag and
        // writes the task record, so cancelling never loses history.
        runCatching { llama.cancelAll() }
        pendingQuestion?.complete(null)
        safety.dismissAll()
        _state.value?.let { current ->
            if (!current.isTerminal) {
                publish(
                    current.copy(
                        status = TaskStatus.CANCELLED,
                        finishedAtEpochMillis = System.currentTimeMillis(),
                        finalMessage = "You stopped this task.",
                        log = current.log + "Cancelled by the user",
                    ),
                )
            }
        }
    }

    /**
     * Answers the question the task is waiting on.
     *
     * @return false when no question is pending (already answered, or the task ended).
     */
    suspend fun answerQuestion(answer: String): Boolean {
        val deferred = pendingQuestion ?: return false
        val current = _state.value ?: return false
        publish(current.copy(question = null, log = current.log + "You answered: ${maskIfSecret(current, answer)}"))
        deferred.complete(answer)
        return true
    }

    /** Dismisses the pending question without answering; the task then stops. */
    suspend fun dismissQuestion(): Boolean {
        val deferred = pendingQuestion ?: return false
        val current = _state.value ?: return false
        publish(current.copy(question = null, log = current.log + "You skipped the question"))
        deferred.complete(null)
        return true
    }

    private fun maskIfSecret(state: TaskState, answer: String): String =
        if (state.question?.secret == true) "•".repeat(answer.length.coerceAtMost(12)) else answer

    private fun publish(next: TaskState) {
        _state.value = next
    }

    private fun emit(event: AgentEvent) {
        _events.tryEmit(event)
    }

    // ------------------------------------------------------------------ the run

    /**
     * Mutable bookkeeping for one [run] call.
     *
     * A class rather than a pile of local variables because the loop has to carry
     * outcomes across replans, and because `finish*` needs the same data to write
     * the task record whatever path ended the task.
     */
    private inner class TaskRun(
        val taskId: String,
        val request: String,
        val trigger: TaskTrigger,
        val persona: Persona,
        val unattended: Boolean,
        val conversationId: String?,
        val promptBuilder: AgentPromptBuilder,
        val startedAt: Long,
        val screenContext: String?,
    ) {
        var activeConversationId: String? = conversationId
        val answers = LinkedHashMap<String, String>()
        val outcomes = mutableListOf<AgentPromptBuilder.StepOutcomeLine>()
        val stepRecords = mutableListOf<TaskStepRecord>()
        val memorable = mutableListOf<String>()
        var modelCalls = 0
        var stepsUsed = 0
        var replans = 0
        var tokensUsed = 0
        var confirmations = 0
        var neededUserInput = false
        var finalMessage: String? = null
        var rawPlanOutput: String? = null

        suspend fun execute(): AgentOutcome {
            val parser = PlanParser(plugins.names().toSet())
            // Either continue the conversation the caller named or start one; the
            // id is resolved once so the request and the reply land together.
            activeConversationId = conversationId
                ?: runCatching { stores.conversations.create(request.take(48)).id }
                    .getOrElse { failure ->
                        Log.w(TAG, "Could not create a conversation record", failure)
                        null
                    }
            val history = activeConversationId
                ?.let { stores.conversations.tail(it, HISTORY_MESSAGES) }
                ?: emptyList()

            if (activeConversationId != null) {
                runCatching {
                    stores.conversations.append(
                        activeConversationId,
                        ChatMessage(ChatRole.USER, request, Instant.now().toString(), null, null),
                    )
                }.onFailure { Log.w(TAG, "Could not record the request", it) }
            }

            val tools = plugins.toolDescriptors(includeDisabled = false)
            val facts = runCatching { stores.userFacts.all() }.getOrDefault(emptyMap())
            val memories = runCatching { stores.memories.search(request, MEMORY_RESULTS) }.getOrDefault(emptyList())
            val device = DeviceBrief.read(
                context = appContext,
                memoryTier = ramPolicy.tier,
                accessibilityConnected = screen.isServiceConnected,
                capturePermitted = screen.hasCapturePermission,
            )
            val systemPrompt = promptBuilder.systemPrompt(persona, tools, memories, facts, device, screenContext)

            var turn = promptBuilder.userTurn(request, history, HISTORY_CHARS)

            while (scope.isActive && !cancelled.get()) {
                if (modelCalls >= limits.maxModelCalls) {
                    return finishPartial(
                        "Sarothi used all $modelCalls of its model calls for this task " +
                            "(the limit keeps a small phone from looping).",
                    )
                }

                val generation = generate(systemPrompt, turn)
                if (generation == null) {
                    return finishFailed(
                        "The on-device model is not usable right now: ${llama.unavailabilityReason() ?: "no reply"}.",
                    )
                }
                rawPlanOutput = generation.first
                tokensUsed += generation.second

                when (val outcome = parser.parse(generation.first)) {
                    is PlanParseOutcome.Unparseable -> {
                        if (replans >= limits.maxReplans) {
                            return finishFailed(
                                "The model's plan could not be read ${replans + 1} time(s). " +
                                    "Last reason: ${outcome.reason}",
                            )
                        }
                        replans++
                        note("Plan was unusable (${outcome.reason}); asking for a new one ($replans/${limits.maxReplans})")
                        turn = promptBuilder.replanTurn(
                            request = request,
                            executed = outcomes,
                            failure = outcome.reason,
                            remainingBudget = (limits.maxStepsPerTask - stepsUsed).coerceAtLeast(1),
                        )
                        continue
                    }

                    is PlanParseOutcome.Parsed -> {
                        val plan = outcome.plan
                        note(plan.thought.ifBlank { "Decided: ${plan.kind.name.lowercase()}" })
                        plan.assumptions.forEach { assumption ->
                            note("Assumption: $assumption")
                            memorable += "assumption: $assumption"
                        }

                        when (plan.kind) {
                            DecisionKind.ANSWER, DecisionKind.REFUSE -> {
                                val text = plan.answer?.takeIf { it.isNotBlank() }
                                    ?: "The model chose not to use a tool but produced no reply text."
                                return if (stepsUsed == 0) {
                                    finishDirect(text)
                                } else {
                                    finishCompleted(text)
                                }
                            }

                            DecisionKind.ASK_USER -> {
                                val spec = plan.ask
                                    ?: return finishFailed("The model wanted to ask you something but did not say what.")
                                if (unattended) {
                                    return finishFailed(
                                        "This task was started automatically and Sarothi needs to ask you " +
                                            "something it must not guess: \"${spec.question}\". " +
                                            "Run it again from the app to answer.",
                                    )
                                }
                                val answer = ask(spec)
                                if (answer == null) {
                                    return finishWaiting(spec)
                                }
                                if (spec.field.isNotBlank()) answers[spec.field] = answer
                                note("You answered ${spec.field.ifBlank { "the question" }}")
                                turn = promptBuilder.replanTurn(
                                    request = request,
                                    executed = outcomes,
                                    failure = "The user answered ${spec.field.ifBlank { "your question" }}: \"$answer\". " +
                                        "Continue with that value. Do not ask again for the same thing.",
                                    remainingBudget = (limits.maxStepsPerTask - stepsUsed).coerceAtLeast(1),
                                )
                                continue
                            }

                            DecisionKind.PLAN -> {
                                if (plan.steps.size > limits.maxStepsPerPlan) {
                                    note(
                                        "The model planned ${plan.steps.size} steps; the limit is " +
                                            "${limits.maxStepsPerPlan}, so only the first " +
                                            "${limits.maxStepsPerPlan} will run.",
                                    )
                                }
                                val result = executeSteps(plan.steps.take(limits.maxStepsPerPlan), parser)
                                when (result) {
                                    StepLoopResult.ALL_DONE -> return summarise()
                                    is StepLoopResult.NeedsReplan -> {
                                        if (replans >= limits.maxReplans) {
                                            return finishPartial(
                                                "Step \"${result.stepIntent}\" failed and Sarothi has " +
                                                    "already replanned ${replans} time(s): ${result.reason}",
                                            )
                                        }
                                        replans++
                                        turn = promptBuilder.replanTurn(
                                            request = request,
                                            executed = outcomes,
                                            failure = result.reason,
                                            remainingBudget = (limits.maxStepsPerTask - stepsUsed).coerceAtLeast(1),
                                        )
                                        continue
                                    }
                                    is StepLoopResult.Aborted -> return finishPartial(result.reason)
                                    is StepLoopResult.WaitingForUser -> return finishWaiting(result.spec)
                                    StepLoopResult.BUDGET_EXHAUSTED -> return finishPartial(
                                        "Sarothi reached its step budget of ${limits.maxStepsPerTask} " +
                                            "for one task and stopped rather than continuing forever.",
                                    )
                                    StepLoopResult.CANCELLED -> return finishCancelled()
                                }
                            }
                        }
                    }
                }
                // Every branch above either returns or continues; this is the
                // belt-and-braces exit so the loop can never spin.
                return finishFailed("The model produced no decision Sarothi could act on.")
            }

            return if (cancelled.get()) finishCancelled() else finishFailed("The task was interrupted.")
        }

        private sealed interface StepLoopResult {
            data object ALL_DONE : StepLoopResult
            data class NeedsReplan(val stepIntent: String, val reason: String) : StepLoopResult
            data class Aborted(val reason: String) : StepLoopResult
            data class WaitingForUser(val spec: UserQuestionSpec) : StepLoopResult
            data object BUDGET_EXHAUSTED : StepLoopResult
            data object CANCELLED : StepLoopResult
        }

        private suspend fun executeSteps(steps: List<PlanStep>, parser: PlanParser): StepLoopResult {
            publishSteps(steps)
            for (step in steps) {
                if (cancelled.get() || !scope.isActive) return StepLoopResult.CANCELLED
                if (stepsUsed >= limits.maxStepsPerTask) return StepLoopResult.BUDGET_EXHAUSTED

                val knownTool = plugins.get(step.tool)
                if (knownTool == null) {
                    val reason = "'${step.tool}' is not a Sarothi tool. The tools that exist are: " +
                        plugins.names().joinToString()
                    note(reason)
                    record(step, StepStatus.FAILED, null, reason, null, "UnknownPluginException", null)
                    outcomes += AgentPromptBuilder.StepOutcomeLine(step.intent, step.tool, false, reason)
                    return when (step.onFailure) {
                        OnStepFailure.SKIP -> continue
                        OnStepFailure.ABORT -> StepLoopResult.Aborted(reason)
                        OnStepFailure.ASK_USER -> StepLoopResult.WaitingForUser(
                            UserQuestionSpec("", reason, emptyList(), false),
                        )
                        OnStepFailure.REPLAN -> StepLoopResult.NeedsReplan(step.intent, reason)
                    }
                }

                markStep(step.id, StepStatus.RUNNING, "Working on it…", started = true)
                emit(AgentEvent.StepStarted(taskId, step.id, step.index, step.intent, step.tool))

                val record = runStep(step, knownTool.sensitivity)
                stepRecords += record
                stepsUsed++

                val ok = record.status == StepStatus.DONE
                outcomes += AgentPromptBuilder.StepOutcomeLine(
                    intent = step.intent,
                    tool = step.tool,
                    ok = ok,
                    detail = record.resultSummary ?: record.errorSummary ?: "",
                )
                emit(
                    AgentEvent.StepFinished(
                        taskId = taskId,
                        stepId = step.id,
                        index = step.index,
                        ok = ok,
                        detail = record.resultSummary ?: record.errorSummary ?: "",
                    ),
                )

                if (ok) continue

                val reason = record.errorSummary ?: "The step failed."
                when (step.onFailure) {
                    OnStepFailure.SKIP -> {
                        markStep(step.id, StepStatus.SKIPPED, "Skipped after failure: $reason")
                        note("Skipped \"${step.intent}\" because it failed: $reason")
                    }
                    OnStepFailure.ABORT -> return StepLoopResult.Aborted("Stopped at \"${step.intent}\": $reason")
                    OnStepFailure.ASK_USER -> return StepLoopResult.WaitingForUser(
                        UserQuestionSpec("", "Step \"${step.intent}\" failed: $reason. What should Sarothi do?", emptyList(), false),
                    )
                    OnStepFailure.REPLAN -> return StepLoopResult.NeedsReplan(step.intent, reason)
                }
            }
            return StepLoopResult.ALL_DONE
        }

        private suspend fun runStep(step: PlanStep, sensitivity: Sensitivity): TaskStepRecord {
            var attempt = 0
            while (true) {
                val taskContext = TaskContext(
                    taskId = taskId,
                    stepId = step.id,
                    persona = persona,
                    language = persona.language,
                    userAnswers = answers.toMap(),
                    unattended = unattended,
                    interactive = !unattended,
                )
                val execution = plugins.executeDetailed(step.tool, applyAnswers(step.args), taskContext)
                if (execution.confirmation != null) confirmations++

                when (val result = execution.result) {
                    is PluginResult.Success -> {
                        memorable += result.memorable.map { "$step.tool: $it" }
                        markStep(
                            step.id,
                            StepStatus.DONE,
                            result.summaryForUser,
                            finished = true,
                            canUndo = result.undoToken != null,
                            confirmed = execution.confirmation != null,
                        )
                        return TaskStepRecord(
                            id = step.id,
                            index = step.index,
                            intent = step.intent,
                            plugin = step.tool,
                            parametersDigest = execution.parameterDigest,
                            status = StepStatus.DONE,
                            resultSummary = result.summaryForUser,
                            errorSummary = null,
                            startedAt = Instant.now().toString(),
                            finishedAt = Instant.now().toString(),
                            sensitivity = sensitivity.name.lowercase(),
                            confirmation = execution.confirmation?.name?.lowercase(),
                            undoToken = result.undoToken,
                        )
                    }

                    is PluginResult.NeedsUserInput -> {
                        if (unattended) {
                            markStep(step.id, StepStatus.FAILED, "Needs your input but the task is unattended")
                            return failedRecord(step, sensitivity, execution.parameterDigest, null,
                                "Unattended task needed to ask: ${result.question}")
                        }
                        if (attempt >= limits.maxRetriesPerStep) {
                            markStep(step.id, StepStatus.FAILED, result.question)
                            return failedRecord(step, sensitivity, execution.parameterDigest, null,
                                "Asked for '${result.field}' but got no usable answer")
                        }
                        attempt++
                        neededUserInput = true
                        markStep(step.id, StepStatus.WAITING_FOR_USER, result.question)
                        val answer = ask(
                            UserQuestionSpec(result.field, result.question, result.choices, result.secret),
                            step.id,
                        )
                        if (answer == null) {
                            markStep(step.id, StepStatus.FAILED, "You did not answer")
                            return failedRecord(step, sensitivity, execution.parameterDigest, null,
                                "The user did not answer the question about '${result.field}'")
                        }
                        answers[result.field] = answer
                        if (!result.secret) {
                            memorable += "${result.field}: $answer"
                            runCatching {
                                stores.userFacts.put(result.field, answer, taskId, confirmedByUser = true, secret = false)
                            }
                        } else {
                            // Secret answers are used for this task only and never
                            // written to memory or to the model's context.
                            runCatching {
                                stores.userFacts.put(result.field, answer, taskId, confirmedByUser = true, secret = true)
                            }
                        }
                        markStep(step.id, StepStatus.RUNNING, "Retrying with your answer", started = true)
                        continue
                    }

                    is PluginResult.Unavailable -> {
                        markStep(step.id, StepStatus.FAILED, result.summaryForUser, finished = true)
                        return failedRecord(step, sensitivity, execution.parameterDigest, null, result.summaryForUser)
                    }

                    is PluginResult.Failure -> {
                        if (result.retriable && attempt < limits.maxRetriesPerStep) {
                            attempt++
                            note("Retrying \"${step.intent}\" (${result.errorClass})")
                            markStep(step.id, StepStatus.RUNNING, "Retrying…", started = true)
                            continue
                        }
                        val blocked = when (execution.blockedBy) {
                            com.ngi.sarothi.core.plugin.BlockedBy.CONFIRMATION -> StepStatus.DENIED
                            else -> StepStatus.FAILED
                        }
                        markStep(step.id, blocked, result.summaryForUser, finished = true)
                        return failedRecord(
                            step = step,
                            sensitivity = sensitivity,
                            parameterDigest = execution.parameterDigest,
                            confirmation = execution.confirmation?.name?.lowercase(),
                            error = result.summaryForUser,
                            errorClass = result.errorClass,
                        )
                    }
                }
            }
        }

        private fun failedRecord(
            step: PlanStep,
            sensitivity: Sensitivity,
            parameterDigest: String?,
            confirmation: String?,
            error: String,
            errorClass: String? = null,
        ): TaskStepRecord = TaskStepRecord(
            id = step.id,
            index = step.index,
            intent = step.intent,
            plugin = step.tool,
            parametersDigest = parameterDigest,
            status = StepStatus.FAILED,
            resultSummary = null,
            errorSummary = error,
            startedAt = Instant.now().toString(),
            finishedAt = Instant.now().toString(),
            sensitivity = sensitivity.name.lowercase(),
            confirmation = confirmation,
            undoToken = null,
        )

        /**
         * Fills `$answer:<field>` placeholders in a plan's arguments with values the
         * user has already given.
         *
         * This is the only substitution the agent performs, and it only ever uses a
         * value the user typed in this task or a fact they previously confirmed.
         * There is no path by which a model-supplied placeholder becomes a made-up
         * phone number.
         */
        private fun applyAnswers(args: JsonObject): JsonObject {
            if (answers.isEmpty()) return args
            val out = JsonObject()
            for ((key, value) in args.entrySet()) {
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    val text = value.asString
                    if (text.contains(ANSWER_PLACEHOLDER)) {
                        var replaced = text
                        answers.forEach { (field, answer) ->
                            replaced = replaced
                                .replace("$ANSWER_PLACEHOLDER$field", answer)
                                .replace(ANSWER_PLACEHOLDER, answer)
                        }
                        out.addProperty(key, replaced)
                        continue
                    }
                }
                out.add(key, value)
            }
            return out
        }

        private suspend fun ask(spec: UserQuestionSpec, stepId: String? = null): String? {
            val question = UserQuestion(
                id = Ids.newId("q"),
                question = spec.question.ifBlank { "Sarothi needs more information." },
                field = spec.field,
                choices = spec.choices,
                secret = spec.secret,
                stepId = stepId,
                askedAtEpochMillis = System.currentTimeMillis(),
            )
            val deferred = CompletableDeferred<String?>()
            pendingQuestion = deferred
            neededUserInput = true

            val current = _state.value
            if (current != null) {
                publish(
                    current.copy(
                        status = TaskStatus.WAITING_FOR_USER,
                        question = question,
                        log = current.log + "Asking you: ${question.question}",
                    ),
                )
            }
            emit(AgentEvent.QuestionAsked(taskId, question))
            notifier.info(
                id = "question-$taskId",
                title = "Sarothi needs your input",
                text = question.question,
            )

            val answer = deferred.await()?.trim()?.takeIf { it.isNotEmpty() }
            pendingQuestion = null
            val after = _state.value
            if (after != null && answer != null) {
                publish(after.copy(status = TaskStatus.RUNNING))
            }
            return answer
        }

        /** One generation from the orchestrator, or null when it is not usable. */
        private suspend fun generate(systemPrompt: String, turn: String): Pair<String, Int>? {
            modelCalls++
            val session = runCatching { models.orchestrator() }.getOrElse { failure ->
                Log.w(TAG, "Could not load the orchestrator", failure)
                return null
            }
            val prompt = "$systemPrompt\n\n$turn\n\nJSON:"
            val result = withTimeoutOrNull(limits.generationTimeoutMillis) {
                withContext(Dispatchers.Default) {
                    llama.generate(
                        session = session,
                        prompt = prompt,
                        params = GenerationParams.STRUCTURED,
                    )
                }
            }
            if (result == null) {
                llama.cancel(session)
                note("The model took longer than ${limits.generationTimeoutMillis / 1000}s and was stopped.")
                return null
            }
            if (!result.succeeded) {
                note("Model error: ${result.errorMessage ?: result.reason.name}")
                return null
            }
            if (result.text.isBlank()) {
                note("The model produced an empty reply.")
                return null
            }
            return result.text to result.piecesEmitted
        }

        /** Asks the model to report the outcome after the steps succeeded. */
        private suspend fun summarise(): AgentOutcome {
            if (modelCalls >= limits.maxModelCalls) {
                val fallback = outcomes.filter { it.ok }.joinToString("; ") { it.detail }
                return finishCompleted(
                    fallback.ifBlank { "Done." } +
                        " (Sarothi skipped the summary because it had used its model budget.)",
                )
            }
            val turn = buildString {
                append("All planned steps are finished. Report the outcome to the user.\n\n")
                append("ORIGINAL REQUEST:\n").append(request).append("\n\n")
                append("WHAT HAPPENED:\n")
                outcomes.forEach { append("- ").append(it.render()).append('\n') }
                append("\nReply with {\"kind\":\"answer\",\"answer\":\"...\"}. ")
                append("Say only what actually happened. If something did not work, say that.\n")
            }
            val device = DeviceBrief.read(
                context = appContext,
                memoryTier = ramPolicy.tier,
                accessibilityConnected = screen.isServiceConnected,
                capturePermitted = screen.hasCapturePermission,
            )
            val generation = generate(promptBuilder.summarySystemPrompt(persona, device), turn)

            val text = generation?.let { (raw, tokens) ->
                tokensUsed += tokens
                PlanParser(emptySet()).parse(raw).let { outcome ->
                    when (outcome) {
                        is PlanParseOutcome.Parsed -> outcome.plan.answer?.takeIf { it.isNotBlank() }
                        is PlanParseOutcome.Unparseable -> null
                    }
                }
            } ?: outcomes.filter { it.ok }.joinToString("; ") { it.detail }.ifBlank { null }

            return finishCompleted(text ?: "Done.")
        }

        // ------------------------------------------------------------- endings

        private suspend fun finishCompleted(message: String): AgentOutcome {
            finalMessage = message
            return conclude(
                status = TaskStatus.COMPLETED,
                outcome = AgentOutcome.Completed(taskId, message, stepsUsed, tokensUsed, memorable.distinct()),
                failureReason = null,
            )
        }

        private suspend fun finishDirect(message: String): AgentOutcome {
            finalMessage = message
            return conclude(
                status = TaskStatus.COMPLETED,
                outcome = AgentOutcome.DirectAnswer(taskId, message, tokensUsed),
                failureReason = null,
            )
        }

        private suspend fun finishPartial(reason: String): AgentOutcome {
            finalMessage = reason
            val done = stepRecords.count { it.status == StepStatus.DONE }
            return conclude(
                status = if (done > 0) TaskStatus.PARTIALLY_COMPLETED else TaskStatus.FAILED,
                outcome = AgentOutcome.Partial(
                    taskId = taskId,
                    message = if (done > 0) "$done of ${stepRecords.size} step(s) completed. $reason" else reason,
                    completedSteps = done,
                    failedStep = stepRecords.firstOrNull { it.status == StepStatus.FAILED }?.intent,
                ),
                failureReason = reason,
            )
        }

        private suspend fun finishFailed(reason: String): AgentOutcome {
            finalMessage = reason
            return conclude(
                status = TaskStatus.FAILED,
                outcome = AgentOutcome.Failed(taskId, reason, reason),
                failureReason = reason,
            )
        }

        suspend fun finishTimedOut(): AgentOutcome = finishFailed(
            "The task ran longer than ${limits.taskTimeoutMillis / 1000} seconds and was stopped.",
        )

        private suspend fun finishWaiting(spec: UserQuestionSpec): AgentOutcome {
            val question = UserQuestion(
                id = Ids.newId("q"),
                question = spec.question,
                field = spec.field,
                choices = spec.choices,
                secret = spec.secret,
                stepId = null,
                askedAtEpochMillis = System.currentTimeMillis(),
            )
            finalMessage = spec.question
            val current = _state.value
            if (current != null) {
                publish(current.copy(status = TaskStatus.WAITING_FOR_USER, question = question))
            }
            return conclude(
                status = TaskStatus.WAITING_FOR_USER,
                outcome = AgentOutcome.WaitingForUser(taskId, spec.question, question),
                failureReason = null,
                keepQuestion = question,
            )
        }

        private suspend fun finishCancelled(): AgentOutcome {
            finalMessage = "Cancelled."
            return conclude(
                status = TaskStatus.CANCELLED,
                outcome = AgentOutcome.Cancelled(taskId, "You stopped this task."),
                failureReason = "Cancelled by the user",
            )
        }

        private suspend fun conclude(
            status: TaskStatus,
            outcome: AgentOutcome,
            failureReason: String?,
            keepQuestion: UserQuestion? = null,
        ): AgentOutcome {
            val finishedAt = System.currentTimeMillis()
            val current = _state.value
            if (current != null) {
                publish(
                    current.copy(
                        status = status,
                        finishedAtEpochMillis = finishedAt,
                        finalMessage = finalMessage,
                        failureReason = failureReason,
                        question = keepQuestion,
                        stepsUsed = stepsUsed,
                        tokensUsed = tokensUsed,
                        replanCount = replans,
                        confirmationCount = confirmations,
                        neededUserInput = neededUserInput,
                        log = current.log + "Finished: ${status.name.lowercase()}",
                    ),
                )
            }
            emit(AgentEvent.TaskFinished(taskId, status, finalMessage))

            persist(status, failureReason, finishedAt)
            val toSpeak = finalMessage
            if (persona.speakRepliesAloud && toSpeak != null && !unattended) {
                scope.launch {
                    val spoken = runCatching { voice.speak(toSpeak) }.getOrNull()
                    if (spoken is SpeakOutcome.Failed) {
                        Log.w(TAG, "Could not speak the reply: ${spoken.reason}")
                    }
                }
            }
            return outcome
        }

        private fun persist(status: TaskStatus, failureReason: String?, finishedAt: Long) {
            scope.launch {
                val record = TaskRecord(
                    id = taskId,
                    createdAt = Instant.ofEpochMilli(startedAt).toString(),
                    finishedAt = Instant.ofEpochMilli(finishedAt).toString(),
                    request = request,
                    language = persona.language.code,
                    trigger = trigger,
                    personaName = persona.name,
                    steps = stepRecords,
                    status = status,
                    finalMessage = finalMessage,
                    failureReason = failureReason,
                    replanCount = replans,
                    totalTokens = tokensUsed,
                    elapsedMillis = finishedAt - startedAt,
                    neededUserInput = neededUserInput,
                    confirmationCount = confirmations,
                )
                runCatching { stores.taskHistory.save(record) }
                    .onFailure { Log.w(TAG, "Could not save task history for $taskId", it) }

                finalMessage?.let { message ->
                    activeConversationId?.let { conversation ->
                        runCatching {
                            stores.conversations.append(
                                conversation,
                                ChatMessage(ChatRole.ASSISTANT, message, Instant.now().toString(), null, null),
                            )
                        }.onFailure { Log.w(TAG, "Could not record the reply", it) }
                    }
                }

                memorable.distinct().forEach { fact ->
                    val kind = if (fact.startsWith("assumption:")) MemoryKind.OBSERVATION else MemoryKind.FACT
                    runCatching {
                        stores.memories.add(
                            kind = kind,
                            text = fact.substringAfter(':').trim(),
                            tags = listOf(trigger.name.lowercase()),
                            importance = if (kind == MemoryKind.FACT) 4 else 2,
                            sourceTaskId = taskId,
                        )
                    }
                }

                audit.record(
                    actor = if (unattended) ActorKind.SCHEDULE else ActorKind.USER,
                    actorName = "agent",
                    category = "meta",
                    action = "task",
                    sensitivity = Sensitivity.NORMAL,
                    outcome = when (status) {
                        TaskStatus.COMPLETED -> ActionOutcome.COMPLETED
                        TaskStatus.PARTIALLY_COMPLETED -> ActionOutcome.COMPLETED
                        TaskStatus.CANCELLED -> ActionOutcome.CANCELLED
                        else -> ActionOutcome.FAILED,
                    },
                    summary = finalMessage ?: request,
                    taskId = taskId,
                    durationMillis = finishedAt - startedAt,
                )
            }
        }

        // ------------------------------------------------------- state helpers

        private fun publishSteps(steps: List<PlanStep>) {
            val current = _state.value ?: return
            val existingById = current.steps.associateBy { it.id }
            val views = steps.map { step ->
                existingById[step.id] ?: StepView(
                    id = step.id,
                    index = step.index,
                    intent = step.intent,
                    tool = step.tool,
                    status = StepStatus.PENDING,
                    detail = null,
                    startedAtEpochMillis = null,
                    finishedAtEpochMillis = null,
                    sensitivity = Sensitivity.NORMAL,
                    canUndo = false,
                    confirmed = false,
                )
            }
            publish(
                current.copy(
                    status = TaskStatus.RUNNING,
                    steps = current.steps + views.filterNot { view -> current.steps.any { it.id == view.id } },
                ),
            )
        }

        private fun markStep(
            stepId: String,
            status: StepStatus,
            detail: String?,
            started: Boolean = false,
            finished: Boolean = false,
            canUndo: Boolean = false,
            confirmed: Boolean = false,
        ) {
            val current = _state.value ?: return
            val now = System.currentTimeMillis()
            publish(
                current.copy(
                    currentStepId = if (status == StepStatus.RUNNING || status == StepStatus.WAITING_FOR_USER) stepId else current.currentStepId,
                    steps = current.steps.map { view ->
                        if (view.id != stepId) {
                            view
                        } else {
                            view.copy(
                                status = status,
                                detail = detail ?: view.detail,
                                startedAtEpochMillis = if (started && view.startedAtEpochMillis == null) now else view.startedAtEpochMillis,
                                finishedAtEpochMillis = if (finished || status == StepStatus.DONE || status == StepStatus.FAILED) now else view.finishedAtEpochMillis,
                                canUndo = canUndo || view.canUndo,
                                confirmed = confirmed || view.confirmed,
                            )
                        }
                    },
                ),
            )
        }

        private fun record(
            step: PlanStep,
            status: StepStatus,
            resultSummary: String?,
            errorSummary: String?,
            confirmation: String?,
            errorClass: String?,
            undoToken: String?,
        ) {
            stepRecords += TaskStepRecord(
                id = step.id,
                index = step.index,
                intent = step.intent,
                plugin = step.tool,
                parametersDigest = null,
                status = status,
                resultSummary = resultSummary,
                errorSummary = errorSummary,
                startedAt = Instant.now().toString(),
                finishedAt = Instant.now().toString(),
                sensitivity = Sensitivity.NORMAL.name.lowercase(),
                confirmation = confirmation,
                undoToken = undoToken,
            )
            markStep(step.id, status, errorSummary ?: resultSummary, finished = true)
        }

        private fun note(line: String) {
            val current = _state.value ?: return
            publish(current.copy(log = (current.log + line).takeLast(LOG_LINES)))
        }
    }

    companion object {
        private const val TAG = "SarothiAgent"
        private const val HISTORY_MESSAGES = 8
        private const val HISTORY_CHARS = 1400
        private const val MEMORY_RESULTS = 6
        private const val LOG_LINES = 60
        private const val MIN_CONTEXT_TOKENS = 1024
        private const val DEFAULT_CONTEXT_TOKENS = 2048
        private const val ANSWER_PLACEHOLDER = "\$answer:"
    }
}

/** Step-level events for the notification line and toasts. */
sealed interface AgentEvent {
    data class StepStarted(
        val taskId: String,
        val stepId: String,
        val index: Int,
        val intent: String,
        val tool: String,
    ) : AgentEvent

    data class StepFinished(
        val taskId: String,
        val stepId: String,
        val index: Int,
        val ok: Boolean,
        val detail: String,
    ) : AgentEvent

    data class QuestionAsked(val taskId: String, val question: UserQuestion) : AgentEvent

    data class TaskFinished(val taskId: String, val status: TaskStatus, val message: String?) : AgentEvent
}

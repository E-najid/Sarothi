package com.ngi.sarothi.plugins.meta

import com.google.gson.JsonObject
import com.ngi.sarothi.core.plugin.JsonSchema
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.core.plugin.PluginAvailability
import com.ngi.sarothi.core.plugin.PluginCategory
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.plugin.pluginContext
import com.ngi.sarothi.core.safety.ActionOutcome
import com.ngi.sarothi.core.safety.ActorKind
import com.ngi.sarothi.core.safety.UndoOutcome
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import com.ngi.sarothi.plugins.common.textOrAsk

/**
 * Stops the task and asks the user something.
 *
 * The model can call this directly, and plugins call it indirectly by throwing
 * [com.ngi.sarothi.core.error.MissingInformationException]. Either way the effect is
 * the same: nothing else runs until a human answers. This plugin exists so "ask the
 * user" is a first-class tool the planner can choose, not only an error path.
 */
class AskUserPlugin : Plugin {
    override val name = "ask_user"
    override val description =
        "Stop and ask the user a question, then wait for the answer. Use this whenever a task needs " +
            "something only the user knows: a phone number, an amount, an address, which contact, " +
            "which file, or which of several options. NEVER guess these values."
    override val category = PluginCategory.META
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "question" to JsonSchema.Property.Text("The question, in the user's language."),
            "field" to JsonSchema.Property.Text(
                "The parameter name the answer fills, e.g. 'amount' or 'phone'. Use 'general' for a free answer.",
            ),
            "choices" to JsonSchema.Property.List(
                description = "Optional buttons instead of free text.",
                items = JsonSchema.Property.Text("One option"),
            ),
            "secret" to JsonSchema.Property.Flag(
                "True when the answer is a password, PIN or OTP: it will be masked and kept out of logs.",
                default = false,
            ),
        ),
        required = listOf("question"),
    )

    override val example = """{"question":"কত টাকা পাঠাব?","field":"amount"}"""

    override suspend fun execute(params: JsonObject): PluginResult {
        val question = params.textOrAsk("question", "What should Sarothi ask you?")
        val field = params.stringOrNull("field")?.takeIf { it.isNotBlank() } ?: "general"
        val secret = params.get("secret")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val choices = params.getAsJsonArray("choices")?.mapNotNull {
            if (it.isJsonPrimitive) it.asString else null
        } ?: emptyList()
        return PluginResult.NeedsUserInput(
            question = question,
            field = field,
            choices = choices,
            secret = secret,
        )
    }
}

/** Reports what Sarothi is allowed to do without asking, and lets the user revoke it. */
class SafetyStatusPlugin : Plugin {
    override val name = "safety_status"
    override val description =
        "Show Sarothi's safety state: which actions are remembered as always-allowed, which recent " +
            "actions can still be undone, and what the last few audited actions were. Use 'forget' to " +
            "revoke a remembered approval."
    override val category = PluginCategory.META
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "action" to JsonSchema.Property.Text(
                "What to do.",
                enum = listOf("report", "forget_all", "recent_actions", "undoable"),
                default = "report",
            ),
            "limit" to JsonSchema.Property.Integer("How many entries to return.", minimum = 1, maximum = 50, default = 10),
        ),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val action = params.stringOrNull("action") ?: "report"
        val limit = params.get("limit")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, 50) ?: 10

        return when (action) {
            "forget_all" -> {
                val gate = context.safety
                val before = gate.remembered().count { it.scope == "durable" }
                gate.forgetAllRemembered()
                PluginResult.Success(
                    "Forgot $before remembered approval(s). Sarothi will ask again before each of those actions.",
                    Json.obj { addProperty("forgotten", before) },
                )
            }

            "recent_actions" -> {
                val entries = context.audit.recent(limit)
                val data = Json.obj {
                    add("actions", Json.arr {
                        entries.forEach { entry ->
                            add(Json.obj {
                                addProperty("when", entry.timestamp)
                                addProperty("action", entry.action)
                                addProperty("outcome", entry.outcome.name.lowercase())
                                addProperty("sensitivity", entry.sensitivity.name.lowercase())
                                addProperty("summary", entry.summary.take(160))
                            })
                        }
                    })
                    addProperty("count", entries.size)
                }
                PluginResult.Success(
                    if (entries.isEmpty()) "No actions have been logged yet."
                    else "Last ${entries.size} logged action(s): " +
                        entries.take(5).joinToString("; ") { "${it.action} → ${it.outcome.name.lowercase()}" },
                    data,
                )
            }

            "undoable" -> {
                val available = context.undo.available(limit)
                val data = Json.obj {
                    add("undoable", Json.arr {
                        available.forEach { item ->
                            add(Json.obj {
                                addProperty("id", item.id)
                                addProperty("plugin", item.pluginName)
                                addProperty("description", item.description)
                                addProperty("recorded_at", item.recordedAtEpochMillis)
                            })
                        }
                    })
                }
                PluginResult.Success(
                    if (available.isEmpty()) {
                        "Nothing Sarothi did recently can be taken back. Most phone actions — sending " +
                            "a message, placing a call, deleting a file — are irreversible, which is why " +
                            "Sarothi asks first."
                    } else {
                        "${available.size} recent action(s) can still be undone: " +
                            available.joinToString("; ") { it.description }
                    },
                    data,
                )
            }

            else -> {
                val remembered = context.safety.remembered()
                val data = Json.obj {
                    add("remembered_approvals", Json.arr {
                        remembered.forEach { approval ->
                            add(Json.obj {
                                addProperty("plugin", approval.pluginName)
                                addProperty("action", approval.action)
                                addProperty("granted_at", approval.grantedAt)
                                addProperty("scope", approval.scope)
                            })
                        }
                    })
                    addProperty("unattended_blocked", true)
                    addProperty("policy", "Payments, deletions, outbound messages and destructive " +
                        "system actions always ask. A scheduled or notification-triggered task can " +
                        "never be granted one, because nobody is there to answer.")
                }
                PluginResult.Success(
                    if (remembered.isEmpty()) {
                        "Sarothi has no remembered approvals: it will ask before every sensitive action."
                    } else {
                        "${remembered.size} approval(s) remembered: " +
                            remembered.joinToString("; ") { "${it.pluginName}/${it.action} (${it.scope})" }
                    },
                    data,
                )
            }
        }
    }
}

/** Lists the tools Sarothi has and says honestly which ones cannot run right now. */
class PluginListPlugin : Plugin {
    override val name = "plugin_list"
    override val description =
        "List Sarothi's tools by category, with each one's availability and why it is unavailable. " +
            "Use this when the user asks what Sarothi can do, or when you are unsure a tool exists."
    override val category = PluginCategory.META
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "category" to JsonSchema.Property.Text(
                "Restrict to one category.",
                enum = PluginCategory.entries.map { it.name.lowercase() },
            ),
            "only_unavailable" to JsonSchema.Property.Flag("Show only tools that cannot run right now.", default = false),
        ),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val wanted = params.stringOrNull("category")?.lowercase()
        val onlyUnavailable = params.get("only_unavailable")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

        val tools = context.plugins.all()
            .filter { plugin -> wanted == null || plugin.category.name.lowercase() == wanted }
            .sortedWith(compareBy({ it.category.ordinal }, { it.name }))

        val rows = tools.map { plugin ->
            val availability = runCatching { plugin.availability(context) }.getOrElse { failure ->
                PluginAvailability.unavailable("availability check failed: ${failure.message}")
            }
            Triple(plugin, availability, plugin.sensitivity)
        }.filter { (_, availability, _) -> !onlyUnavailable || !availability.ready }

        val data = Json.obj {
            add("tools", Json.arr {
                rows.forEach { (plugin, availability, sensitivity) ->
                    add(Json.obj {
                        addProperty("name", plugin.name)
                        addProperty("category", plugin.category.displayName)
                        addProperty("description", plugin.description)
                        addProperty("sensitivity", sensitivity.name.lowercase())
                        addProperty("available", availability.ready)
                        availability.reason?.let { addProperty("reason", it) }
                        availability.fixAction?.let { addProperty("fix", it) }
                        addProperty("parameters", plugin.parameters.toPromptHint())
                    })
                }
            })
            addProperty("total", rows.size)
            addProperty("unavailable", rows.count { !it.second.ready })
        }

        val unavailableCount = rows.count { !it.second.ready }
        return PluginResult.Success(
            summaryForUser = when {
                rows.isEmpty() -> "No tools match that filter."
                unavailableCount == 0 -> "${rows.size} tool(s), all usable right now."
                else -> "${rows.size} tool(s) listed; $unavailableCount cannot run on this device yet."
            },
            data = data,
        )
    }
}

/** Reads Sarothi's own task history from the vault. */
class TaskHistoryPlugin : Plugin {
    override val name = "task_history"
    override val description =
        "Look up what Sarothi did before: recent tasks, or one task by id, with every step and its " +
            "outcome. Use it when the user asks 'what did you do yesterday' or 'did that bill get paid'."
    override val category = PluginCategory.META
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "task_id" to JsonSchema.Property.Text("A specific task id. Empty lists recent tasks."),
            "limit" to JsonSchema.Property.Integer("How many tasks to list.", minimum = 1, maximum = 50, default = 10),
        ),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val store = context.stores.taskHistory

        val taskId = params.stringOrNull("task_id")?.takeIf { it.isNotBlank() }
        if (taskId != null) {
            val record = store.load(taskId)
                ?: return PluginResult.Failure(
                    summaryForUser = "There is no task with id '$taskId' in the history.",
                    errorClass = "NotFoundException",
                    retriable = false,
                )
            val data = Json.obj {
                addProperty("id", record.id)
                addProperty("request", record.request)
                addProperty("created_at", record.createdAt)
                addProperty("status", record.status.name.lowercase())
                addProperty("trigger", record.trigger.name.lowercase())
                record.finalMessage?.let { addProperty("final_message", it) }
                record.failureReason?.let { addProperty("failure_reason", it) }
                addProperty("elapsed_millis", record.elapsedMillis)
                addProperty("replans", record.replanCount)
                addProperty("asked_user", record.neededUserInput)
                add("steps", Json.arr {
                    record.steps.forEach { step ->
                        add(Json.obj {
                            addProperty("intent", step.intent)
                            step.plugin?.let { addProperty("tool", it) }
                            addProperty("status", step.status.name.lowercase())
                            step.resultSummary?.let { addProperty("result", it) }
                            step.errorSummary?.let { addProperty("error", it) }
                        })
                    }
                })
            }
            return PluginResult.Success(
                "\"${record.request.take(60)}\" — ${record.status.name.lowercase()}, " +
                    "${record.steps.count { it.status == com.ngi.sarothi.core.data.StepStatus.DONE }}/" +
                    "${record.steps.size} step(s) done.",
                data,
            )
        }

        val limit = params.get("limit")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, 50) ?: 10
        val records = store.recent(limit)
        if (records.isEmpty()) {
            return PluginResult.Success(
                "Sarothi has not run any tasks yet, so there is no history.",
                Json.obj { addProperty("count", 0) },
            )
        }
        val data = Json.obj {
            add("tasks", Json.arr {
                records.forEach { record ->
                    add(Json.obj {
                        addProperty("id", record.id)
                        addProperty("request", record.request.take(120))
                        addProperty("created_at", record.createdAt)
                        addProperty("status", record.status.name.lowercase())
                        addProperty("trigger", record.trigger.name.lowercase())
                        addProperty("steps", record.steps.size)
                    })
                }
            })
            addProperty("count", records.size)
        }
        return PluginResult.Success(
            "${records.size} recent task(s), newest first: " +
                records.take(3).joinToString("; ") { "${it.request.take(40)} (${it.status.name.lowercase()})" },
            data,
        )
    }
}

/** Takes back the most recent reversible action. */
class UndoLastPlugin : Plugin {
    override val name = "undo_last"
    override val description =
        "Take back the most recent action Sarothi performed that can be reversed, for example a note " +
            "it saved, a to-do it created or a calendar event it added. Use it when the user says " +
            "'undo' or 'that was wrong'. Not everything can be undone; this says so plainly when it cannot."
    override val category = PluginCategory.META
    override val sensitivity = Sensitivity.NORMAL
    override val supportsUndo = false

    override val parameters = JsonSchema(
        properties = mapOf(
            "action_id" to JsonSchema.Property.Text("A specific undo id from safety_status. Empty undoes the latest."),
        ),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val actionId = params.stringOrNull("action_id")?.takeIf { it.isNotBlank() }
        val outcome = if (actionId != null) context.undo.undo(actionId) else context.undo.undoLast(context.task.taskId)

        return when (outcome) {
            is UndoOutcome.Reversed -> {
                context.audit.record(
                    actor = ActorKind.USER,
                    actorName = "undo_last",
                    category = "meta",
                    action = "undo",
                    sensitivity = Sensitivity.NORMAL,
                    outcome = ActionOutcome.ROLLED_BACK,
                    summary = "Reversed: ${outcome.action.description}",
                    taskId = context.task.taskId,
                )
                PluginResult.Success(
                    "Took back \"${outcome.action.description}\". ${outcome.detail}",
                    Json.obj {
                        addProperty("undone_plugin", outcome.action.pluginName)
                        addProperty("detail", outcome.detail)
                    },
                    spoken = "ফিরিয়ে নিয়েছি।",
                )
            }
            is UndoOutcome.Failed -> PluginResult.Failure(
                summaryForUser = "Sarothi tried to undo \"${outcome.action.description}\" but could not: ${outcome.reason}",
                errorClass = "UndoFailedException",
                retriable = false,
            )
            is UndoOutcome.NothingToUndo -> PluginResult.Failure(
                summaryForUser = outcome.reason,
                errorClass = "NothingToUndoException",
                retriable = false,
            )
        }
    }
}

/** Reports model installation, verification and RAM state. */
class ModelStatusPlugin : Plugin {
    override val name = "model_status"
    override val description =
        "Report which on-device models are installed and checksum-verified, what is loaded in memory " +
            "right now, and how much RAM this phone has. Use it when the user asks why something is " +
            "slow, or when a tool says a model is missing."
    override val category = PluginCategory.META
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(properties = emptyMap())

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val sessionStatus = context.models.status()
        val audit = runCatching { context.vault.auditModels() }.getOrElse { failure ->
            return PluginResult.Failure(
                summaryForUser = "Could not audit the vault's models: ${failure.message}",
                errorClass = failure.javaClass.simpleName,
                retriable = true,
            )
        }
        val data = Json.obj {
            add("ram", sessionStatus.let {
                Json.obj {
                    addProperty("tier", it.tier.name.lowercase())
                    addProperty("description", it.description)
                    addProperty("vision_resident_allowed", it.mayKeepVisionResident)
                }
            })
            add("loaded", Json.obj {
                sessionStatus.orchestrator?.let { addProperty("orchestrator", it) }
                sessionStatus.orchestratorContextTokens?.let { addProperty("context_tokens", it) }
                sessionStatus.vision?.let { addProperty("vision", it) }
                sessionStatus.speech?.let { addProperty("speech", it) }
            })
            add("models", Json.arr {
                com.ngi.sarothi.core.model.ModelCatalog.ALL.forEach { model ->
                    add(Json.obj {
                        addProperty("id", model.id)
                        addProperty("name", model.displayName)
                        addProperty("role", model.role.name.lowercase())
                        addProperty("required", model.required)
                        addProperty("size_bytes", model.sizeBytes)
                        val state = audit.stateOf(model)
                        addProperty("state", state.javaClass.simpleName)
                        when (state) {
                            is com.ngi.sarothi.core.storage.ModelState.SizeMismatch ->
                                addProperty("detail", "${state.actualBytes} of ${state.expectedBytes} bytes")
                            is com.ngi.sarothi.core.storage.ModelState.Corrupt ->
                                addProperty("detail", "expected ${state.expectedDigest}, got ${state.actualDigest}")
                            else -> Unit
                        }
                    })
                }
            })
            addProperty("ready_for_inference", audit.isReadyForInference)
        }

        val missing = audit.missingRequired
        val corrupt = audit.corrupt
        val summary = buildString {
            append("RAM tier: ${sessionStatus.tier.name.lowercase()}. ")
            append(
                when {
                    sessionStatus.orchestrator != null -> "Text model loaded: ${sessionStatus.orchestrator}. "
                    else -> "No text model is loaded right now. "
                },
            )
            if (missing.isNotEmpty()) append("Missing required model(s): ${missing.joinToString { it.displayName }}. ")
            if (corrupt.isNotEmpty()) append("Damaged model(s): ${corrupt.joinToString { it.displayName }} — re-download them. ")
            if (missing.isEmpty() && corrupt.isEmpty()) append("Every required model is installed and verified.")
        }
        return PluginResult.Success(summary.trim(), data)
    }
}

/** Reads and changes the assistant's persona. */
class PersonaPlugin(
    private val read: () -> com.ngi.sarothi.core.persona.Persona,
    private val write: suspend (com.ngi.sarothi.core.persona.Persona) -> Unit,
) : Plugin {
    override val name = "persona_set"
    override val description =
        "Read or change how Sarothi talks: its name, language (Bengali or English), formality " +
            "(তুমি or আপনি), tone, how much it says, and standing instructions. Leave a field out to " +
            "keep its current value. Call it with no arguments to report the current persona."
    override val category = PluginCategory.META
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "name" to JsonSchema.Property.Text("Assistant name."),
            "language" to JsonSchema.Property.Text(
                "Reply language.",
                enum = listOf("bn", "en", "bn-Latn"),
            ),
            "formality" to JsonSchema.Property.Text(
                "How to address the user.",
                enum = listOf("familiar", "polite", "neutral"),
            ),
            "tone" to JsonSchema.Property.Text("Tone description, e.g. 'warm and brief'."),
            "verbosity" to JsonSchema.Property.Text("How much to say.", enum = listOf("terse", "normal", "detailed")),
            "custom_instructions" to JsonSchema.Property.Text("Standing instructions to add or replace."),
            "speak_replies" to JsonSchema.Property.Flag("Read replies aloud with text-to-speech."),
        ),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val current = read()
        if (params.size() == 0) {
            return PluginResult.Success(
                "Current persona: ${current.name}, ${current.language.displayName}, " +
                    "${current.formality.displayName.lowercase()}, ${current.verbosity.name.lowercase()} verbosity. " +
                    "Tone: ${current.tone}",
                current.toJson(),
            )
        }

        val updated = current.copy(
            name = params.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: current.name,
            language = com.ngi.sarothi.core.persona.SarothiLanguage.fromCode(params.stringOrNull("language"))
                ?: current.language,
            formality = params.stringOrNull("formality")?.let { raw ->
                com.ngi.sarothi.core.persona.Formality.entries.firstOrNull { it.name.equals(raw, true) }
            } ?: current.formality,
            tone = params.stringOrNull("tone")?.takeIf { it.isNotBlank() } ?: current.tone,
            verbosity = params.stringOrNull("verbosity")?.let { raw ->
                com.ngi.sarothi.core.persona.Persona.Verbosity.entries.firstOrNull { it.name.equals(raw, true) }
            } ?: current.verbosity,
            customInstructions = params.stringOrNull("custom_instructions")?.trim() ?: current.customInstructions,
            speakRepliesAloud = params.get("speak_replies")?.takeIf { it.isJsonPrimitive }?.asBoolean
                ?: current.speakRepliesAloud,
        )

        if (updated == current) {
            return PluginResult.Success("Nothing to change — the persona already has those values.", current.toJson())
        }
        write(updated)
        return PluginResult.Success(
            "Persona updated: ${updated.name}, ${updated.language.displayName}, ${updated.formality.displayName.lowercase()}, " +
                "${updated.verbosity.name.lowercase()} verbosity.",
            updated.toJson(),
            spoken = "ঠিক আছে, এখন থেকে এভাবেই কথা বলব।",
            memorable = listOf("persona changed to ${updated.name} / ${updated.language.code} / ${updated.tone}"),
        )
    }
}

/** Reports vault location, lock state and integrity — the SD-card portability surface. */
class VaultStatusPlugin : Plugin {
    override val name = "vault_status"
    override val description =
        "Report where Sarothi's vault folder is, whether it is unlocked, and whether its files and " +
            "models pass their checksums. Use it when the user asks about backups, the SD card, or " +
            "moving Sarothi to another phone."
    override val category = PluginCategory.META
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "verify_models" to JsonSchema.Property.Flag(
                "Re-hash every model file. Slow on an SD card: only do it when asked.",
                default = false,
            ),
        ),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val vault = context.vault
        if (!vault.isConfigured) {
            return PluginResult.Unavailable(
                PluginAvailability.unavailable(
                    "No vault folder has been chosen yet, so Sarothi has nowhere to keep memories, " +
                        "logs or models.",
                    fixAction = "Open Sarothi and choose a folder (internal storage or SD card).",
                ),
            )
        }

        val verify = params.get("verify_models")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val audit = runCatching { vault.auditModels() }.getOrElse { failure ->
            return PluginResult.Failure(
                summaryForUser = "Could not read the vault: ${failure.message}",
                errorClass = failure.javaClass.simpleName,
                retriable = true,
            )
        }
        val logIntegrity = if (context.audit is com.ngi.sarothi.core.safety.VaultAuditLogger) {
            runCatching { (context.audit as com.ngi.sarothi.core.safety.VaultAuditLogger).integrity() }.getOrNull()
        } else {
            null
        }

        val data = Json.obj {
            addProperty("unlocked", vault.isUnlocked)
            vault.treeUri?.let { addProperty("location", it.toString()) }
            add("models", Json.obj {
                addProperty("verified", audit.states.count { it.value is com.ngi.sarothi.core.storage.ModelState.Verified })
                addProperty("missing", audit.missing.size)
                addProperty("damaged", audit.corrupt.size)
                addProperty("ready_for_inference", audit.isReadyForInference)
            })
            logIntegrity?.let { addProperty("audit_log", it.description) }
            addProperty("verified_now", verify)
        }

        val summary = buildString {
            append(if (vault.isUnlocked) "Vault is unlocked" else "Vault is locked (memories cannot be read)")
            append("; ${audit.states.count { it.value is com.ngi.sarothi.core.storage.ModelState.Verified }} model(s) verified")
            if (audit.missing.isNotEmpty()) append(", ${audit.missing.size} missing")
            if (audit.corrupt.isNotEmpty()) append(", ${audit.corrupt.size} damaged — re-download those")
            logIntegrity?.let { append(". Audit log: ${it.description}") }
            append('.')
        }
        return PluginResult.Success(summary, data)
    }
}

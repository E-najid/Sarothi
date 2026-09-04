package com.ngi.sarothi.core.plugin

import android.util.Log
import com.google.gson.JsonObject
import com.ngi.sarothi.core.error.FeatureNotImplementedException
import com.ngi.sarothi.core.error.MissingInformationException
import com.ngi.sarothi.core.error.NativeRuntimeUnavailableException
import com.ngi.sarothi.core.error.OperationCancelledException
import com.ngi.sarothi.core.error.PermissionDeniedException
import com.ngi.sarothi.core.error.PluginNotConfiguredException
import com.ngi.sarothi.core.error.PluginUnavailableException
import com.ngi.sarothi.core.error.SafetyDeniedException
import com.ngi.sarothi.core.error.SarothiException
import com.ngi.sarothi.core.error.UnsupportedCapabilityException
import com.ngi.sarothi.core.safety.ActionOutcome
import com.ngi.sarothi.core.safety.ActorKind
import com.ngi.sarothi.core.safety.AuditEntry
import com.ngi.sarothi.core.safety.AuditLogger
import com.ngi.sarothi.core.safety.ConfirmationDecision
import com.ngi.sarothi.core.safety.ConfirmationReason
import com.ngi.sarothi.core.safety.ConfirmationRequest
import com.ngi.sarothi.core.safety.PermissionGuard
import com.ngi.sarothi.core.safety.SafetyGate
import com.ngi.sarothi.core.safety.UndoRegistry
import com.ngi.sarothi.core.util.Hashing
import com.ngi.sarothi.core.util.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Builds the capability bundle a plugin runs with. Implemented by `:app`. */
fun interface PluginContextFactory {
    fun create(task: TaskContext, config: PluginConfig, registry: PluginRegistry): PluginContext
}

/**
 * Per-plugin settings, stored in the vault at `plugins_config/<name>.json`.
 *
 * These are non-secret preferences (a Home Assistant URL, a default calendar, a
 * preferred news source). Anything credential-shaped belongs in
 * [com.ngi.sarothi.core.crypto.SecretStore], which keeps it in the Android
 * Keystore-backed encrypted preferences on the device and never on the SD card.
 */
interface PluginConfigStore {
    fun read(pluginName: String): PluginConfig
    suspend fun write(pluginName: String, config: PluginConfig)
}

/** One tool as the planner sees it. */
data class ToolDescriptor(
    val name: String,
    val category: PluginCategory,
    val sensitivity: Sensitivity,
    val description: String,
    val parametersHint: String,
    val requiredParameters: List<String>,
    val example: String?,
    val available: Boolean,
    val unavailableReason: String?,
    val enabled: Boolean,
    val supportsUndo: Boolean,
) {
    /** Compact catalogue line: a 350 M model cannot be given full JSON Schema per tool. */
    fun toCatalogueLine(): String = buildString {
        append(name).append(' ').append(parametersHint)
        if (sensitivity.requiresConfirmation) append("  [needs your OK]")
        append("\n  ").append(description)
        if (!available) {
            append("\n  UNAVAILABLE: ").append(unavailableReason ?: "not usable on this device")
        }
    }
}

/** Everything the executor needs to know about one plugin call. */
data class ExecutionRecord(
    val pluginName: String,
    val result: PluginResult,
    val validatedParameters: JsonObject?,
    val parameterDigest: String?,
    val confirmation: ConfirmationDecision?,
    val durationMillis: Long,
    val depth: Int,
    val blockedBy: BlockedBy?,
)

enum class BlockedBy { DISABLED, PERMISSION, CONFIRMATION, INVALID_PARAMETERS, UNAVAILABLE, TOO_DEEP, UNKNOWN_PLUGIN }

/**
 * Registers plugins, publishes their schemas to the planner, and runs them.
 *
 * Every call goes through the same pipeline, in this order:
 *
 *  1. resolve the plugin (unknown names are a hard failure — the model does not
 *     get to invent tools);
 *  2. check it is enabled, and that its recursion depth is within budget;
 *  3. check availability (hardware, accounts, models) so an unusable tool reports
 *     itself instead of half-running;
 *  4. check permissions through [PermissionGuard];
 *  5. validate and coerce parameters against the plugin's own [JsonSchema];
 *  6. route through [SafetyGate] when the plugin's sensitivity demands it;
 *  7. execute, mapping exceptions to results (never letting one escape);
 *  8. register an undo handle when the plugin produced one;
 *  9. write an audit entry whatever happened.
 *
 * Nothing in this pipeline may be skipped by a plugin: `execute` is the only entry
 * point the agent has.
 */
class PluginManager(
    plugins: List<Plugin>,
    private val enablement: PluginEnablement,
    private val permissionGuard: PermissionGuard,
    private val audit: AuditLogger,
    private val safety: SafetyGate,
    private val undo: UndoRegistry,
    private val contextFactory: PluginContextFactory,
    private val configStore: PluginConfigStore,
) : PluginRegistry {

    private val byName: Map<String, Plugin>

    init {
        val duplicates = plugins.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate plugin names registered: ${duplicates.sorted()}" }

        val malformed = plugins.filterNot { VALID_NAME.matches(it.name) }.map { it.name }
        require(malformed.isEmpty()) {
            "Plugin names must be lowercase snake_case (they are what the model emits): $malformed"
        }

        val blankDescriptions = plugins.filter { it.description.isBlank() }.map { it.name }
        require(blankDescriptions.isEmpty()) {
            "Plugins with no description cannot be routed by the model: $blankDescriptions"
        }

        // A schema that cannot even describe its own required keys would make the
        // planner emit calls that always fail validation; fail at startup instead.
        plugins.forEach { plugin ->
            runCatching { plugin.parameters.toJson() }.onFailure {
                throw IllegalStateException("Plugin '${plugin.name}' has an invalid parameter schema", it)
            }
        }

        byName = plugins.associateBy { it.name }
        Log.i(TAG, "Registered ${byName.size} plugins across ${plugins.map { it.category }.distinct().size} categories")
    }

    override fun all(): List<Plugin> = byName.values.toList()

    override fun get(name: String): Plugin? = byName[name]

    override fun categories(): Map<PluginCategory, List<Plugin>> =
        byName.values.groupBy { it.category }.toSortedMap(compareBy { it.ordinal })

    fun names(): List<String> = byName.keys.sorted()

    fun count(): Int = byName.size

    /**
     * The catalogue handed to the planner.
     *
     * Unavailable tools are included with an explicit `UNAVAILABLE:` line rather
     * than omitted: a model that has never seen a tool cannot explain why it is
     * not using it, and the user then gets "I can't do that" with no reason.
     */
    suspend fun toolDescriptors(includeDisabled: Boolean = true): List<ToolDescriptor> =
        withContext(Dispatchers.IO) {
            val disabled = runCatching { enablement.disabled() }.getOrDefault(emptySet())
            byName.values
                .filter { includeDisabled || it.name !in disabled }
                .sortedWith(compareBy({ it.category.ordinal }, { it.name }))
                .map { plugin ->
                    val probe = probeContext(plugin.name)
                    val availability = runCatching { plugin.availability(probe) }.getOrElse { failure ->
                        Log.w(TAG, "availability() threw for ${plugin.name}", failure)
                        PluginAvailability.unavailable(
                            "This plugin failed its own availability check: " +
                                "${failure.javaClass.simpleName}: ${failure.message}",
                        )
                    }
                    ToolDescriptor(
                        name = plugin.name,
                        category = plugin.category,
                        sensitivity = plugin.sensitivity,
                        description = plugin.description,
                        parametersHint = plugin.parameters.toPromptHint(),
                        requiredParameters = plugin.parameters.required,
                        example = plugin.example,
                        available = availability.ready,
                        unavailableReason = availability.reason,
                        enabled = plugin.name !in disabled,
                        supportsUndo = plugin.supportsUndo,
                    )
                }
        }

    override suspend fun execute(name: String, params: JsonObject, task: TaskContext): PluginResult =
        executeDetailed(name, params, task).result

    /** The full pipeline, returning what happened at each stage. */
    suspend fun executeDetailed(name: String, params: JsonObject, task: TaskContext): ExecutionRecord {
        val startedAt = System.currentTimeMillis()
        val plugin = byName[name]
        val depth = coroutineContext[PluginCallDepth]?.depth ?: 0
        val parameterDigest = digestOf(params)

        if (plugin == null) {
            val record = ExecutionRecord(
                pluginName = name,
                result = PluginResult.Failure(
                    summaryForUser = "'$name' is not a Sarothi tool.",
                    errorClass = "UnknownPluginException",
                    retriable = false,
                    data = Json.obj {
                        add("known_tools", Json.arr { names().forEach { add(it) } })
                    },
                ),
                validatedParameters = null,
                parameterDigest = parameterDigest,
                confirmation = null,
                durationMillis = System.currentTimeMillis() - startedAt,
                depth = depth,
                blockedBy = BlockedBy.UNKNOWN_PLUGIN,
            )
            auditRecord(name, record, task, Sensitivity.NORMAL, PluginCategory.META)
            return record
        }

        if (depth >= MAX_CALL_DEPTH) {
            return finish(
                plugin,
                task,
                startedAt,
                depth,
                parameterDigest,
                null,
                null,
                BlockedBy.TOO_DEEP,
                PluginResult.Failure(
                    summaryForUser = "'$name' was called $depth plugins deep; Sarothi stops at " +
                        "$MAX_CALL_DEPTH so a plugin cannot recurse forever.",
                    errorClass = "PluginRecursionLimitException",
                    retriable = false,
                ),
            )
        }

        if (!enablement.isEnabled(name)) {
            return finish(
                plugin, task, startedAt, depth, parameterDigest, null, null, BlockedBy.DISABLED,
                PluginResult.Unavailable(
                    PluginAvailability.unavailable(
                        "'$name' has been switched off in Sarothi's settings.",
                        fix = "Settings → Plugins → ${plugin.name} → Enable",
                    ),
                ),
            )
        }

        val context = contextFactory.create(task, configFor(plugin.name), this)
        val availability = runCatching { plugin.availability(context) }.getOrElse { failure ->
            PluginAvailability.unavailable(
                "'$name' could not check whether it is usable: ${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
        if (!availability.ready) {
            return finish(
                plugin, task, startedAt, depth, parameterDigest, null, null, BlockedBy.UNAVAILABLE,
                PluginResult.Unavailable(availability),
            )
        }

        val verdict = permissionGuard.verdictFor(plugin)
        if (!verdict.allowed) {
            return finish(
                plugin, task, startedAt, depth, parameterDigest, null, null, BlockedBy.PERMISSION,
                PluginResult.Failure(
                    summaryForUser = verdict.explanation,
                    errorClass = "PermissionDeniedException",
                    retriable = false,
                    data = Json.obj {
                        add("missing_permissions", Json.arr { verdict.missingRuntime.forEach { add(it) } })
                        add("missing_special_access", Json.arr { verdict.missingSpecial.forEach { add(it) } })
                        verdict.missingRuntime.forEach { permission ->
                            addProperty("why_$permission", permissionGuard.describe(permission).english)
                        }
                    },
                ),
            )
        }

        val validated = when (val outcome = plugin.parameters.validate(params)) {
            is JsonSchema.ValidationResult.Valid -> outcome
            is JsonSchema.ValidationResult.Invalid -> return finish(
                plugin, task, startedAt, depth, parameterDigest, null, null, BlockedBy.INVALID_PARAMETERS,
                PluginResult.Failure(
                    summaryForUser = "The parameters for '$name' are not usable: " +
                        outcome.errors.joinToString(" "),
                    errorClass = "SchemaValidationException",
                    retriable = true,
                    data = Json.obj {
                        add("errors", Json.arr { outcome.errors.forEach { add(it) } })
                        addProperty("schema", plugin.parameters.toPromptHint())
                    },
                ),
            )
        }

        var decision: ConfirmationDecision? = null
        if (plugin.sensitivity.requiresConfirmation) {
            val request = buildConfirmation(plugin, validated.value, task)
            decision = safety.requireConfirmation(request)
            if (decision != ConfirmationDecision.ALLOW_ONCE &&
                decision != ConfirmationDecision.ALLOW_FOR_SESSION &&
                decision != ConfirmationDecision.ALLOW_ALWAYS
            ) {
                val reason = when (decision) {
                    ConfirmationDecision.DENY -> "You declined, so Sarothi did nothing."
                    ConfirmationDecision.NO_ANSWER ->
                        "No answer arrived within the time limit, so Sarothi did nothing. " +
                            "An unanswered confirmation is never treated as a yes."
                    else -> "Sarothi did nothing."
                }
                return finish(
                    plugin, task, startedAt, depth, parameterDigest, validated.value, decision,
                    BlockedBy.CONFIRMATION,
                    PluginResult.Failure(
                        summaryForUser = reason,
                        errorClass = "SafetyDeniedException",
                        retriable = true,
                    ),
                )
            }
        }

        val result = withContext(PluginCallDepth(depth + 1) + PluginInvocation(context)) {
            try {
                plugin.execute(validated.value)
            } catch (cancelled: CancellationException) {
                // Coroutine cancellation must propagate, or the task can never be stopped.
                throw cancelled
            } catch (missing: MissingInformationException) {
                PluginResult.NeedsUserInput(
                    question = missing.questionForUser,
                    field = missing.field,
                    choices = missing.choices,
                    secret = missing.secret,
                )
            } catch (unavailable: PluginUnavailableException) {
                PluginResult.Unavailable(PluginAvailability.unavailable(unavailable.message ?: "Unavailable"))
            } catch (notConfigured: PluginNotConfiguredException) {
                PluginResult.Failure(
                    summaryForUser = notConfigured.message ?: "'${plugin.name}' is not configured.",
                    errorClass = "PluginNotConfiguredException",
                    retriable = false,
                )
            } catch (permission: PermissionDeniedException) {
                PluginResult.Failure(
                    summaryForUser = permission.message ?: "Permission denied.",
                    errorClass = "PermissionDeniedException",
                    retriable = false,
                )
            } catch (safetyDenied: SafetyDeniedException) {
                PluginResult.Failure(
                    summaryForUser = safetyDenied.message ?: "Blocked by Sarothi's safety layer.",
                    errorClass = "SafetyDeniedException",
                    retriable = false,
                )
            } catch (cancelledAction: OperationCancelledException) {
                PluginResult.Failure(
                    summaryForUser = cancelledAction.message ?: "The action was cancelled.",
                    errorClass = "OperationCancelledException",
                    retriable = true,
                )
            } catch (unsupported: UnsupportedCapabilityException) {
                PluginResult.Failure(
                    summaryForUser = unsupported.message ?: "This device cannot do that.",
                    errorClass = "UnsupportedCapabilityException",
                    retriable = false,
                )
            } catch (native: NativeRuntimeUnavailableException) {
                PluginResult.Unavailable(
                    PluginAvailability.unavailable(
                        native.message ?: "The native runtime is unavailable.",
                        fix = "Run scripts/setup_native.sh and rebuild Sarothi.",
                    ),
                )
            } catch (notImplemented: NotImplementedError) {
                // The rule this whole codebase is built on: an unfinished capability
                // says so loudly rather than pretending.
                PluginResult.Failure(
                    summaryForUser = "'${plugin.name}' is not implemented: " +
                        (notImplemented.message ?: "no details"),
                    errorClass = "NotImplementedError",
                    retriable = false,
                )
            } catch (notDone: FeatureNotImplementedException) {
                PluginResult.Failure(
                    summaryForUser = notDone.message ?: "'${plugin.name}' is not implemented.",
                    errorClass = "FeatureNotImplementedException",
                    retriable = false,
                )
            } catch (sarothi: SarothiException) {
                PluginResult.Failure(
                    summaryForUser = sarothi.message ?: "Sarothi could not complete the action.",
                    errorClass = sarothi.javaClass.simpleName,
                    retriable = false,
                )
            } catch (failure: Exception) {
                Log.w(TAG, "Plugin '${plugin.name}' threw", failure)
                PluginResult.Failure(
                    summaryForUser = "'${plugin.name}' failed: ${failure.javaClass.simpleName}" +
                        (failure.message?.let { ": $it" } ?: ""),
                    errorClass = failure.javaClass.simpleName,
                    retriable = true,
                )
            }
        }

        if (result is PluginResult.Success && result.undoToken != null) {
            if (plugin.supportsUndo) {
                undo.register(
                    pluginName = plugin.name,
                    undoToken = result.undoToken,
                    description = result.summaryForUser,
                    taskId = task.taskId,
                )
            } else {
                Log.w(
                    TAG,
                    "Plugin '${plugin.name}' returned an undo token but declares supportsUndo=false; " +
                        "the token is discarded rather than offered as an Undo that would do nothing.",
                )
            }
        }

        return finish(plugin, task, startedAt, depth, parameterDigest, validated.value, decision, null, result)
    }

    private suspend fun finish(
        plugin: Plugin,
        task: TaskContext,
        startedAt: Long,
        depth: Int,
        parameterDigest: String?,
        validated: JsonObject?,
        decision: ConfirmationDecision?,
        blockedBy: BlockedBy?,
        result: PluginResult,
    ): ExecutionRecord {
        val record = ExecutionRecord(
            pluginName = plugin.name,
            result = result,
            validatedParameters = validated,
            parameterDigest = parameterDigest,
            confirmation = decision,
            durationMillis = System.currentTimeMillis() - startedAt,
            depth = depth,
            blockedBy = blockedBy,
        )
        auditRecord(plugin.name, record, task, plugin.sensitivity, plugin.category)
        return record
    }

    private suspend fun auditRecord(
        pluginName: String,
        record: ExecutionRecord,
        task: TaskContext,
        sensitivity: Sensitivity,
        category: PluginCategory,
    ) {
        val outcome = when {
            record.blockedBy == BlockedBy.CONFIRMATION ->
                if (record.confirmation == ConfirmationDecision.DENY) ActionOutcome.DENIED_BY_USER
                else ActionOutcome.DENIED_BY_POLICY
            record.blockedBy != null -> ActionOutcome.DENIED_BY_POLICY
            record.result is PluginResult.Success ->
                if (record.confirmation != null) ActionOutcome.CONFIRMED_AND_COMPLETED else ActionOutcome.COMPLETED
            record.result is PluginResult.Failure &&
                record.result.errorClass == "OperationCancelledException" -> ActionOutcome.CANCELLED
            else -> ActionOutcome.FAILED
        }
        val errorClass = (record.result as? PluginResult.Failure)?.errorClass
        val undoable = (record.result as? PluginResult.Success)?.undoToken != null

        audit.record(
            AuditEntry(
                timestamp = AuditEntry.now(),
                taskId = task.taskId,
                stepId = task.stepId,
                actor = if (task.unattended) ActorKind.SCHEDULE else ActorKind.MODEL,
                actorName = pluginName,
                category = category.name.lowercase(),
                action = pluginName,
                target = null,
                sensitivity = sensitivity,
                outcome = outcome,
                summary = record.result.summaryForUser,
                parameterDigest = record.parameterDigest,
                errorClass = errorClass,
                errorMessage = if (outcome == ActionOutcome.FAILED) record.result.summaryForUser else null,
                durationMillis = record.durationMillis,
                undoable = undoable,
            ),
        )
    }

    /**
     * Builds the confirmation request.
     *
     * When a sensitive plugin did not supply a preview, the dialog shows the
     * parameter *names* only and "always allow" is disabled. That is deliberately
     * worse than a real preview, so a missing override is obvious to whoever is
     * using the app and to whoever is reading the code.
     */
    private fun buildConfirmation(plugin: Plugin, params: JsonObject, task: TaskContext): ConfirmationRequest {
        val preview = runCatching { plugin.describeForConfirmation(params) }.getOrElse { failure ->
            Log.w(TAG, "describeForConfirmation threw for ${plugin.name}", failure)
            null
        }
        val names = params.keySet().sorted()
        return if (preview == null) {
            ConfirmationRequest(
                pluginName = plugin.name,
                action = plugin.name,
                reason = defaultReasonFor(plugin.sensitivity),
                sensitivity = plugin.sensitivity,
                title = "'${plugin.name}' wants to run",
                detailLines = listOf(
                    "This plugin is marked ${plugin.sensitivity.name.lowercase()} but did not describe " +
                        "the action in advance, so Sarothi cannot show you what will happen.",
                    "Parameters it will use: ${names.joinToString()}.",
                    "Because of that, 'always allow' is not offered for this action.",
                ),
                parameters = params,
                undoable = plugin.supportsUndo,
                allowRemember = false,
                taskId = task.taskId,
                stepId = task.stepId,
                unattended = task.unattended,
            )
        } else {
            ConfirmationRequest(
                pluginName = plugin.name,
                action = plugin.name,
                reason = preview.reason,
                sensitivity = plugin.sensitivity,
                title = preview.title,
                detailLines = preview.detailLines +
                    if (names.isEmpty()) emptyList() else listOf("Parameters: ${names.joinToString()}"),
                parameters = params,
                undoable = plugin.supportsUndo,
                allowRemember = preview.allowRemember,
                taskId = task.taskId,
                stepId = task.stepId,
                unattended = task.unattended,
            )
        }
    }

    private fun defaultReasonFor(sensitivity: Sensitivity): ConfirmationReason = when (sensitivity) {
        Sensitivity.CRITICAL -> ConfirmationReason.IRREVERSIBLE_INSTALL
        Sensitivity.SENSITIVE -> ConfirmationReason.MODEL_DECIDED
        else -> ConfirmationReason.MODEL_DECIDED
    }

    /** One plugin's settings, from the vault, or empty when there are none yet. */
    private fun configFor(pluginName: String): PluginConfig = configStore.read(pluginName)

    /**
     * A capability bundle with no task attached, for availability checks.
     *
     * Built on demand rather than cached: several plugins answer from live state
     * (is the accessibility service bound, is the vault unlocked), and a cached
     * context would report yesterday's answer.
     */
    private fun probeContext(pluginName: String): PluginContext =
        contextFactory.create(TaskContext.NONE, configFor(pluginName), this)

    /** Persists one plugin's settings. */
    suspend fun saveConfig(pluginName: String, config: PluginConfig) = configStore.write(pluginName, config)

    private fun digestOf(params: JsonObject): String? {
        if (params.size() == 0) return null
        // Canonical ordering so the same call always produces the same digest.
        val canonical = params.entrySet()
            .sortedBy { it.key }
            .joinToString(",") { (key, value) -> "$key=${Json.stringify(value)}" }
        return Hashing.sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val TAG = "SarothiPlugins"
        const val MAX_CALL_DEPTH = 3
        private val VALID_NAME = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")
    }
}

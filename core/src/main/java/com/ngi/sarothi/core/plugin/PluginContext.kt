package com.ngi.sarothi.core.plugin

import android.content.Context
import com.google.gson.JsonObject
import com.ngi.sarothi.core.capability.Notifier
import com.ngi.sarothi.core.capability.TextModelClient
import com.ngi.sarothi.core.data.DataStores
import com.ngi.sarothi.core.net.HttpClient
import com.ngi.sarothi.core.net.NetworkPolicy
import com.ngi.sarothi.core.persona.Persona
import com.ngi.sarothi.core.persona.SarothiLanguage
import com.ngi.sarothi.core.runtime.ModelSessionManager
import com.ngi.sarothi.core.safety.AuditLogger
import com.ngi.sarothi.core.safety.SafetyGate
import com.ngi.sarothi.core.safety.UndoRegistry
import com.ngi.sarothi.core.screen.ScreenController
import com.ngi.sarothi.core.storage.VaultManager
import com.ngi.sarothi.core.voice.VoiceController

/**
 * Everything a plugin is allowed to touch.
 *
 * This is the capability boundary. A plugin cannot reach the vault, the models or
 * the screen except through what is handed to it here, which is what makes the
 * permission model auditable: to know what a plugin can do, read its
 * [Plugin.requiredPermissions] and this object.
 *
 * [task] carries the current task's identity and the user-supplied answers
 * collected so far. Plugins must read personal data from [task.answers] and, when
 * a value is absent, return [PluginResult.NeedsUserInput] — never invent it.
 */
class PluginContext(
    val appContext: Context,
    val vault: VaultManager,
    val screen: ScreenController,
    val models: ModelSessionManager,
    val textModel: TextModelClient,
    val http: HttpClient,
    val network: NetworkPolicy,
    val secrets: com.ngi.sarothi.core.crypto.SecretStore,
    val safety: SafetyGate,
    val guard: com.ngi.sarothi.core.safety.PermissionGuard,
    val audit: AuditLogger,
    val notifier: Notifier,
    val undo: UndoRegistry,
    val stores: DataStores,
    val voice: VoiceController,
    val scheduler: com.ngi.sarothi.core.schedule.TaskScheduler,
    val task: TaskContext,
    /** Lets a plugin delegate to another one (e.g. shopping → screen agent). */
    val plugins: PluginRegistry,
    /** Per-plugin config from the vault's `plugins_config/<name>.json`. */
    val config: PluginConfig,
)

/**
 * One plugin's persisted settings, backed by `plugins_config/<name>.json` in the
 * vault. Values written here travel with the SD card; secrets must go through
 * [com.ngi.sarothi.core.crypto.SecretStore] instead and stay on the device.
 */
class PluginConfig(private val values: MutableMap<String, String>) {
    fun string(key: String): String? = values[key]?.takeIf { it.isNotBlank() }
    fun stringOr(key: String, fallback: String): String = string(key) ?: fallback
    fun boolean(key: String, fallback: Boolean = false): Boolean =
        values[key]?.toBooleanStrictOrNull() ?: fallback

    fun int(key: String, fallback: Int): Int = values[key]?.toIntOrNull() ?: fallback

    fun all(): Map<String, String> = values.toMap()

    fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }

    fun toJson(): JsonObject {
        val json = JsonObject()
        values.toSortedMap().forEach { (key, value) -> json.addProperty(key, value) }
        return json
    }

    companion object {
        fun fromJson(json: JsonObject?): PluginConfig {
            val values = linkedMapOf<String, String>()
            json?.entrySet()?.forEach { (key, value) ->
                if (value.isJsonPrimitive) values[key] = value.asString
            }
            return PluginConfig(values)
        }
    }
}

/**
 * Identity and user-supplied facts for the task a plugin is running inside.
 *
 * [answers] is how "pause and ask the user" works end to end: the agent asks,
 * the user replies, the reply is stored here keyed by the parameter name, and the
 * step is retried with the value filled in. A plugin that needs something not in
 * [answers] must ask, because guessing personal data is the failure mode this
 * whole mechanism exists to prevent.
 */
data class TaskContext(
    val taskId: String,
    val stepId: String?,
    val persona: Persona,
    val language: SarothiLanguage,
    val userAnswers: Map<String, String>,
    /** True when a schedule or notification rule started this task. */
    val unattended: Boolean,
    /** Set when the user is watching a live checklist; changes how results are surfaced. */
    val interactive: Boolean,
) {
    fun answer(field: String): String? = userAnswers[field]?.takeIf { it.isNotBlank() }

    /**
     * Returns the user's value for [field], or null. Callers must treat null as
     * "ask", not as "make something up".
     */
    fun requireAnswer(field: String, question: String, secret: Boolean = false): String? = answer(field)

    companion object {
        val NONE = TaskContext(
            taskId = "none",
            stepId = null,
            persona = Persona.DEFAULT,
            language = SarothiLanguage.DEFAULT,
            userAnswers = emptyMap(),
            unattended = false,
            interactive = false,
        )
    }
}

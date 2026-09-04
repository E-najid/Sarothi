package com.ngi.sarothi.core.plugin

import com.google.gson.JsonObject
import com.ngi.sarothi.core.util.Json

/**
 * Why a plugin cannot run right now.
 *
 * Plugins must report this instead of failing silently or pretending to succeed:
 * an unavailable plugin is shown greyed-out in the UI and is excluded from the
 * tool list handed to the model, so the model cannot plan a step that cannot work.
 */
data class PluginAvailability(val ready: Boolean, val reason: String? = null, val fixAction: String? = null) {
    fun toJson(): JsonObject = Json.obj {
        addProperty("ready", ready)
        reason?.let { addProperty("reason", it) }
        fixAction?.let { addProperty("fix", it) }
    }

    companion object {
        val READY = PluginAvailability(true)
        fun unavailable(reason: String, fixAction: String? = null) = PluginAvailability(false, reason, fixAction)
    }
}

/**
 * The result of one plugin execution.
 *
 * [summaryForUser] is always present and is the string a human reads; [data] is
 * what the agent reasons over. Keeping them separate stops the model from having
 * to parse prose, and stops the UI from showing JSON.
 */
sealed class PluginResult {

    abstract val summaryForUser: String

    /** Succeeded. [undoToken] lets the safety layer reverse it if one exists. */
    data class Success(
        override val summaryForUser: String,
        val data: JsonObject = JsonObject(),
        /** Text to speak aloud when the task was started by voice. */
        val spoken: String? = null,
        /** Opaque handle understood only by the plugin that produced it. */
        val undoToken: String? = null,
        /** Extra facts worth remembering long term (names, preferences, events). */
        val memorable: List<String> = emptyList(),
    ) : PluginResult()

    /**
     * Failed for a reason Sarothi can explain. [retriable] means the agent may
     * try again with different parameters; [userMessage] is shown verbatim.
     */
    data class Failure(
        override val summaryForUser: String,
        val errorClass: String,
        val retriable: Boolean = false,
        val data: JsonObject = JsonObject(),
    ) : PluginResult()

    /**
     * The plugin needs something only the user has. This is the mechanism behind
     * "pause and ask" — the agent stops the whole task here rather than inventing
     * an address, an amount or a name.
     *
     * [field] names the parameter that is missing so the answer can be routed
     * straight back into it.
     */
    data class NeedsUserInput(
        val question: String,
        val field: String,
        override val summaryForUser: String = question,
        val choices: List<String> = emptyList(),
        val secret: Boolean = false,
    ) : PluginResult()

    /** The plugin itself is not usable on this device right now. */
    data class Unavailable(
        val availability: PluginAvailability,
        override val summaryForUser: String = availability.reason ?: "This action is not available on this device",
    ) : PluginResult()

    fun isSuccess(): Boolean = this is Success

    fun toJson(): JsonObject = Json.obj {
        addProperty("status", when (this@PluginResult) {
            is Success -> "success"
            is Failure -> "failure"
            is NeedsUserInput -> "needs_user_input"
            is Unavailable -> "unavailable"
        })
        addProperty("summary", summaryForUser)
        when (this@PluginResult) {
            is Success -> {
                add("data", data)
                spoken?.let { addProperty("spoken", it) }
                undoToken?.let { addProperty("undo_token", it) }
                if (memorable.isNotEmpty()) add("memorable", Json.arr { memorable.forEach { add(it) } })
            }
            is Failure -> {
                addProperty("error_class", errorClass)
                addProperty("retriable", retriable)
                add("data", data)
            }
            is NeedsUserInput -> {
                addProperty("field", field)
                add("choices", Json.arr { choices.forEach { add(it) } })
                addProperty("secret", secret)
            }
            is Unavailable -> {
                availability.reason?.let { addProperty("reason", it) }
                availability.fixAction?.let { addProperty("fix", it) }
            }
        }
    }
}

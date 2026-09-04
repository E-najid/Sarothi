package com.ngi.sarothi.core.plugin

import com.google.gson.JsonObject

/**
 * Read-only view of the plugin set, handed to plugins through
 * [PluginContext.plugins] so one can delegate to another without being able to
 * reconfigure or disable anything.
 */
interface PluginRegistry {
    fun all(): List<Plugin>
    fun get(name: String): Plugin?
    fun categories(): Map<PluginCategory, List<Plugin>>

    /** Runs another plugin from inside a plugin, with the current task context. */
    suspend fun execute(name: String, params: JsonObject, task: TaskContext): PluginResult
}

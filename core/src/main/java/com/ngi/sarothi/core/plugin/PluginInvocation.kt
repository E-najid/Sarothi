package com.ngi.sarothi.core.plugin

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Carries the [PluginContext] for the call currently being executed.
 *
 * The plugin contract is `suspend fun execute(params: JsonObject): PluginResult`
 * and stays that way — it is what makes a user-written plugin and a built-in one
 * interchangeable. But a plugin still needs capabilities, and adding a second
 * parameter would fork the interface.
 *
 * So [PluginManager] puts the context in the coroutine context for the duration of
 * the call, and a plugin reads it with [pluginContext]. That is safe under
 * concurrency (each call has its own context, unlike a mutable field on a singleton
 * plugin) and it fails loudly rather than silently if a plugin is invoked outside
 * the manager.
 */
class PluginInvocation(val context: PluginContext) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PluginInvocation>
}

/**
 * The capabilities for the current plugin call.
 *
 * @throws IllegalStateException when called outside [PluginManager.execute], which
 *   means the plugin is being used directly in a test or from app code that should
 *   be going through the manager so its action is validated, confirmed and audited.
 */
suspend fun pluginContext(): PluginContext =
    coroutineContext[PluginInvocation]?.context
        ?: throw IllegalStateException(
            "This plugin was executed outside Sarothi's PluginManager, so it has no capabilities. " +
                "Call PluginManager.execute(name, params, task) instead: going around it would skip " +
                "schema validation, the permission guard, the safety gate and the audit log.",
        )

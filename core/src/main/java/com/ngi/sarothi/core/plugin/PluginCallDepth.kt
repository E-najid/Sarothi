package com.ngi.sarothi.core.plugin

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * How deep the current plugin call is.
 *
 * Plugins may call other plugins through [PluginContext.plugins] — `shopping`
 * delegates to `screen_agent`, `voice_note` delegates to `save_note`. That makes
 * unbounded recursion possible if a model asks one plugin to call itself, and a
 * stack overflow deep inside the agent is a terrible way to find out. Depth is
 * carried in the coroutine context so nested calls see it without every plugin
 * having to pass it around, and [PluginManager] refuses to go past
 * [PluginManager.MAX_CALL_DEPTH].
 */
class PluginCallDepth(val depth: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PluginCallDepth>
}

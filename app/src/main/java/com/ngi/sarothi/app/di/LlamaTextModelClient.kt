package com.ngi.sarothi.app.di

import android.util.Log
import com.ngi.sarothi.core.capability.TextModelClient
import com.ngi.sarothi.core.runtime.CompletionReason
import com.ngi.sarothi.core.runtime.GenerationParams
import com.ngi.sarothi.core.runtime.GenerationResult
import com.ngi.sarothi.core.runtime.LlamaRuntime
import com.ngi.sarothi.core.runtime.ModelSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The only implementation of [TextModelClient].
 *
 * A plugin that asks for a language model gets the same always-resident orchestrator
 * the agent plans with, reached through [ModelSessionManager] so that RAM policy stays
 * in charge of what is loaded. Nothing here reaches the network and nothing falls back
 * to a canned answer: when the model is absent the caller receives a
 * [GenerationResult] carrying the reason, which is what the plugin contract expects.
 *
 * `SarothiAgent` builds its own prompt (it appends a `JSON:` cue for the planner) and
 * calls [LlamaRuntime.generate] directly. This client is the plugin-facing path, and it
 * keeps the same shape -- system block, blank line, user turn -- so a plugin's output
 * does not depend on which of the two routes reached the model.
 */
class LlamaTextModelClient(
    private val models: ModelSessionManager,
    private val llama: LlamaRuntime,
) : TextModelClient {

    override val isReady: Boolean
        get() = llama.isAvailable() && models.status().orchestrator != null

    override val unavailableReason: String?
        get() {
            llama.unavailabilityReason()?.let { return it }
            if (models.status().orchestrator == null) {
                return "The orchestrator model is not loaded. Install it under Settings → Models " +
                    "and unlock the vault first."
            }
            return null
        }

    override suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        params: GenerationParams,
        purpose: String,
    ): GenerationResult {
        val session = runCatching { models.orchestrator() }.getOrElse { failure ->
            Log.w(TAG, "Orchestrator unavailable for '$purpose'", failure)
            return GenerationResult(
                text = "",
                reason = CompletionReason.ERROR,
                piecesEmitted = 0,
                elapsedMillis = 0,
                errorMessage = unavailableReason
                    ?: failure.message
                    ?: "The on-device model could not be loaded.",
            )
        }

        val prompt = if (systemPrompt.isBlank()) userPrompt else "$systemPrompt\n\n$userPrompt"
        return withContext(Dispatchers.Default) {
            llama.generate(session = session, prompt = prompt, params = params)
        }
    }

    private companion object {
        const val TAG = "SarothiTextModel"
    }
}

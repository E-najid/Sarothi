package com.ngi.sarothi.core.capability

import com.ngi.sarothi.core.runtime.GenerationParams
import com.ngi.sarothi.core.runtime.GenerationResult

/**
 * Access to the resident text orchestrator for components that are not the agent
 * loop (plugins that need to summarise a fetched page, the memory consolidator,
 * the persona rewriter).
 *
 * Deliberately narrow: no vision, no tools, no streaming — a caller gets one
 * completion and cannot hijack the agent's context window.
 */
interface TextModelClient {
    /** True when the orchestrator is installed and the native runtime is present. */
    val isReady: Boolean

    /** Why it is not ready, for UI. Null when it is. */
    val unavailableReason: String?

    suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        params: GenerationParams = GenerationParams(maxTokens = 256, temperature = 0.2f),
        purpose: String,
    ): GenerationResult
}

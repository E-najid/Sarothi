package com.ngi.sarothi.core.runtime

import android.content.Context
import com.google.gson.JsonObject
import com.ngi.sarothi.core.error.NativeRuntimeUnavailableException
import com.ngi.sarothi.core.model.ModelRole
import com.ngi.sarothi.core.storage.VaultFileSystem
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import java.io.Closeable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GenerationParams(
    val maxTokens: Int = 256,
    /** <= 0 selects greedy decoding, which is what plan/tool output uses. */
    val temperature: Float = 0.0f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    /** Constrains decoding with a GBNF grammar so only a JSON object can be emitted. */
    val jsonOnly: Boolean = false,
    val seed: Long = 0xC0FFEEL,
) {
    companion object {
        /** Conversational reply: a little sampling keeps a 350M model from looping. */
        val CHAT = GenerationParams(maxTokens = 320, temperature = 0.7f, topK = 40, topP = 0.9f)

        /** Structured output: greedy + grammar. */
        val STRUCTURED = GenerationParams(maxTokens = 512, temperature = 0.0f, jsonOnly = true)

        /** Vision grounding: short, structured, greedy. */
        val GROUNDING = GenerationParams(maxTokens = 192, temperature = 0.0f, jsonOnly = true)
    }
}

enum class CompletionReason { END_OF_GENERATION, TOKEN_BUDGET, CANCELLED, ERROR }

data class GenerationResult(
    val text: String,
    val reason: CompletionReason,
    val piecesEmitted: Int,
    val elapsedMillis: Long,
    val errorMessage: String?,
) {
    val succeeded: Boolean get() = errorMessage == null && reason != CompletionReason.ERROR
}

/** A loaded llama.cpp model + context. One per role; not safe to share across threads. */
class LlamaSession internal constructor(
    internal val handle: Long,
    internal val modelFile: VaultModelFile,
    internal val mmprojFile: VaultModelFile?,
    val role: ModelRole,
    val displayName: String,
    val info: JsonObject?,
    /** Context window this session was created with, in tokens. */
    val contextTokens: Int,
) : Closeable {

    internal val mutex = Mutex()
    @Volatile internal var closed = false

    /** How this model's bytes reached native code; shown in the UI because a copy costs disk. */
    val fileAccess: NativeFileAccess get() = modelFile.access

    override fun close() {
        if (closed) return
        closed = true
        runCatching { NativeBridge.nativeLlamaFree(handle) }
        runCatching { modelFile.close() }
        mmprojFile?.let { runCatching { it.close() } }
    }
}

/**
 * Loads GGUF models through llama.cpp and generates text.
 *
 * Model weights are mapped with `mmap` (the native bridge always requests it), so
 * a 219 MB orchestrator does not become 219 MB of process heap on a 3 GB phone —
 * the pages stay in the shared page cache and are dropped under pressure.
 */
class LlamaRuntime(private val context: Context) {

    fun isAvailable(): Boolean =
        NativeBridge.isLoaded && runCatching { NativeBridge.nativeLlamaRuntimeAvailable() }.getOrDefault(false)

    fun isVisionAvailable(): Boolean =
        NativeBridge.isLoaded && runCatching { NativeBridge.nativeLlamaVisionAvailable() }.getOrDefault(false)

    /** Precise, user-facing reason why inference cannot run, or null when it can. */
    fun unavailabilityReason(): String? = when {
        !NativeBridge.isLoaded -> NativeBridge.loadFailure
            ?: "libsarothi_native.so is missing from this APK"
        !isAvailable() -> "libsarothi_native.so loaded but was built without llama.cpp. " +
            "Run scripts/setup_native.sh, then rebuild."
        else -> null
    }

    fun load(
        fileSystem: VaultFileSystem,
        modelVaultPath: String,
        mmprojVaultPath: String?,
        role: ModelRole,
        displayName: String,
        policy: RamPolicy,
        expectedSizeBytes: Long = -1L,
    ): LlamaSession {
        NativeBridge.requireLoaded("llama.cpp")
        if (!isAvailable()) {
            throw NativeRuntimeUnavailableException("llama.cpp", unavailabilityReason() ?: "unknown")
        }
        if (role == ModelRole.VISION_SCREEN_AGENT && !isVisionAvailable()) {
            throw NativeRuntimeUnavailableException(
                "llama.cpp mtmd (vision)",
                "This native build has no multimodal (mtmd) support, so the screen-agent VLM " +
                    "cannot be loaded. Screen reading through the accessibility service still " +
                    "works; only pixel-level grounding is unavailable.",
            )
        }

        val modelFile = VaultModelFile.open(context, fileSystem, modelVaultPath, expectedSizeBytes)
        val mmprojFile = mmprojVaultPath?.let {
            VaultModelFile.open(context, fileSystem, it, -1L)
        }

        val handle = NativeBridge.nativeLlamaLoad(
            modelPath = modelFile.nativePath,
            mmprojPath = mmprojFile?.nativePath,
            nCtx = policy.contextTokens(role),
            nBatch = policy.batchTokens(),
            nThreads = policy.inferenceThreads(),
            // CPU only: the Android builds Sarothi ships do not enable a GPU backend,
            // and offloading layers on a low-RAM phone would compete with the display.
            nGpuLayers = 0,
            useMmap = true,
            seed = -1L,
        )
        if (handle <= 0) {
            val reason = NativeBridge.lastErrorOr("llama.cpp returned error code $handle")
            runCatching { modelFile.close() }
            mmprojFile?.let { runCatching { it.close() } }
            throw NativeRuntimeUnavailableException(
                "llama.cpp model ${modelVaultPath.substringAfterLast('/')}",
                reason,
            )
        }

        val info = NativeBridge.nativeLlamaInfo(handle)?.let { text ->
            runCatching { Json.parseObject(text) }.getOrNull()
        }
        return LlamaSession(
            handle = handle,
            modelFile = modelFile,
            mmprojFile = mmprojFile,
            role = role,
            displayName = displayName,
            info = info,
            contextTokens = policy.contextTokens(role),
        ).also { session ->
            // Tracked so a task cancellation can stop a generation that is still
            // running in native code; without this the cancel flag would never
            // reach a session the caller lost track of.
            liveSessions += session
        }
    }

    /**
     * Generates a completion.
     *
     * @param image encoded screenshot bytes (JPEG/PNG). Only valid for a session
     *   loaded with an mmproj; passing it otherwise is an error, not a silent no-op.
     * @param onToken streaming callback; return false to stop generation early.
     */
    suspend fun generate(
        session: LlamaSession,
        prompt: String,
        params: GenerationParams = GenerationParams.CHAT,
        image: ByteArray? = null,
        onToken: ((String) -> Boolean)? = null,
    ): GenerationResult = session.mutex.withLock {
        check(!session.closed) { "This model session has been closed" }
        if (image != null && session.mmprojFile == null) {
            return@withLock GenerationResult(
                text = "",
                reason = CompletionReason.ERROR,
                piecesEmitted = 0,
                elapsedMillis = 0,
                errorMessage = "An image was passed to a text-only model session " +
                    "(${session.displayName}). Load the vision model with its mmproj first.",
            )
        }

        val configured = NativeBridge.nativeLlamaConfigureSampling(
            handle = session.handle,
            temperature = params.temperature,
            topK = params.topK,
            topP = params.topP,
            jsonOnly = params.jsonOnly,
            seed = params.seed,
        )
        if (!configured) {
            return@withLock GenerationResult(
                text = "",
                reason = CompletionReason.ERROR,
                piecesEmitted = 0,
                elapsedMillis = 0,
                errorMessage = NativeBridge.lastErrorOr("could not configure sampling"),
            )
        }

        val builder = StringBuilder()
        var pieces = 0
        var stoppedByCaller = false
        val callback = NativeBridge.TokenCallback { piece ->
            builder.append(piece)
            pieces++
            // The callback runs on this same thread (native code calls it inline
            // between decode steps), so no synchronisation is needed.
            if (onToken == null) true else onToken(piece).also { if (!it) stoppedByCaller = true }
        }

        val startedAt = System.currentTimeMillis()
        val code = NativeBridge.nativeLlamaGenerate(
            handle = session.handle,
            prompt = prompt,
            image = image,
            maxTokens = params.maxTokens,
            callback = callback,
        )
        val elapsed = System.currentTimeMillis() - startedAt
        val text = builder.toString()

        when {
            code == NativeBridge.RESULT_OK -> GenerationResult(
                text = text,
                // The native loop stops either on an end-of-generation token or when
                // the token budget runs out; distinguish them so a truncated plan is
                // reported as truncated rather than as a complete answer.
                reason = if (pieces >= params.maxTokens) CompletionReason.TOKEN_BUDGET
                else CompletionReason.END_OF_GENERATION,
                piecesEmitted = pieces,
                elapsedMillis = elapsed,
                // A clean return with no output at all is still worth reporting: the
                // prompt almost certainly did not fit the context window.
                errorMessage = if (text.isBlank()) {
                    "The model produced no output. The prompt may not fit in this session's " +
                        "${session.contextTokens}-token context window."
                } else {
                    null
                },
            )
            code == NativeBridge.RESULT_CANCELLED -> GenerationResult(
                text, CompletionReason.CANCELLED, pieces, elapsed,
                if (stoppedByCaller) "Stopped by the caller" else null,
            )
            code == NativeBridge.ERROR_OUT_OF_MEMORY -> GenerationResult(
                text, CompletionReason.ERROR, pieces, elapsed,
                "llama.cpp ran out of memory. Unload the vision model or lower the context size.",
            )
            else -> GenerationResult(
                text, CompletionReason.ERROR, pieces, elapsed,
                NativeBridge.lastErrorOr("llama.cpp generation failed with code $code"),
            )
        }
    }

    /** Cancels an in-flight generation from another thread. */
    fun cancel(session: LlamaSession) {
        if (session.closed) {
            liveSessions -= session
            return
        }
        runCatching { NativeBridge.nativeLlamaCancel(session.handle) }
    }

    /**
     * Cancels every live session.
     *
     * Used by the agent's stop button: at that point the caller may hold a
     * reference to the orchestrator, the vision session, or neither, and a
     * generation that keeps running in native code would keep burning CPU on a
     * phone the user is trying to use.
     */
    fun cancelAll() {
        liveSessions.toList().forEach { session -> cancel(session) }
    }

    /** Drops closed sessions from the tracking list. */
    fun pruneClosedSessions() {
        liveSessions.removeAll { it.closed }
    }

    private val liveSessions =
        java.util.Collections.synchronizedList(mutableListOf<LlamaSession>())

    companion object {
        /** Field in the native info JSON that reports the architecture. */
        fun architectureOf(session: LlamaSession): String? = session.info?.stringOrNull("arch")
    }
}

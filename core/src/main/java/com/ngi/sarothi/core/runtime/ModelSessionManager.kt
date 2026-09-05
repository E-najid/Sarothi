package com.ngi.sarothi.core.runtime

import com.ngi.sarothi.core.error.ModelNotInstalledException
import com.ngi.sarothi.core.model.CatalogModel
import com.ngi.sarothi.core.model.ModelCatalog
import com.ngi.sarothi.core.model.ModelRole
import com.ngi.sarothi.core.storage.ModelState
import com.ngi.sarothi.core.storage.VaultManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What is loaded right now, for the Settings → Models screen. */
data class SessionStatus(
    val orchestrator: String?,
    val orchestratorContextTokens: Int?,
    val vision: String?,
    val speech: String?,
    val tier: MemoryTier,
    val mayKeepVisionResident: Boolean,
    val description: String,
)

/**
 * Owns model lifetimes according to the device's measured RAM.
 *
 * Rules enforced here, not left to callers:
 *  - the text orchestrator is loaded once and stays resident (it is the agent);
 *  - the vision model is loaded per screen task and, on constrained tiers,
 *    released immediately afterwards — never kept "just in case";
 *  - STT/TTS sessions are released after use on constrained tiers;
 *  - at most [RamPolicy.maxResidentModels] sessions exist at once; loading another
 *    evicts the least recently used optional session first;
 *  - a load is refused with a clear message when free RAM cannot accommodate it,
 *    rather than proceeding and getting the whole app killed by the system.
 */
class ModelSessionManager(
    private val vaultManager: VaultManager,
    private val policy: RamPolicy,
    private val llama: LlamaRuntime,
    private val whisper: WhisperRuntime,
    private val scope: CoroutineScope,
) {

    private val mutex = Mutex()

    @Volatile private var orchestratorSession: LlamaSession? = null
    @Volatile private var visionSession: LlamaSession? = null
    @Volatile private var whisperSession: WhisperSession? = null

    /** Which orchestrator the user selected; defaults to the small LFM2.5-350M. */
    var orchestratorModelId: String = DEFAULT_ORCHESTRATOR
    var visionModelId: String = DEFAULT_VISION
    var visionProjectionId: String = DEFAULT_VISION_PROJECTION
    var speechModelId: String = DEFAULT_SPEECH

    private var visionIdleJob: Job? = null
    private var speechIdleJob: Job? = null

    fun status(): SessionStatus = SessionStatus(
        orchestrator = orchestratorSession?.displayName,
        orchestratorContextTokens = orchestratorSession?.contextTokens,
        vision = visionSession?.displayName,
        speech = whisperSession?.displayName,
        tier = policy.tier,
        mayKeepVisionResident = policy.mayKeepVisionResident(),
        description = policy.describe(),
    )

    /** The resident text model, loaded on first use. */
    suspend fun orchestrator(): LlamaSession = mutex.withLock {
        orchestratorSession?.takeIf { !it.closed } ?: loadOrchestratorLocked()
    }

    private suspend fun loadOrchestratorLocked(): LlamaSession {
        val model = requireModel(orchestratorModelId, ModelRole.TEXT_ORCHESTRATOR)
        evictOptionalLocked()
        return withContext(Dispatchers.IO) {
            ensureVerified(model)
            if (!policy.canLoad(model.approximateRamBytes)) {
                throw ModelNotInstalledException(
                    modelId = model.id,
                    expectedFileName = model.fileName,
                    reason = "the model is installed but this device does not have enough free " +
                        "RAM to load it right now (${policy.memory.availableMiB} MiB available, " +
                        "${model.approximateRamBytes / DeviceMemory.MIB} MiB needed plus a safety " +
                        "margin). Close other apps and try again.",
                )
            }
            llama.load(
                fileSystem = vaultManager.requireFileSystem(),
                modelVaultPath = model.vaultPath,
                mmprojVaultPath = null,
                role = ModelRole.TEXT_ORCHESTRATOR,
                displayName = model.displayName,
                policy = policy,
                expectedSizeBytes = model.sizeBytes,
            )
        }.also { orchestratorSession = it }
    }

    /**
     * Runs [block] with a loaded vision session and then applies the tier's
     * residency rule. The session is never left loaded by accident: `withVision`
     * is the only way to reach it.
     */
    suspend fun <T> withVision(block: suspend (LlamaSession) -> T): T {
        val session = mutex.withLock {
            visionSession?.takeIf { !it.closed } ?: loadVisionLocked()
        }
        return try {
            block(session)
        } finally {
            scheduleVisionRelease()
        }
    }

    private suspend fun loadVisionLocked(): LlamaSession {
        val model = requireModel(visionModelId, ModelRole.VISION_SCREEN_AGENT)
        val projection = requireModel(visionProjectionId, ModelRole.VISION_PROJECTION)

        val combined = model.approximateRamBytes + projection.approximateRamBytes
        // Free what we can before giving up: the vision model is only ever a
        // fallback, so dropping it is always the right trade.
        releaseVisionNow()
        evictOptionalLocked()
        return withContext(Dispatchers.IO) {
            ensureVerified(model)
            ensureVerified(projection)
            if (!policy.canLoad(combined)) {
                throw ModelNotInstalledException(
                    modelId = model.id,
                    expectedFileName = model.fileName,
                    reason = "not enough free RAM for the vision model plus its projector " +
                        "(needs ~${combined / DeviceMemory.MIB} MiB, " +
                        "${policy.memory.availableMiB} MiB available). The accessibility-tree " +
                        "screen reader still works without it.",
                )
            }
            llama.load(
                fileSystem = vaultManager.requireFileSystem(),
                modelVaultPath = model.vaultPath,
                mmprojVaultPath = projection.vaultPath,
                role = ModelRole.VISION_SCREEN_AGENT,
                displayName = model.displayName,
                policy = policy,
                expectedSizeBytes = model.sizeBytes,
            )
        }.also { visionSession = it }
    }

    suspend fun <T> withWhisper(block: suspend (WhisperSession) -> T): T {
        val session = mutex.withLock {
            whisperSession?.takeIf { !it.closed } ?: loadWhisperLocked()
        }
        return try {
            block(session)
        } finally {
            scheduleSpeechRelease()
        }
    }

    private suspend fun loadWhisperLocked(): WhisperSession {
        val model = requireModel(speechModelId, ModelRole.SPEECH_TO_TEXT)
        evictOptionalLocked()
        return withContext(Dispatchers.IO) {
            ensureVerified(model)
            if (!policy.canLoad(model.approximateRamBytes)) {
                throw ModelNotInstalledException(
                    modelId = model.id,
                    expectedFileName = model.fileName,
                    reason = "not enough free RAM to load the speech model right now " +
                        "(${policy.memory.availableMiB} MiB available).",
                )
            }
            whisper.load(
                fileSystem = vaultManager.requireFileSystem(),
                modelVaultPath = model.vaultPath,
                displayName = model.displayName,
                policy = policy,
                expectedSizeBytes = model.sizeBytes,
            )
        }.also { whisperSession = it }
    }

    private fun requireModel(id: String, role: ModelRole): CatalogModel {
        val model = ModelCatalog.byId(id)
            ?: throw ModelNotInstalledException(
                modelId = id,
                expectedFileName = id,
                reason = "'$id' is not in Sarothi's model catalogue. Settings → Models lists " +
                    "the ids that are.",
            )
        if (model.role != role) {
            throw ModelNotInstalledException(
                modelId = id,
                expectedFileName = model.fileName,
                reason = "'${model.displayName}' is a ${model.role} model and cannot be used " +
                    "as a $role model.",
            )
        }
        return model
    }

    /** Refuses to load a model whose bytes do not match what upstream publishes. */
    private fun ensureVerified(model: CatalogModel) {
        when (val state = vaultManager.verifyModel(model)) {
            ModelState.Missing -> throw ModelNotInstalledException(
                model.id, model.fileName, "it has not been downloaded yet",
            )
            is ModelState.SizeMismatch -> throw ModelNotInstalledException(
                model.id, model.fileName,
                "the file is ${state.actualBytes} bytes but upstream publishes ${state.expectedBytes}; " +
                    "it looks like an interrupted download. Re-download it from Settings → Models.",
            )
            is ModelState.Corrupt -> throw ModelNotInstalledException(
                model.id, model.fileName,
                "its checksum does not match upstream (expected ${state.expectedDigest}, " +
                    "got ${state.actualDigest}). The file is corrupt and will not be loaded.",
            )
            is ModelState.PresentUnverified -> Unit // usable, but the UI says it is unverified
            is ModelState.Verified -> Unit
        }
    }

    /**
     * Drops optional sessions when loading one more would exceed the tier's cap.
     * The orchestrator is never evicted: without it there is no agent.
     */
    private fun evictOptionalLocked() {
        var resident = listOfNotNull(
            orchestratorSession?.takeIf { !it.closed },
            visionSession?.takeIf { !it.closed },
            whisperSession?.takeIf { !it.closed },
        ).size + 1

        val cap = policy.maxResidentModels()
        if (resident > cap) {
            whisperSession?.takeIf { !it.closed }?.let { runCatching { it.close() } }
            whisperSession = null
            resident--
        }
        if (resident > cap) {
            releaseVisionNow()
            resident--
        }
    }

    private fun scheduleVisionRelease() {
        visionIdleJob?.cancel()
        val timeout = policy.visionIdleTimeoutMillis()
        if (timeout <= 0L) {
            // Constrained tiers: the model is released as soon as the task ends.
            releaseVisionNow()
            return
        }
        visionIdleJob = scope.launch {
            delay(timeout)
            mutex.withLock { releaseVisionNow() }
        }
    }

    private fun scheduleSpeechRelease() {
        speechIdleJob?.cancel()
        val timeout = policy.speechIdleTimeoutMillis()
        if (timeout <= 0L) {
            whisperSession?.takeIf { !it.closed }?.let { runCatching { it.close() } }
            whisperSession = null
            return
        }
        speechIdleJob = scope.launch {
            delay(timeout)
            mutex.withLock {
                whisperSession?.takeIf { !it.closed }?.let { runCatching { it.close() } }
                whisperSession = null
            }
        }
    }

    private fun releaseVisionNow() {
        visionIdleJob?.cancel()
        visionIdleJob = null
        visionSession?.takeIf { !it.closed }?.let { runCatching { it.close() } }
        visionSession = null
    }

    fun releaseVision() = scope.launch { mutex.withLock { releaseVisionNow() } }

    /** Releases everything, including the orchestrator. Used on lock and on low memory. */
    fun releaseAll() {
        visionIdleJob?.cancel()
        speechIdleJob?.cancel()
        runCatching { visionSession?.close() }
        runCatching { whisperSession?.close() }
        runCatching { orchestratorSession?.close() }
        visionSession = null
        whisperSession = null
        orchestratorSession = null
    }

    /** Switches orchestrator model; the new one is loaded lazily on next use. */
    suspend fun selectOrchestrator(modelId: String) = mutex.withLock {
        requireModel(modelId, ModelRole.TEXT_ORCHESTRATOR)
        orchestratorModelId = modelId
        orchestratorSession?.takeIf { !it.closed }?.let { runCatching { it.close() } }
        orchestratorSession = null
    }

    companion object {
        const val DEFAULT_ORCHESTRATOR = "text.lfm2.5-350m.q4_0"
        const val DEFAULT_VISION = "vision.lfm2.5-vl-450m.q4_0"
        const val DEFAULT_VISION_PROJECTION = "vision.lfm2.5-vl-450m.mmproj.q8_0"
        const val DEFAULT_SPEECH = "stt.whisper-base-q5_1"
    }
}

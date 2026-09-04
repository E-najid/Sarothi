package com.ngi.sarothi.core.model

/** What a model is used for. Determines when it may be loaded and unloaded. */
enum class ModelRole {
    /** Small instruct model: conversation, intent, planning, tool calling. Always resident. */
    TEXT_ORCHESTRATOR,

    /** GUI-grounding VLM. Loaded on demand for screen tasks, unloaded when idle. */
    VISION_SCREEN_AGENT,

    /** The vision projector (mmproj) that pairs with a [VISION_SCREEN_AGENT] model. */
    VISION_PROJECTION,

    /** whisper.cpp ggml model. Loaded only while transcribing. */
    SPEECH_TO_TEXT,

    /** Piper ONNX voice plus its config. Loaded only while speaking. */
    TEXT_TO_SPEECH,
}

/**
 * How a downloaded file's integrity is proven.
 *
 * Hugging Face exposes a real SHA-256 for Git-LFS files (the LFS object id) but
 * only a Git blob SHA-1 for regular files. Both are genuine, verifiable digests of
 * the exact bytes, so Sarothi pins whichever upstream actually publishes rather
 * than inventing a hash. Anything with no published digest falls back to
 * [SIZE_ONLY] and is recorded in the manifest as *unverified* — the UI says so
 * plainly instead of claiming a checksum passed.
 */
enum class ChecksumPolicy {
    /** Git-LFS object id, which is the file's SHA-256. */
    SHA256_PINNED,

    /** Git blob digest: SHA-1 of `"blob <length>\0<content>"`. */
    GIT_BLOB_SHA1_PINNED,

    /** No digest published upstream. Size is checked; integrity is not proven. */
    SIZE_ONLY,
}

/** One place a model can be fetched from, tried in order. */
data class ModelSource(
    val url: String,
    val label: String,
)

data class CatalogModel(
    val id: String,
    val role: ModelRole,
    val displayName: String,
    /** File name inside the vault's `models/` directory. */
    val fileName: String,
    val sizeBytes: Long,
    val checksumPolicy: ChecksumPolicy,
    /** SHA-256 hex, when [checksumPolicy] is [ChecksumPolicy.SHA256_PINNED]. */
    val sha256: String? = null,
    /** Git blob SHA-1 hex, when [checksumPolicy] is [ChecksumPolicy.GIT_BLOB_SHA1_PINNED]. */
    val gitBlobSha1: String? = null,
    val sources: List<ModelSource>,
    /** Must be present before Sarothi can do anything useful. */
    val required: Boolean,
    val description: String,
    /** Where to get the file by hand when every automated source fails. */
    val manualInstructions: String,
    /** BCP-47 tag when the artifact is language-specific. */
    val language: String? = null,
    /** For a [ModelRole.VISION_PROJECTION]: the model it must be loaded with. */
    val companionOf: String? = null,
    /** Rough resident memory once loaded, for the RAM policy. */
    val approximateRamBytes: Long,
) {
    init {
        require(sources.isNotEmpty()) { "model '$id' has no download source" }
        when (checksumPolicy) {
            ChecksumPolicy.SHA256_PINNED -> require(!sha256.isNullOrBlank()) {
                "model '$id' declares SHA256_PINNED but has no sha256"
            }
            ChecksumPolicy.GIT_BLOB_SHA1_PINNED -> require(!gitBlobSha1.isNullOrBlank()) {
                "model '$id' declares GIT_BLOB_SHA1_PINNED but has no gitBlobSha1"
            }
            ChecksumPolicy.SIZE_ONLY -> Unit
        }
    }

    val vaultPath: String get() = "${com.ngi.sarothi.core.storage.VaultPaths.MODELS_DIR}/$fileName"
}

/**
 * The models Sarothi downloads, with **real digests taken from upstream**.
 *
 * Every SHA-256 below is the Git-LFS object id published by the hosting
 * repository at the time the catalogue was written; every size is the published
 * file size. `scripts/verify_model_catalog.py` re-checks both against the
 * Hugging Face API so the catalogue cannot silently drift.
 *
 * Deliberate choices for 3 GB devices:
 *  - The orchestrator is LFM2.5-350M at Q4_0: a 219 MB file whose resident set
 *    stays well under the ~500 MB budget, so it can remain loaded.
 *  - The screen agent is LFM2.5-VL-450M (Q4_0 + Q8_0 mmproj, 322 MB together)
 *    rather than a 1.6B/3B VLM, because it is affordable to load on demand and
 *    still does GUI grounding. It is unloaded as soon as the screen task ends.
 *  - STT is whisper `base` at q5_1 (57 MB), which is the *multilingual* base
 *    model and therefore carries the Bengali decoder. The `.en` variants are not
 *    offered, since they cannot transcribe বাংলা at all.
 *  - TTS is the official Piper Bengali (Bangladesh) medium voice.
 */
object ModelCatalog {

    /** Hugging Face mirror used as the second source when the primary is unreachable. */
    private const val HF_MIRROR = "https://hf-mirror.com"

    private fun hfSources(repo: String, pathInRepo: String): List<ModelSource> {
        val encoded = pathInRepo.split('/').joinToString("/") { it }
        return listOf(
            ModelSource("https://huggingface.co/$repo/resolve/main/$encoded", "Hugging Face"),
            ModelSource("$HF_MIRROR/$repo/resolve/main/$encoded", "hf-mirror.com"),
        )
    }

    val TEXT_ORCHESTRATOR_LFM2_350M_Q4_0 = CatalogModel(
        id = "text.lfm2.5-350m.q4_0",
        role = ModelRole.TEXT_ORCHESTRATOR,
        displayName = "LFM2.5-350M (Q4_0)",
        fileName = "LFM2.5-350M-Q4_0.gguf",
        sizeBytes = 219_309_792L,
        checksumPolicy = ChecksumPolicy.SHA256_PINNED,
        sha256 = "85e32858daafad55b7bcd6b97a1343ee0661188e8036f9862d14d6b563142f50",
        sources = hfSources("LiquidAI/LFM2.5-350M-GGUF", "LFM2.5-350M-Q4_0.gguf"),
        required = true,
        description = "Default text/orchestrator model. Liquid LFM2.5-350M quantised to Q4_0; " +
            "handles conversation, intent, planning and tool calling.",
        manualInstructions = "Download LFM2.5-350M-Q4_0.gguf from " +
            "https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF/tree/main and copy it into the " +
            "vault's models/ folder, then re-run 'Verify models' in Settings → Models.",
        approximateRamBytes = 260L * 1024 * 1024,
    )

    val TEXT_ORCHESTRATOR_LFM2_350M_Q4_K_M = CatalogModel(
        id = "text.lfm2.5-350m.q4_k_m",
        role = ModelRole.TEXT_ORCHESTRATOR,
        displayName = "LFM2.5-350M (Q4_K_M)",
        fileName = "LFM2.5-350M-Q4_K_M.gguf",
        sizeBytes = 229_312_224L,
        checksumPolicy = ChecksumPolicy.SHA256_PINNED,
        sha256 = "7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4",
        sources = hfSources("LiquidAI/LFM2.5-350M-GGUF", "LFM2.5-350M-Q4_K_M.gguf"),
        required = false,
        description = "Slightly better quality than Q4_0 at 10 MB more. Optional alternative " +
            "orchestrator for devices with headroom.",
        manualInstructions = "Download LFM2.5-350M-Q4_K_M.gguf from " +
            "https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF/tree/main into the vault's models/ folder.",
        approximateRamBytes = 275L * 1024 * 1024,
    )

    val TEXT_ORCHESTRATOR_QWEN25_05B_Q4_0 = CatalogModel(
        id = "text.qwen2.5-0.5b-instruct.q4_0",
        role = ModelRole.TEXT_ORCHESTRATOR,
        displayName = "Qwen2.5-0.5B-Instruct (Q4_0)",
        fileName = "qwen2.5-0.5b-instruct-q4_0.gguf",
        sizeBytes = 428_730_208L,
        checksumPolicy = ChecksumPolicy.SHA256_PINNED,
        sha256 = "7671c0c304e6ce5a7fc577bcb12aba01e2c155cc2efd29b2213c95b18edaf6ed",
        sources = hfSources("Qwen/Qwen2.5-0.5B-Instruct-GGUF", "qwen2.5-0.5b-instruct-q4_0.gguf"),
        required = false,
        description = "Alternative orchestrator with stronger multilingual instruction following. " +
            "Twice the size of LFM2.5-350M, so it is not the default on 3 GB devices.",
        manualInstructions = "Download qwen2.5-0.5b-instruct-q4_0.gguf from " +
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/tree/main into models/.",
        approximateRamBytes = 480L * 1024 * 1024,
    )

    val VISION_LFM25_VL_450M_Q4_0 = CatalogModel(
        id = "vision.lfm2.5-vl-450m.q4_0",
        role = ModelRole.VISION_SCREEN_AGENT,
        displayName = "LFM2.5-VL-450M (Q4_0)",
        fileName = "LFM2.5-VL-450M-Q4_0.gguf",
        sizeBytes = 219_311_264L,
        checksumPolicy = ChecksumPolicy.SHA256_PINNED,
        sha256 = "6d2757dd0f0b98aea7dc90477bb5b3a0df1089be85ef92943f8cecb05121ccbf",
        sources = hfSources("LiquidAI/LFM2.5-VL-450M-GGUF", "LFM2.5-VL-450M-Q4_0.gguf"),
        required = false,
        description = "Screen agent VLM. Read only when the accessibility tree is not enough " +
            "(canvases, games, custom-drawn surfaces); unloaded when idle.",
        manualInstructions = "Download LFM2.5-VL-450M-Q4_0.gguf from " +
            "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-GGUF/tree/main into models/, along with " +
            "its mmproj file.",
        companionOf = null,
        approximateRamBytes = 320L * 1024 * 1024,
    )

    val VISION_LFM25_VL_450M_MMPROJ = CatalogModel(
        id = "vision.lfm2.5-vl-450m.mmproj.q8_0",
        role = ModelRole.VISION_PROJECTION,
        displayName = "LFM2.5-VL-450M mmproj (Q8_0)",
        fileName = "mmproj-LFM2.5-VL-450m-Q8_0.gguf",
        sizeBytes = 102_815_168L,
        checksumPolicy = ChecksumPolicy.SHA256_PINNED,
        sha256 = "ebfc428baa37efad8bae93864f914b2634a09009f91ad59f974fe1a1565d8561",
        sources = hfSources("LiquidAI/LFM2.5-VL-450M-GGUF", "mmproj-LFM2.5-VL-450m-Q8_0.gguf"),
        required = false,
        description = "Vision projector required by LFM2.5-VL-450M. llama.cpp loads the language " +
            "model and the mmproj together.",
        manualInstructions = "Download mmproj-LFM2.5-VL-450m-Q8_0.gguf from " +
            "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-GGUF/tree/main into models/.",
        companionOf = "vision.lfm2.5-vl-450m.q4_0",
        approximateRamBytes = 110L * 1024 * 1024,
    )

    val STT_WHISPER_BASE_Q5_1 = CatalogModel(
        id = "stt.whisper-base-q5_1",
        role = ModelRole.SPEECH_TO_TEXT,
        displayName = "Whisper base (q5_1, multilingual)",
        fileName = "ggml-base-q5_1.bin",
        sizeBytes = 59_707_625L,
        checksumPolicy = ChecksumPolicy.SHA256_PINNED,
        sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
        sources = hfSources("ggerganov/whisper.cpp", "ggml-base-q5_1.bin"),
        required = false,
        description = "whisper.cpp speech-to-text. This is the *multilingual* base model, so it " +
            "transcribes Bengali as well as English. The `.en` models are deliberately not offered.",
        manualInstructions = "Download ggml-base-q5_1.bin from " +
            "https://huggingface.co/ggerganov/whisper.cpp/tree/main into models/.",
        language = "multilingual",
        approximateRamBytes = 120L * 1024 * 1024,
    )

    val STT_WHISPER_BASE_F16 = CatalogModel(
        id = "stt.whisper-base-f16",
        role = ModelRole.SPEECH_TO_TEXT,
        displayName = "Whisper base (f16, multilingual)",
        fileName = "ggml-base.bin",
        sizeBytes = 147_951_465L,
        checksumPolicy = ChecksumPolicy.SHA256_PINNED,
        sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        sources = hfSources("ggerganov/whisper.cpp", "ggml-base.bin"),
        required = false,
        description = "Unquantised multilingual base model, for users who prefer accuracy over " +
            "size on devices with more RAM.",
        manualInstructions = "Download ggml-base.bin from " +
            "https://huggingface.co/ggerganov/whisper.cpp/tree/main into models/.",
        language = "multilingual",
        approximateRamBytes = 250L * 1024 * 1024,
    )

    val TTS_PIPER_BN_BD_MEDIUM = CatalogModel(
        id = "tts.piper.bn_bd.medium",
        role = ModelRole.TEXT_TO_SPEECH,
        displayName = "Piper বাংলা (bn_BD, medium)",
        fileName = "bn_BD-google-medium.onnx",
        sizeBytes = 76_782_515L,
        checksumPolicy = ChecksumPolicy.SHA256_PINNED,
        sha256 = "f2e7518ed5534a755024a48c71b80bf617efaf12570bbdf3ce255a9526a8afd3",
        sources = hfSources(
            "rhasspy/piper-voices",
            "bn/bn_BD/google/medium/bn_BD-google-medium.onnx",
        ),
        required = false,
        description = "Piper Bengali (Bangladesh) medium voice: 22.05 kHz, 16 speakers, espeak-ng " +
            "phonemes, driven by ONNX Runtime on device.",
        manualInstructions = "Download bn_BD-google-medium.onnx and bn_BD-google-medium.onnx.json " +
            "from https://huggingface.co/rhasspy/piper-voices/tree/main/bn/bn_BD/google/medium " +
            "into models/.",
        language = "bn",
        approximateRamBytes = 120L * 1024 * 1024,
    )

    val TTS_PIPER_BN_BD_MEDIUM_CONFIG = CatalogModel(
        id = "tts.piper.bn_bd.medium.config",
        role = ModelRole.TEXT_TO_SPEECH,
        displayName = "Piper বাংলা voice config",
        fileName = "bn_BD-google-medium.onnx.json",
        sizeBytes = 5_494L,
        // Not an LFS object, so Hugging Face publishes no SHA-256 for it. The Git
        // blob digest is pinned instead — it is a real digest of the exact bytes.
        checksumPolicy = ChecksumPolicy.GIT_BLOB_SHA1_PINNED,
        gitBlobSha1 = "ee79e469edaed486747fb5f05067ed04f0d3d201",
        sources = hfSources(
            "rhasspy/piper-voices",
            "bn/bn_BD/google/medium/bn_BD-google-medium.onnx.json",
        ),
        required = false,
        description = "Voice configuration for the Piper Bengali model: sample rate, phoneme id " +
            "map, speaker map and inference defaults. Read at runtime, never hardcoded.",
        manualInstructions = "Download bn_BD-google-medium.onnx.json from " +
            "https://huggingface.co/rhasspy/piper-voices/tree/main/bn/bn_BD/google/medium into models/.",
        language = "bn",
        companionOf = "tts.piper.bn_bd.medium",
        approximateRamBytes = 0,
    )

    /** Every model Sarothi knows how to download. */
    val ALL: List<CatalogModel> = listOf(
        TEXT_ORCHESTRATOR_LFM2_350M_Q4_0,
        TEXT_ORCHESTRATOR_LFM2_350M_Q4_K_M,
        TEXT_ORCHESTRATOR_QWEN25_05B_Q4_0,
        VISION_LFM25_VL_450M_Q4_0,
        VISION_LFM25_VL_450M_MMPROJ,
        STT_WHISPER_BASE_Q5_1,
        STT_WHISPER_BASE_F16,
        TTS_PIPER_BN_BD_MEDIUM,
        TTS_PIPER_BN_BD_MEDIUM_CONFIG,
    )

    fun byId(id: String): CatalogModel? = ALL.firstOrNull { it.id == id }

    fun byFileName(fileName: String): CatalogModel? = ALL.firstOrNull { it.fileName == fileName }

    fun forRole(role: ModelRole): List<CatalogModel> = ALL.filter { it.role == role }

    /** The models a brand-new install needs before Sarothi can answer anything. */
    val REQUIRED: List<CatalogModel> = ALL.filter { it.required }

    /**
     * The default install set: one orchestrator, plus the optional models that make
     * screen, voice-in and voice-out work. Chosen to stay inside the RAM budget.
     */
    val DEFAULT_INSTALL: List<CatalogModel> = listOf(
        TEXT_ORCHESTRATOR_LFM2_350M_Q4_0,
        VISION_LFM25_VL_450M_Q4_0,
        VISION_LFM25_VL_450M_MMPROJ,
        STT_WHISPER_BASE_Q5_1,
        TTS_PIPER_BN_BD_MEDIUM,
        TTS_PIPER_BN_BD_MEDIUM_CONFIG,
    )
}

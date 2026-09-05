package com.ngi.sarothi.core.runtime

import com.ngi.sarothi.core.error.NativeRuntimeUnavailableException

/**
 * Raw JNI surface of `libsarothi_native.so`.
 *
 * Every method here has a real implementation in `core/src/main/cpp/`. When the
 * native library was not compiled into this build (no `third_party/` sources, or
 * `-Psarothi.skipNative=true`), [isLoaded] is false and
 * [requireLoaded] throws [NativeRuntimeUnavailableException] with the actual
 * `UnsatisfiedLinkError` message — Sarothi never substitutes a canned response for
 * model output.
 *
 * The C++ entry points are named
 * `Java_com_ngi_sarothi_core_runtime_NativeBridge_<method>`, so this object's name
 * and package are part of the ABI and must not be renamed without updating
 * `sarothi_llama.cpp`, `sarothi_whisper.cpp` and `sarothi_phonemizer.cpp`.
 */
object NativeBridge {

    /** Streaming callback: return false to stop generation. */
    fun interface TokenCallback {
        fun onToken(piece: String): Boolean
    }

    fun interface ProgressCallback {
        fun onProgress(percent: Int)
    }

    // Return codes mirrored from sarothi_llama.cpp / sarothi_whisper.cpp.
    const val RESULT_OK = 0
    const val RESULT_CANCELLED = 1
    const val ERROR_UNAVAILABLE = -1
    const val ERROR_BAD_HANDLE = -2
    const val ERROR_LOAD = -3
    const val ERROR_DECODE = -4
    const val ERROR_UNSUPPORTED = -5
    const val ERROR_OUT_OF_MEMORY = -6

    private const val LIBRARY_NAME = "sarothi_native"

    private data class LoadOutcome(val loaded: Boolean, val failure: String?)

    private val loadOutcome: LoadOutcome by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            System.loadLibrary(LIBRARY_NAME)
            LoadOutcome(true, null)
        } catch (missing: UnsatisfiedLinkError) {
            LoadOutcome(false, "System.loadLibrary(\"$LIBRARY_NAME\") failed: ${missing.message}")
        } catch (failure: Throwable) {
            LoadOutcome(
                false,
                "Loading $LIBRARY_NAME threw ${failure.javaClass.name}: ${failure.message}",
            )
        }
    }

    val isLoaded: Boolean get() = loadOutcome.loaded

    /** Exact reason the native runtime is missing, for display in Settings → Models. */
    val loadFailure: String? get() = loadOutcome.failure

    fun requireLoaded(component: String) {
        if (!loadOutcome.loaded) {
            throw NativeRuntimeUnavailableException(
                component = component,
                reason = loadOutcome.failure
                    ?: "libsarothi_native.so is not present in this APK. Build it with " +
                    "scripts/setup_native.sh followed by a normal Gradle build.",
            )
        }
    }

    // ------------------------------------------------------------------ llama

    external fun nativeLlamaRuntimeAvailable(): Boolean
    external fun nativeLlamaVisionAvailable(): Boolean
    external fun nativeLastError(): String

    external fun nativeLlamaLoad(
        modelPath: String,
        mmprojPath: String?,
        nCtx: Int,
        nBatch: Int,
        nThreads: Int,
        nGpuLayers: Int,
        useMmap: Boolean,
        seed: Long,
    ): Long

    external fun nativeLlamaFree(handle: Long)
    external fun nativeLlamaCancel(handle: Long)
    external fun nativeLlamaInfo(handle: Long): String?

    external fun nativeLlamaConfigureSampling(
        handle: Long,
        temperature: Float,
        topK: Int,
        topP: Float,
        jsonOnly: Boolean,
        seed: Long,
    ): Boolean

    external fun nativeLlamaGenerate(
        handle: Long,
        prompt: String,
        image: ByteArray?,
        maxTokens: Int,
        callback: TokenCallback?,
    ): Int

    // ---------------------------------------------------------------- whisper

    external fun nativeWhisperRuntimeAvailable(): Boolean
    external fun nativeWhisperLoad(modelPath: String, nThreads: Int): Long
    external fun nativeWhisperFree(handle: Long)

    external fun nativeWhisperTranscribe(
        handle: Long,
        pcm: FloatArray,
        language: String,
        translate: Boolean,
        maxTokens: Int,
        progress: ProgressCallback?,
    ): String?

    external fun nativeWhisperDetectedLanguage(handle: Long): String?

    // --------------------------------------------------- espeak-ng (Piper TTS)

    external fun nativePhonemizerAvailable(): Boolean
    external fun nativePhonemizerInit(dataPath: String): Int
    external fun nativePhonemizerShutdown()
    external fun nativePhonemize(text: String, voice: String, espeakMode: Int, phonemeMode: Int): String?

    /** Reads and clears the last native error message for the calling thread. */
    fun lastErrorOr(fallback: String): String {
        if (!loadOutcome.loaded) return loadOutcome.failure ?: fallback
        return runCatching { nativeLastError() }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
    }
}

package com.ngi.sarothi.core.runtime

import android.content.Context
import com.ngi.sarothi.core.error.NativeRuntimeUnavailableException
import java.io.File

/**
 * espeak-ng used purely as a phonemiser for Piper TTS.
 *
 * Piper voice models consume IPA phoneme ids, not text. The Bengali voice in the
 * catalogue declares `"phoneme_type": "espeak"` and `"espeak": {"voice": "bn"}`,
 * so বাংলা text has to go through espeak-ng exactly as `piper-phonemize` does on
 * desktop before it can be fed to the ONNX model.
 *
 * espeak-ng is autotools-based and needs its `espeak-ng-data` tree at runtime, so
 * `scripts/build_espeak_ng.sh` cross-compiles the library and installs the data
 * into `core/src/main/assets/espeak-ng-data`. This class extracts that tree to
 * app-private storage on first use (the native code needs a real directory path)
 * and reports precisely which piece is missing when it is absent.
 */
class EspeakPhonemizer(private val context: Context) {

    enum class Status { READY, NO_NATIVE_LIBRARY, NO_DATA, INIT_FAILED }

    data class Availability(val status: Status, val detail: String) {
        val isReady: Boolean get() = status == Status.READY
    }

    private val dataDir: File = File(context.filesDir, "espeak-ng-data")

    @Volatile
    private var initialised = false

    fun availability(): Availability {
        if (!NativeBridge.isLoaded) {
            return Availability(Status.NO_NATIVE_LIBRARY, NativeBridge.loadFailure ?: "libsarothi_native.so is missing")
        }
        if (!runCatching { NativeBridge.nativePhonemizerAvailable() }.getOrDefault(false)) {
            return Availability(
                Status.NO_NATIVE_LIBRARY,
                "libsarothi_native.so was built without espeak-ng. Run scripts/build_espeak_ng.sh " +
                    "and rebuild to enable Piper TTS; the Android system voice is used meanwhile.",
            )
        }
        if (!dataAvailable()) {
            return Availability(
                Status.NO_DATA,
                "espeak-ng is compiled in but its voice data is missing. Expected it in the APK " +
                    "assets under espeak-ng-data/ (installed there by scripts/build_espeak_ng.sh) " +
                    "or already extracted at ${dataDir.absolutePath}.",
            )
        }
        return Availability(Status.READY, "espeak-ng phonemiser ready (data at ${dataDir.absolutePath})")
    }

    private fun dataAvailable(): Boolean {
        if (File(dataDir, PROBE_FILE).isFile) return true
        val assets = runCatching { context.assets.list(ASSET_ROOT)?.isNotEmpty() == true }.getOrDefault(false)
        return assets
    }

    /** Extracts the voice data if needed and initialises espeak-ng. Idempotent. */
    fun initialise(): Availability {
        val availability = availability()
        if (!availability.isReady) return availability
        if (initialised) return availability

        synchronized(this) {
            if (initialised) return availability
            if (!File(dataDir, PROBE_FILE).isFile) {
                val extracted = extractAssets()
                if (!extracted) {
                    return Availability(Status.NO_DATA, "Failed to extract espeak-ng-data from the APK assets")
                }
            }
            // espeak_Initialize wants the *parent* of espeak-ng-data.
            val code = NativeBridge.nativePhonemizerInit(context.filesDir.absolutePath)
            if (code != 0) {
                return Availability(Status.INIT_FAILED, NativeBridge.lastErrorOr("espeak_Initialize returned $code"))
            }
            initialised = true
            return availability
        }
    }

    /**
     * Converts text to IPA phonemes for [voice] (e.g. `"bn"`).
     *
     * @throws NativeRuntimeUnavailableException when the phonemiser is not ready.
     *   Callers must not fall back to raw text: feeding unphonemised Bengali
     *   characters to a Piper model produces noise, not speech.
     */
    fun phonemize(text: String, voice: String): String {
        val availability = initialise()
        if (!availability.isReady) {
            throw NativeRuntimeUnavailableException("espeak-ng phonemiser", availability.detail)
        }
        require(text.isNotEmpty()) { "nothing to phonemise" }
        val result = NativeBridge.nativePhonemize(
            text = text,
            voice = voice,
            // espeakCHARS_UTF8 | espeakPHONEMES | (separator=space << 8), the mode
            // piper-phonemize uses, plus phonememode 0x01 (IPA) | 0x02 (separated).
            espeakMode = ESPEAK_CHARS_UTF8 or ESPEAK_PHONEMES or (SEPARATOR_SPACE shl 8),
            phonemeMode = PHONEME_MODE_IPA or PHONEME_MODE_SEPARATED,
        )
        if (result == null) {
            throw NativeRuntimeUnavailableException(
                "espeak-ng phonemiser",
                NativeBridge.lastErrorOr("espeak_TextToPhonemes returned null for voice '$voice'"),
            )
        }
        return result
    }

    fun shutdown() {
        if (!initialised) return
        runCatching { NativeBridge.nativePhonemizerShutdown() }
        initialised = false
    }

    /** Recursively copies `assets/espeak-ng-data/` into app-private storage. */
    private fun extractAssets(): Boolean {
        dataDir.mkdirs()
        return try {
            copyAssetDir(ASSET_ROOT, dataDir)
            File(dataDir, PROBE_FILE).isFile
        } catch (failure: Exception) {
            false
        }
    }

    private fun copyAssetDir(assetPath: String, destination: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            // A leaf: copy its bytes.
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destination.mkdirs()
        for (child in children) {
            copyAssetDir("$assetPath/$child", File(destination, child))
        }
    }

    companion object {
        private const val ASSET_ROOT = "espeak-ng-data"
        /** Present only when the data tree extracted completely. */
        private const val PROBE_FILE = "phontab"

        // espeak-ng constants (espeak-ng/speak_lib.h).
        private const val ESPEAK_CHARS_UTF8 = 1
        private const val ESPEAK_PHONEMES = 0x0100
        private const val SEPARATOR_SPACE = 0x02
        private const val PHONEME_MODE_IPA = 0x01
        private const val PHONEME_MODE_SEPARATED = 0x02
    }
}

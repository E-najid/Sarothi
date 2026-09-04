package com.ngi.sarothi.core.runtime

import android.content.Context
import com.google.gson.JsonObject
import com.ngi.sarothi.core.error.NativeRuntimeUnavailableException
import com.ngi.sarothi.core.storage.VaultFileSystem
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Everything Piper needs, read from the voice's `.onnx.json` at runtime. */
data class PiperVoiceConfig(
    val sampleRate: Int,
    val quality: String,
    val espeakVoice: String,
    val phonemeType: String,
    val numSymbols: Int,
    val numSpeakers: Int,
    val noiseScale: Float,
    val lengthScale: Float,
    val noiseW: Float,
    val hopLength: Int,
    /** phoneme (or multi-char phoneme) -> id. Keys come straight from the config. */
    val phonemeIdMap: Map<String, Int>,
    val speakerIdMap: Map<String, Int>,
    val languageCode: String?,
    val languageNameNative: String?,
    val piperVersion: String?,
) {
    val bosId: Int get() = phonemeIdMap["^"] ?: 1
    val eosId: Int get() = phonemeIdMap["$"] ?: 2
    val padId: Int get() = phonemeIdMap["_"] ?: 0
    val spaceId: Int get() = phonemeIdMap[" "] ?: 3

    companion object {
        fun parse(json: JsonObject): PiperVoiceConfig {
            val audio = json.getAsJsonObject("audio")
            val espeak = json.getAsJsonObject("espeak")
            val inference = json.getAsJsonObject("inference")
            val language = json.getAsJsonObject("language")

            val phonemeType = json.stringOrNull("phoneme_type")
            require(phonemeType == "espeak") {
                "This Piper voice uses phoneme_type '$phonemeType'. Sarothi implements the " +
                    "'espeak' phonemisation path only; a 'text' voice would need a different " +
                    "phonemiser and is not silently substituted."
            }
            require(espeak != null) { "Piper voice config has no 'espeak' block" }

            val idMapJson = json.getAsJsonObject("phoneme_id_map")
            require(idMapJson != null) { "Piper voice config has no phoneme_id_map" }
            val phonemeIdMap = linkedMapOf<String, Int>()
            for ((key, value) in idMapJson.entrySet()) {
                val array = value.takeIf { it.isJsonArray }?.asJsonArray
                if (array != null && array.size() > 0) phonemeIdMap[key] = array[0].asInt
            }

            val speakerIdMap = linkedMapOf<String, Int>()
            json.getAsJsonObject("speaker_id_map")?.entrySet()?.forEach { (key, value) ->
                if (value.isJsonPrimitive) speakerIdMap[key] = value.asInt
            }

            return PiperVoiceConfig(
                sampleRate = audio?.get("sample_rate")?.asInt ?: 22050,
                quality = audio?.stringOrNull("quality") ?: "unknown",
                espeakVoice = espeak.stringOrNull("voice")
                    ?: throw IllegalArgumentException("Piper voice config has no espeak.voice"),
                phonemeType = phonemeType,
                numSymbols = json.get("num_symbols")?.asInt ?: phonemeIdMap.size,
                numSpeakers = json.get("num_speakers")?.asInt ?: 1,
                noiseScale = inference?.get("noise_scale")?.asFloat ?: 0.667f,
                lengthScale = inference?.get("length_scale")?.asFloat ?: 1.0f,
                noiseW = inference?.get("noise_w")?.asFloat ?: 0.8f,
                hopLength = json.get("hop_length")?.asInt ?: 256,
                phonemeIdMap = phonemeIdMap,
                speakerIdMap = speakerIdMap,
                languageCode = language?.stringOrNull("code"),
                languageNameNative = language?.stringOrNull("name_native"),
                piperVersion = json.stringOrNull("piper_version"),
            )
        }
    }
}

data class SynthesisResult(
    /** A complete 16-bit PCM WAV file, ready for [android.media.AudioTrack] or a file. */
    val wav: ByteArray,
    val sampleRate: Int,
    val durationMillis: Long,
    val speakerName: String?,
    /** Phonemes espeak produced that the voice has no id for; reported, never guessed. */
    val unmappedPhonemes: List<String>,
    val phonemeCount: Int,
    val elapsedMillis: Long,
) {
    /** The 44-byte canonical header, so callers can slice [wav] without a magic number. */
    val pcm: ByteArray
        get() = if (wav.size > WAV_HEADER_BYTES) wav.copyOfRange(WAV_HEADER_BYTES, wav.size) else ByteArray(0)

    override fun equals(other: Any?): Boolean =
        other is SynthesisResult && wav.contentEquals(other.wav) && sampleRate == other.sampleRate

    override fun hashCode(): Int = wav.contentHashCode() * 31 + sampleRate
}

/** A loaded Piper voice: the ONNX session plus its parsed config. */
class PiperVoice internal constructor(
    val displayName: String,
    val config: PiperVoiceConfig,
    internal val ortSession: OrtSessionAlias,
    internal val modelFile: VaultModelFile,
) : Closeable {
    val languageCode: String? get() = config.languageCode

    override fun close() {
        runCatching { ortSession.close() }
        runCatching { modelFile.close() }
    }
}

/**
 * Thin alias so `:core` does not leak ONNX Runtime types into its public API while
 * still holding the real session.
 */
class OrtSessionAlias internal constructor(internal val session: ai.onnxruntime.OrtSession) : Closeable {
    val inputNames: Set<String> get() = session.inputNames
    override fun close() = session.close()
}

/**
 * Piper TTS inference on ONNX Runtime.
 *
 * Pipeline, all on device:
 *  1. espeak-ng phonemises the text in the voice's language (see [EspeakPhonemizer]).
 *  2. Phonemes are mapped to ids through the voice config's own `phoneme_id_map`,
 *     with `^`/`$` boundary markers — nothing about the vocabulary is hardcoded.
 *  3. ONNX Runtime runs the voice model (`input`, `input_lengths`, `scales`, and
 *     `sid` when the voice has more than one speaker).
 *  4. The float waveform is written out as a 16-bit PCM WAV at the voice's sample
 *     rate (22 050 Hz for the Bengali medium voice).
 */
class PiperRuntime(
    private val context: Context,
    private val phonemizer: EspeakPhonemizer,
) {

    private val environment: ai.onnxruntime.OrtEnvironment by lazy {
        ai.onnxruntime.OrtEnvironment.getEnvironment()
    }

    fun availability(): String? {
        val phonemiserStatus = phonemizer.availability()
        if (!phonemiserStatus.isReady) return phonemiserStatus.detail
        return null
    }

    fun loadVoice(
        fileSystem: VaultFileSystem,
        modelVaultPath: String,
        configVaultPath: String,
        displayName: String,
        expectedSizeBytes: Long = -1L,
    ): PiperVoice {
        phonemizer.availability().takeIf { !it.isReady }?.let {
            throw NativeRuntimeUnavailableException("Piper TTS", it.detail)
        }

        val configBytes = fileSystem.readFile(configVaultPath)
        val config = PiperVoiceConfig.parse(
            runCatching { Json.parseObject(configBytes.toString(Charsets.UTF_8)) }.getOrElse {
                throw IllegalArgumentException(
                    "The Piper voice config '$configVaultPath' is not valid JSON: ${it.message}",
                )
            },
        )

        val modelFile = VaultModelFile.open(context, fileSystem, modelVaultPath, expectedSizeBytes)
        val options = environment.createSessionOptions().apply {
            // Intra-op parallelism follows the RAM policy: a 3 GB phone should not
            // have TTS competing with the orchestrator for cores.
            setIntraOpNumThreads(minOf(2, Runtime.getRuntime().availableProcessors()))
            setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
        val session = try {
            environment.createSession(modelFile.nativePath, options)
        } catch (failure: Exception) {
            runCatching { modelFile.close() }
            throw NativeRuntimeUnavailableException(
                "Piper voice ${modelVaultPath.substringAfterLast('/')}",
                "ONNX Runtime could not open the model: ${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
        return PiperVoice(displayName, config, OrtSessionAlias(session), modelFile)
    }

    fun synthesize(
        voice: PiperVoice,
        text: String,
        speakerName: String? = null,
        lengthScaleOverride: Float? = null,
    ): SynthesisResult {
        require(text.isNotBlank()) { "Nothing to speak" }
        val config = voice.config
        val startedAt = System.currentTimeMillis()

        val phonemes = phonemizer.phonemize(text, config.espeakVoice)
        val mapped = phonemesToIds(phonemes, config)
        if (mapped.ids.size <= 2) {
            // Only BOS/EOS survived mapping: speaking this would emit silence and
            // look like a working TTS that says nothing.
            throw NativeRuntimeUnavailableException(
                "Piper phoneme mapping",
                "espeak-ng produced ${mapped.consumed} phoneme(s) for voice '${config.espeakVoice}' " +
                    "but none of them exist in this voice's phoneme_id_map " +
                    "(unmapped: ${mapped.unmapped.take(20)}). The voice and the text language do " +
                    "not match.",
            )
        }

        val speakerId = resolveSpeaker(config, speakerName)
        val scales = floatArrayOf(
            config.noiseScale,
            lengthScaleOverride ?: config.lengthScale,
            config.noiseW,
        )

        val inputs = linkedMapOf<String, ai.onnxruntime.OnnxTensor>()
        try {
            val inputName = requireInput(voice, "input", listOf("input", "input_ids", "x"))
            val lengthName = requireInput(voice, "input_lengths", listOf("input_lengths", "x_lengths", "lengths"))
            val scalesName = requireInput(voice, "scales", listOf("scales", "scale"))

            inputs[inputName] = OnnxTensors.int64(
                environment,
                LongArray(mapped.ids.size) { mapped.ids[it].toLong() },
                longArrayOf(1, mapped.ids.size.toLong()),
            )
            inputs[lengthName] = OnnxTensors.int64(environment, longArrayOf(mapped.ids.size.toLong()), longArrayOf(1))
            inputs[scalesName] = OnnxTensors.float32(environment, scales, longArrayOf(scales.size.toLong()))

            val sidName = voice.ortSession.inputNames.firstOrNull { it.equals("sid", true) || it.equals("speaker_id", true) }
            if (sidName != null) {
                inputs[sidName] = OnnxTensors.int64(environment, longArrayOf(speakerId.toLong()), longArrayOf(1))
            } else if (config.numSpeakers > 1) {
                throw NativeRuntimeUnavailableException(
                    "Piper voice ${voice.displayName}",
                    "The voice declares ${config.numSpeakers} speakers but the ONNX graph has no " +
                        "speaker input (available inputs: ${voice.ortSession.inputNames}).",
                )
            }

            voice.ortSession.session.run(inputs).use { result ->
                val outputName = voice.ortSession.session.outputNames.firstOrNull { it.equals("output", true) || it.equals("audio", true) }
                    ?: voice.ortSession.session.outputNames.first()
                val value = result[outputName].orElseThrow {
                    NativeRuntimeUnavailableException("Piper inference", "ONNX produced no '$outputName' output")
                }
                val samples = OnnxTensors.toFloatSamples(value)
                val wav = writeWav(samples, config.sampleRate)
                val durationMillis = if (config.sampleRate > 0) samples.size * 1000L / config.sampleRate else 0L
                return SynthesisResult(
                    wav = wav,
                    sampleRate = config.sampleRate,
                    durationMillis = durationMillis,
                    speakerName = config.speakerIdMap.entries.firstOrNull { it.value == speakerId }?.key,
                    unmappedPhonemes = mapped.unmapped,
                    phonemeCount = mapped.consumed,
                    elapsedMillis = System.currentTimeMillis() - startedAt,
                )
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    private fun resolveSpeaker(config: PiperVoiceConfig, requested: String?): Int {
        if (config.numSpeakers <= 1) return config.speakerIdMap.values.firstOrNull() ?: 0
        if (requested != null) {
            config.speakerIdMap[requested]?.let { return it }
            throw IllegalArgumentException(
                "Speaker '$requested' does not exist in this voice. Available: " +
                    config.speakerIdMap.keys.joinToString(),
            )
        }
        // Default to the first declared speaker rather than an arbitrary index.
        return config.speakerIdMap.values.firstOrNull() ?: 0
    }

    private fun requireInput(voice: PiperVoice, logicalName: String, candidates: List<String>): String =
        candidates.firstOrNull { voice.ortSession.inputNames.contains(it) }
            ?: throw NativeRuntimeUnavailableException(
                "Piper voice ${voice.displayName}",
                "The ONNX graph has no '$logicalName' input (available: ${voice.ortSession.inputNames}). " +
                    "This does not look like a Piper VITS voice model.",
            )

    data class MappedPhonemes(val ids: List<Int>, val unmapped: List<String>, val consumed: Int)

    /**
     * Maps an espeak IPA string to voice ids.
     *
     * The config's `phoneme_id_map` contains multi-character entries (diphthongs
     * like `aɪ`, and combining diacritics like `̃`), so matching is longest-first
     * over code points rather than one character at a time. Phonemes with no entry
     * are collected and reported — dropping them silently would mispronounce words
     * without anyone being able to tell.
     */
    fun phonemesToIds(phonemes: String, config: PiperVoiceConfig): MappedPhonemes {
        val maxLength = config.phonemeIdMap.keys.maxOfOrNull { it.length } ?: 1
        val ids = mutableListOf(config.bosId)
        val unmapped = mutableListOf<String>()
        var consumed = 0
        var index = 0
        var pendingSpace = false

        while (index < phonemes.length) {
            val codePoint = phonemes.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val slice = phonemes.substring(index, minOf(index + maxLength, phonemes.length))

            if (Character.isWhitespace(codePoint)) {
                pendingSpace = true
                index += charCount
                continue
            }

            var matched: String? = null
            var length = minOf(maxLength, slice.length)
            while (length >= 1) {
                val candidate = slice.substring(0, length)
                if (config.phonemeIdMap.containsKey(candidate)) {
                    matched = candidate
                    break
                }
                length--
            }

            if (matched != null) {
                if (pendingSpace) {
                    ids += config.spaceId
                    pendingSpace = false
                }
                ids += config.phonemeIdMap.getValue(matched)
                consumed++
                index += matched.length
            } else {
                val single = String(Character.toChars(codePoint))
                unmapped += single
                consumed++
                index += charCount
            }
        }

        ids += config.eosId
        return MappedPhonemes(ids, unmapped, consumed)
    }

    /** Writes a canonical 44-byte-header 16-bit PCM WAV. */
    private fun writeWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)

        fun writeAscii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun writeLe32(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }

        fun writeLe16(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }

        writeAscii("RIFF"); writeLe32(36 + dataSize); writeAscii("WAVE")
        writeAscii("fmt "); writeLe32(16); writeLe16(1); writeLe16(channels)
        writeLe32(sampleRate); writeLe32(byteRate); writeLe16(channels * bitsPerSample / 8)
        writeLe16(bitsPerSample)
        writeAscii("data"); writeLe32(dataSize)

        for (sample in samples) {
            val clamped = sample.coerceIn(-1f, 1f)
            writeLe16((clamped * Short.MAX_VALUE).toInt().toShort().toInt() and 0xFFFF)
        }
        return out.toByteArray()
    }

    /** ONNX tensor helpers kept in one place so tensor lifetime is obvious. */
    private object OnnxTensors {
        fun int64(env: ai.onnxruntime.OrtEnvironment, values: LongArray, shape: LongArray) =
            ai.onnxruntime.OnnxTensor.createTensor(env, LongBuffer.wrap(values), shape)

        fun float32(env: ai.onnxruntime.OrtEnvironment, values: FloatArray, shape: LongArray) =
            ai.onnxruntime.OnnxTensor.createTensor(env, FloatBuffer.wrap(values), shape)

        /** Extracts the waveform from either a [1][N] or a [1][1][N] float output. */
        fun toFloatSamples(value: ai.onnxruntime.OnnxValue): FloatArray {
            val tensor = value as? ai.onnxruntime.OnnxTensor
                ?: throw NativeRuntimeUnavailableException("Piper inference", "ONNX output is not a tensor")
            return when (val raw = tensor.value) {
                is FloatArray -> raw
                is Array<*> -> when {
                    raw.isEmpty() -> FloatArray(0)
                    raw[0] is FloatArray -> raw.flatMap { (it as FloatArray).toList() }.toFloatArray()
                    raw[0] is Array<*> -> raw.flatMap { outer ->
                        (outer as Array<*>).flatMap { (it as FloatArray).toList() }
                    }.toFloatArray()
                    else -> throw NativeRuntimeUnavailableException(
                        "Piper inference",
                        "Unexpected ONNX output element type ${raw[0]?.javaClass?.name}",
                    )
                }
                else -> throw NativeRuntimeUnavailableException(
                    "Piper inference",
                    "Unexpected ONNX output type ${raw.javaClass.name}",
                )
            }
        }
    }

    companion object {
        const val WAV_HEADER_BYTES = 44

        fun voiceConfigPathFor(modelFileName: String): String = "$modelFileName.json"
    }
}

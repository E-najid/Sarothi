// JNI bridge to espeak-ng, used purely as the phonemiser for Piper TTS.
//
// Piper's ONNX voice models consume IPA phoneme ids, not raw text. The Bengali
// voice in the default catalogue (bn_BD-google-medium) declares
// `"phoneme_type": "espeak"` and `"espeak": {"voice": "bn"}`, so the text has to
// pass through espeak-ng exactly as piper-phonemize does on desktop.
//
// Upstream espeak-ng is autotools-based, so it is cross-compiled separately by
// scripts/build_espeak_ng.sh and dropped into third_party/espeak-ng-android.
// Without it this file still compiles: every entry point reports unavailable and
// the `tts` plugin falls back to the Android system voice, telling the user why
// instead of pretending Piper ran.

#include <jni.h>

#include <mutex>
#include <string>

#include "sarothi_common.h"

#if defined(SAROTHI_HAS_ESPEAK)
#include <espeak-ng/speak_lib.h>
#endif

namespace {

constexpr jint PHON_OK = 0;
constexpr jint PHON_E_UNAVAILABLE = -1;
constexpr jint PHON_E_INIT = -2;
constexpr jint PHON_E_VOICE = -3;

#if defined(SAROTHI_HAS_ESPEAK)
// espeak-ng keeps global state and is not re-entrant; every call is serialised.
std::mutex gEspeakMutex;
bool gInitialised = false;
#endif

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativePhonemizerAvailable(JNIEnv *, jobject) {
#if defined(SAROTHI_HAS_ESPEAK)
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jint JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativePhonemizerInit(JNIEnv *env, jobject,
                                                                   jstring jDataPath) {
#if !defined(SAROTHI_HAS_ESPEAK)
    (void)env; (void)jDataPath;
    sarothi::setError(
            "espeak-ng is not compiled into this build, so Piper cannot phonemise text. "
            "Run scripts/build_espeak_ng.sh, then rebuild. The `tts` plugin will use the Android "
            "system voice meanwhile.");
    return PHON_E_UNAVAILABLE;
#else
    const std::string dataPath = sarothi::toUtf8(env, jDataPath);
    std::lock_guard<std::mutex> lock(gEspeakMutex);
    if (gInitialised) return PHON_OK;

    // AUDIO_OUTPUT_SYNCHRONOUS + buflength 0: we only want phonemes, never audio.
    const int sampleRate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0,
                                             dataPath.empty() ? nullptr : dataPath.c_str(), 0);
    if (sampleRate <= 0) {
        sarothi::setError("espeak_Initialize failed (is espeak-ng-data present under '" +
                          dataPath + "'?)");
        return PHON_E_INIT;
    }
    gInitialised = true;
    return PHON_OK;
#endif
}

JNIEXPORT void JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativePhonemizerShutdown(JNIEnv *, jobject) {
#if defined(SAROTHI_HAS_ESPEAK)
    std::lock_guard<std::mutex> lock(gEspeakMutex);
    if (gInitialised) {
        espeak_Terminate();
        gInitialised = false;
    }
#endif
}

JNIEXPORT jstring JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativePhonemize(JNIEnv *env, jobject, jstring jText,
                                                              jstring jVoice, jint espeakMode,
                                                              jint phonemeMode) {
#if !defined(SAROTHI_HAS_ESPEAK)
    (void)env; (void)jText; (void)jVoice; (void)espeakMode; (void)phonemeMode;
    sarothi::setError("espeak-ng is not compiled into this build; Piper phonemisation unavailable.");
    return nullptr;
#else
    const std::string text = sarothi::toUtf8(env, jText);
    const std::string voice = sarothi::toUtf8(env, jVoice);
    if (text.empty()) return sarothi::toJString(env, std::string());
    if (voice.empty()) {
        sarothi::setError("no espeak voice was supplied");
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(gEspeakMutex);
    if (!gInitialised) {
        sarothi::setError("espeak-ng has not been initialised; call nativePhonemizerInit first");
        return nullptr;
    }
    if (espeak_SetVoiceByName(voice.c_str()) != EE_OK) {
        sarothi::setError("espeak_SetVoiceByName failed for voice '" + voice +
                          "' (is that language installed in espeak-ng-data?)");
        return nullptr;
    }

    // espeak_TextToPhonemes consumes the input in chunks and advances the pointer
    // until it reaches NULL, returning a pointer to an internal static buffer
    // that is only valid until the next call — hence the immediate copy.
    std::string phonemes;
    const char *cursor = text.c_str();
    while (cursor != nullptr) {
        const void *inputPointer = static_cast<const void *>(cursor);
        const char *chunk = espeak_TextToPhonemes(&inputPointer, static_cast<int>(espeakMode),
                                                  static_cast<int>(phonemeMode));
        cursor = static_cast<const char *>(inputPointer);
        if (chunk == nullptr) break;
        phonemes += chunk;
    }
    return sarothi::toJString(env, phonemes);
#endif
}

}  // extern "C"

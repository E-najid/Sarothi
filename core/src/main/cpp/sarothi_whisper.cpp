// JNI bridge to whisper.cpp for Sarothi's speech-to-text plugin.
//
// Target: whisper.cpp tag b4938. The multilingual ggml models (ggml-base-q5_1.bin
// in the default catalogue) carry the Bengali decoder, which is what the `stt`
// plugin needs for বাংলা voice input.
//
// Audio arrives as 16 kHz mono float PCM, which is exactly what
// whisper_full() consumes, so no resampling happens in this layer.

#include <jni.h>

#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "sarothi_common.h"

#if defined(SAROTHI_HAS_WHISPER)
#include "whisper.h"
#endif

namespace {

// NB: whisper.h already defines WHISPER_OK, so these use a distinct prefix.
constexpr jint STT_E_UNAVAILABLE = -1;
constexpr jint STT_E_LOAD = -3;

bool invokeProgressCallback(JNIEnv *env, jobject callback, jint percent) {
    if (callback == nullptr) return true;
    static jmethodID methodId = nullptr;
    if (methodId == nullptr) {
        jclass clazz = env->GetObjectClass(callback);
        methodId = env->GetMethodID(clazz, "onProgress", "(I)V");
        env->DeleteLocalRef(clazz);
        if (methodId == nullptr) {
            env->ExceptionClear();
            return true;  // progress reporting is best-effort
        }
    }
    env->CallVoidMethod(callback, methodId, percent);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    return true;
}

#if defined(SAROTHI_HAS_WHISPER)

struct WhisperSession {
    whisper_context *ctx = nullptr;
    int nThreads = 2;
};

std::mutex gWhisperMutex;
std::map<jlong, std::unique_ptr<WhisperSession>> gWhisperSessions;
jlong gNextWhisperHandle = 1;

WhisperSession *findWhisperSession(jlong handle) {
    std::lock_guard<std::mutex> lock(gWhisperMutex);
    const auto it = gWhisperSessions.find(handle);
    return it == gWhisperSessions.end() ? nullptr : it->second.get();
}

// whisper.cpp invokes this from its worker thread; user_data carries the JNIEnv
// and the Kotlin callback so decoding progress surfaces live in the UI.
struct ProgressBridge {
    JNIEnv *env;
    jobject callback;
};

void progressTrampoline(struct whisper_context *, int progress, void *userData) {
    auto *bridge = static_cast<ProgressBridge *>(userData);
    if (bridge == nullptr || bridge->callback == nullptr) return;
    invokeProgressCallback(bridge->env, bridge->callback, static_cast<jint>(progress));
}

#endif  // SAROTHI_HAS_WHISPER

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativeWhisperRuntimeAvailable(JNIEnv *, jobject) {
#if defined(SAROTHI_HAS_WHISPER)
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jlong JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativeWhisperLoad(JNIEnv *env, jobject,
                                                                jstring jModelPath,
                                                                jint nThreads) {
#if !defined(SAROTHI_HAS_WHISPER)
    (void)env; (void)jModelPath; (void)nThreads;
    sarothi::setError(
            "whisper.cpp is not compiled into this build. Run scripts/setup_native.sh and rebuild; "
            "on-device speech-to-text is unavailable until then.");
    return STT_E_UNAVAILABLE;
#else
    const std::string modelPath = sarothi::toUtf8(env, jModelPath);
    if (modelPath.empty()) {
        sarothi::setError("whisper model path is empty");
        return STT_E_LOAD;
    }
    const whisper_context_params ctxParams = whisper_context_default_params();
    whisper_context *ctx = whisper_init_from_file_with_params(modelPath.c_str(), ctxParams);
    if (ctx == nullptr) {
        sarothi::setError("whisper_init_from_file_with_params failed for " + modelPath);
        return STT_E_LOAD;
    }
    auto session = std::make_unique<WhisperSession>();
    session->ctx = ctx;
    session->nThreads = nThreads > 0 ? static_cast<int>(nThreads) : 2;

    jlong handle = 0;
    {
        std::lock_guard<std::mutex> lock(gWhisperMutex);
        handle = gNextWhisperHandle++;
        gWhisperSessions[handle] = std::move(session);
    }
    return handle;
#endif
}

JNIEXPORT void JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativeWhisperFree(JNIEnv *, jobject, jlong handle) {
#if defined(SAROTHI_HAS_WHISPER)
    std::unique_ptr<WhisperSession> session;
    {
        std::lock_guard<std::mutex> lock(gWhisperMutex);
        const auto it = gWhisperSessions.find(handle);
        if (it == gWhisperSessions.end()) return;
        session = std::move(it->second);
        gWhisperSessions.erase(it);
    }
    if (session->ctx != nullptr) whisper_free(session->ctx);
#else
    (void)handle;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativeWhisperTranscribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray jPcm, jstring jLanguage,
        jboolean translate, jint maxTokens, jobject jProgressCallback) {
#if !defined(SAROTHI_HAS_WHISPER)
    (void)env; (void)handle; (void)jPcm; (void)jLanguage; (void)translate; (void)maxTokens;
    (void)jProgressCallback;
    sarothi::setError("whisper.cpp is not compiled into this build; no transcription is possible.");
    return nullptr;
#else
    WhisperSession *session = findWhisperSession(handle);
    if (session == nullptr || session->ctx == nullptr) {
        sarothi::setError("invalid whisper handle");
        return nullptr;
    }
    if (jPcm == nullptr) {
        sarothi::setError("audio buffer is null");
        return nullptr;
    }
    const jsize nSamples = env->GetArrayLength(jPcm);
    if (nSamples <= 0) {
        sarothi::setError("audio buffer is empty");
        return nullptr;
    }
    std::vector<float> pcm(static_cast<size_t>(nSamples));
    env->GetFloatArrayRegion(jPcm, 0, nSamples, pcm.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        sarothi::setError("failed to read the audio buffer");
        return nullptr;
    }

    const std::string language = sarothi::toUtf8(env, jLanguage);

    const whisper_full_params *defaults =
            whisper_full_default_params_by_ref(WHISPER_SAMPLING_GREEDY);
    if (defaults == nullptr) {
        sarothi::setError("whisper_full_default_params_by_ref returned null");
        return nullptr;
    }
    whisper_full_params params = *defaults;
    params.n_threads = session->nThreads;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = (translate == JNI_TRUE);
    // "auto" lets whisper run language detection; an explicit tag such as "bn"
    // pins Bengali and skips the detection step.
    params.language = language.empty() ? "auto" : language.c_str();
    params.single_segment = false;
    params.no_context = true;
    if (maxTokens > 0) params.max_tokens = static_cast<int>(maxTokens);

    ProgressBridge bridge{env, jProgressCallback};
    if (jProgressCallback != nullptr) {
        params.progress_callback = progressTrampoline;
        params.progress_callback_user_data = &bridge;
    } else {
        params.progress_callback = nullptr;
        params.progress_callback_user_data = nullptr;
    }

    const int status = whisper_full(session->ctx, params, pcm.data(), static_cast<int>(pcm.size()));
    if (status != WHISPER_OK) {
        sarothi::setError("whisper_full failed with code " + std::to_string(status));
        return nullptr;
    }

    std::string text;
    const int segments = whisper_full_n_segments(session->ctx);
    for (int i = 0; i < segments; ++i) {
        const char *segmentText = whisper_full_get_segment_text(session->ctx, i);
        if (segmentText != nullptr) text += segmentText;
    }
    // Segments arrive in decode order; whisper already spaces them, so the
    // concatenation is the transcript. Trim the trailing padding segment.
    while (!text.empty() && (text.back() == ' ' || text.back() == '\n' || text.back() == '\t')) {
        text.pop_back();
    }
    size_t start = 0;
    while (start < text.size() && (text[start] == ' ' || text[start] == '\n' || text[start] == '\t')) {
        ++start;
    }
    return sarothi::toJString(env, text.substr(start));
#endif
}

JNIEXPORT jstring JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativeWhisperDetectedLanguage(JNIEnv *env, jobject,
                                                                            jlong handle) {
#if !defined(SAROTHI_HAS_WHISPER)
    (void)env; (void)handle;
    return nullptr;
#else
    WhisperSession *session = findWhisperSession(handle);
    if (session == nullptr || session->ctx == nullptr) return nullptr;
    const int langId = whisper_full_lang_id(session->ctx);
    if (langId < 0) return nullptr;
    const char *langStr = whisper_lang_str(langId);
    return langStr == nullptr ? nullptr : sarothi::toJString(env, std::string(langStr));
#endif
}

}  // extern "C"

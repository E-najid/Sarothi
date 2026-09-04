// JNI bridge to llama.cpp for Sarothi's on-device text/orchestrator model and
// its on-demand vision (screen agent) model.
//
// Target: llama.cpp tag v0.3.0 (see scripts/setup_native.sh). When llama.cpp is
// not present in third_party/ this file still compiles and every entry point
// reports SAROTHI_E_UNAVAILABLE with a real message, so the Kotlin layer can
// tell the user the native runtime is missing instead of inventing model output.
//
// The multimodal block additionally requires llama.cpp's `mtmd` library plus its
// helper header. Both are detected at configure time (SAROTHI_HAS_MTMD /
// SAROTHI_HAS_MTMD_HELPER), so a checkout without mtmd degrades to text-only
// rather than failing to build. Screen reading through the Android accessibility
// tree never needs this path.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "sarothi_common.h"

#if defined(SAROTHI_HAS_LLAMA)
#include "llama.h"
#endif

#if defined(SAROTHI_HAS_MTMD)
#include "mtmd.h"
#if defined(SAROTHI_HAS_MTMD_HELPER) && __has_include("mtmd-helper.h")
#include "mtmd-helper.h"
#define SAROTHI_MTMD_HELPER_USABLE 1
#endif
#endif

namespace {

// Return codes shared with Kotlin (mirrored by NativeBridge).
constexpr jint SAROTHI_OK = 0;              // finished: end-of-generation or budget
constexpr jint SAROTHI_CANCELLED = 1;       // cancelled by the agent or the user
constexpr jint SAROTHI_E_UNAVAILABLE = -1;  // native runtime not compiled in
constexpr jint SAROTHI_E_BAD_HANDLE = -2;
constexpr jint SAROTHI_E_LOAD = -3;
constexpr jint SAROTHI_E_DECODE = -4;
constexpr jint SAROTHI_E_UNSUPPORTED = -5;
constexpr jint SAROTHI_E_OOM = -6;

// Compact GBNF constraining decoding to a single JSON object. Small instruct
// models drift out of the tool-call format without it, so the orchestrator
// enables this whenever it needs structured output (plans, tool calls, vision
// grounding coordinates).
const char *const kJsonObjectGrammar = R"GBNF(
root    ::= object
object  ::= "{" ws ( pair ( "," ws pair )* )? "}"
pair    ::= string ":" ws value
value   ::= object | array | string | number | boolean | null
array   ::= "[" ws ( value ( "," ws value )* )? "]"
string  ::= "\"" char* "\""
char    ::= [^"\\\x7F\x00-\x1F] | "\\" ( ["\\bfnrt/] | "u" hex hex hex hex )
hex     ::= [0-9a-fA-F]
number  ::= "-"? int ( "." [0-9]+ )? ( [eE] [-+]? [0-9]+ )?
int     ::= [0-9] | [1-9] [0-9]*
boolean ::= "true" | "false"
null    ::= "null"
ws      ::= [ \t\n]*
)GBNF";

bool invokeTokenCallback(JNIEnv *env, jobject callback, const std::string &piece) {
    if (callback == nullptr) return true;
    static jmethodID methodId = nullptr;
    if (methodId == nullptr) {
        jclass clazz = env->GetObjectClass(callback);
        methodId = env->GetMethodID(clazz, "onToken", "(Ljava/lang/String;)Z");
        env->DeleteLocalRef(clazz);
        if (methodId == nullptr) {
            env->ExceptionClear();
            sarothi::setError("token callback is missing onToken(String):Boolean");
            return false;
        }
    }
    jstring jPiece = sarothi::toJString(env, piece);
    const jboolean keepGoing = env->CallBooleanMethod(callback, methodId, jPiece);
    env->DeleteLocalRef(jPiece);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }
    return keepGoing == JNI_TRUE;
}

#if defined(SAROTHI_HAS_LLAMA)

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    int32_t nCtx = 0;
    int32_t nBatch = 512;
    std::atomic<bool> cancel{false};
    std::string mmprojPath;
#if defined(SAROTHI_HAS_MTMD)
    mtmd_context *mtmdCtx = nullptr;
#endif
};

std::mutex gMutex;
std::map<jlong, std::unique_ptr<Session>> gSessions;
jlong gNextHandle = 1;

void ensureBackend() {
    static std::once_flag once;
    std::call_once(once, [] { llama_backend_init(); });
}

Session *findSession(jlong handle) {
    std::lock_guard<std::mutex> lock(gMutex);
    const auto it = gSessions.find(handle);
    return it == gSessions.end() ? nullptr : it->second.get();
}

// Autoregressive sampling loop shared by the text-only and multimodal paths.
// `contextUsed` is the number of positions already present in the KV cache.
jint sampleLoop(JNIEnv *env, Session *session, const llama_vocab *vocab, jint maxTokens,
                jobject callback, int32_t contextUsed) {
    llama_batch batch = llama_batch_init(1, 0, 1);
    int32_t generated = 0;
    jint result = SAROTHI_OK;

    while (generated < maxTokens) {
        if (session->cancel.load()) {
            result = SAROTHI_CANCELLED;
            break;
        }
        const llama_token id = llama_sampler_sample(session->sampler, session->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;
        llama_sampler_accept(session->sampler, id);

        const std::string piece = llama_token_to_piece(vocab, id, false);
        if (!piece.empty() && !invokeTokenCallback(env, callback, piece)) {
            result = SAROTHI_CANCELLED;
            break;
        }

        batch.n_tokens = 0;
        batch.token[0] = id;
        batch.pos[0] = static_cast<llama_pos>(contextUsed++);
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        if (llama_decode(session->ctx, batch) != 0) {
            sarothi::setError("llama_decode failed while generating");
            result = SAROTHI_E_DECODE;
            break;
        }
        ++generated;
        if (contextUsed >= session->nCtx - 2) break;  // leave headroom for EOS
    }

    llama_batch_free(batch);
    return result;
}

#endif  // SAROTHI_HAS_LLAMA

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativeLlamaRuntimeAvailable(JNIEnv *, jobject) {
#if defined(SAROTHI_HAS_LLAMA)
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativeLlamaVisionAvailable(JNIEnv *, jobject) {
#if defined(SAROTHI_MTMD_HELPER_USABLE)
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativeLastError(JNIEnv *env, jobject) {
    return sarothi::toJString(env, sarothi::takeError());
}

JNIEXPORT jlong JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativeLlamaLoad(
        JNIEnv *env, jobject, jstring jModelPath, jstring jMmprojPath, jint nCtx, jint nBatch,
        jint nThreads, jint nGpuLayers, jboolean useMmap, jlong seed) {
#if !defined(SAROTHI_HAS_LLAMA)
    (void)env; (void)jModelPath; (void)jMmprojPath; (void)nCtx; (void)nBatch; (void)nThreads;
    (void)nGpuLayers; (void)useMmap; (void)seed;
    sarothi::setError(
            "llama.cpp is not compiled into this build. Run scripts/setup_native.sh and rebuild; "
            "on-device inference is unavailable until then.");
    return SAROTHI_E_UNAVAILABLE;
#else
    const std::string modelPath = sarothi::toUtf8(env, jModelPath);
    const std::string mmprojPath = sarothi::toUtf8(env, jMmprojPath);
    if (modelPath.empty()) {
        sarothi::setError("model path is empty");
        return SAROTHI_E_LOAD;
    }
    ensureBackend();

    llama_model_params modelParams = llama_model_default_params();
    // mmap is mandatory on 3 GB devices: weights stay page-cache backed instead of
    // being copied into the process heap.
    modelParams.use_mmap = (useMmap == JNI_TRUE);
    modelParams.use_mlock = JNI_FALSE;
    modelParams.n_gpu_layers = static_cast<int32_t>(nGpuLayers);

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = static_cast<int32_t>(nCtx);
    ctxParams.n_batch = static_cast<int32_t>(nBatch);
    ctxParams.n_threads = static_cast<uint32_t>(nThreads);
    ctxParams.n_threads_batch = static_cast<uint32_t>(nThreads);
    ctxParams.seed = static_cast<uint32_t>(seed);

    auto session = std::make_unique<Session>();
    session->nCtx = static_cast<int32_t>(nCtx);
    session->nBatch = std::max<jint>(1, nBatch);
    session->mmprojPath = mmprojPath;

#if defined(SAROTHI_MTMD_HELPER_USABLE)
    if (!mmprojPath.empty()) {
        const mtmd_init_params mtmdParams = mtmd_init_params_default(mmprojPath.c_str());
        session->mtmdCtx = mtmd_init_from_file(modelPath.c_str(), mmprojPath.c_str(), mtmdParams);
        if (session->mtmdCtx == nullptr) {
            sarothi::setError("mtmd_init_from_file failed for " + modelPath + " + " + mmprojPath);
            return SAROTHI_E_LOAD;
        }
        session->model = const_cast<llama_model *>(mtmd_get_model(session->mtmdCtx));
    } else {
        session->model = llama_model_load_from_file(modelPath.c_str(), modelParams);
        if (session->model == nullptr) {
            sarothi::setError("llama_model_load_from_file failed for " + modelPath);
            return SAROTHI_E_LOAD;
        }
    }
#else
    if (!mmprojPath.empty()) {
        sarothi::setError(
                "This native build has no mtmd multimodal support, so the vision model cannot be "
                "loaded. Rebuild against llama.cpp v0.3.0 with mtmd enabled. Screen reading still "
                "works through the accessibility tree.");
        return SAROTHI_E_UNSUPPORTED;
    }
    session->model = llama_model_load_from_file(modelPath.c_str(), modelParams);
    if (session->model == nullptr) {
        sarothi::setError("llama_model_load_from_file failed for " + modelPath);
        return SAROTHI_E_LOAD;
    }
#endif

    session->ctx = llama_init_from_model(session->model, ctxParams);
    if (session->ctx == nullptr) {
        sarothi::setError("llama_init_from_model failed (context allocation, n_ctx=" +
                          std::to_string(ctxParams.n_ctx) + ")");
#if defined(SAROTHI_HAS_MTMD)
        if (session->mtmdCtx != nullptr) mtmd_free(session->mtmdCtx);
        else llama_model_free(session->model);
#else
        llama_model_free(session->model);
#endif
        return SAROTHI_E_OOM;
    }

    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Deterministic by default; nativeLlamaConfigureSampling replaces this chain
    // before every generation with the orchestrator's requested settings.
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    (void)vocab;
    (void)seed;
    session->sampler = chain;

    jlong handle = 0;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        handle = gNextHandle++;
        gSessions[handle] = std::move(session);
    }
    return handle;
#endif
}

JNIEXPORT void JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativeLlamaFree(JNIEnv *, jobject, jlong handle) {
#if defined(SAROTHI_HAS_LLAMA)
    std::unique_ptr<Session> session;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        const auto it = gSessions.find(handle);
        if (it == gSessions.end()) return;
        session = std::move(it->second);
        gSessions.erase(it);
    }
    if (session->sampler != nullptr) llama_sampler_free(session->sampler);
    if (session->ctx != nullptr) llama_free(session->ctx);
#if defined(SAROTHI_HAS_MTMD)
    if (session->mtmdCtx != nullptr) {
        mtmd_free(session->mtmdCtx);  // also releases the model it loaded
    } else if (session->model != nullptr) {
        llama_model_free(session->model);
    }
#else
    if (session->model != nullptr) llama_model_free(session->model);
#endif
#else
    (void)handle;
#endif
}

JNIEXPORT void JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativeLlamaCancel(JNIEnv *, jobject, jlong handle) {
#if defined(SAROTHI_HAS_LLAMA)
    Session *session = findSession(handle);
    if (session != nullptr) session->cancel.store(true);
#else
    (void)handle;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativeLlamaInfo(JNIEnv *env, jobject, jlong handle) {
#if !defined(SAROTHI_HAS_LLAMA)
    (void)env; (void)handle;
    sarothi::setError("llama.cpp is not compiled into this build");
    return nullptr;
#else
    Session *session = findSession(handle);
    if (session == nullptr || session->model == nullptr || session->ctx == nullptr) {
        sarothi::setError("invalid model handle");
        return nullptr;
    }
    char buffer[640];
    std::snprintf(buffer, sizeof(buffer),
                  "{\"arch\":\"%s\",\"n_params\":%u,\"n_ctx\":%d,\"n_vocab\":%d,\"mmproj\":\"%s\"}",
                  llama_model_arch(session->model),
                  static_cast<unsigned>(llama_model_n_params(session->model)),
                  static_cast<int>(llama_n_ctx(session->ctx)),
                  static_cast<int>(llama_vocab_n_tokens(llama_model_get_vocab(session->model))),
                  session->mmprojPath.c_str());
    return sarothi::toJString(env, std::string(buffer));
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativeLlamaConfigureSampling(
        JNIEnv *, jobject, jlong handle, jfloat temperature, jint topK, jfloat topP,
        jboolean jsonOnly, jlong seed) {
#if !defined(SAROTHI_HAS_LLAMA)
    (void)handle; (void)temperature; (void)topK; (void)topP; (void)jsonOnly; (void)seed;
    sarothi::setError("llama.cpp is not compiled into this build");
    return JNI_FALSE;
#else
    Session *session = findSession(handle);
    if (session == nullptr || session->model == nullptr) {
        sarothi::setError("invalid model handle");
        return JNI_FALSE;
    }
    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    if (session->sampler != nullptr) llama_sampler_free(session->sampler);

    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (jsonOnly == JNI_TRUE) {
        llama_sampler_chain_add(chain, llama_sampler_init_grammar(vocab, kJsonObjectGrammar, "root"));
    }
    const float temp = static_cast<float>(temperature);
    if (temp <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temp));
        if (topK > 0) llama_sampler_chain_add(chain, llama_sampler_init_top_k(topK));
        if (topP > 0.0f && topP < 1.0f) {
            llama_sampler_chain_add(chain, llama_sampler_init_top_p(static_cast<float>(topP), 1));
        }
        llama_sampler_chain_add(chain, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }
    session->sampler = chain;
    session->cancel.store(false);
    return JNI_TRUE;
#endif
}

JNIEXPORT jint JNICALL
Java_com_ngi_sarothi_core_runtime_NativeBridge_nativeLlamaGenerate(
        JNIEnv *env, jobject, jlong handle, jstring jPrompt, jbyteArray jImage, jint maxTokens,
        jobject jCallback) {
#if !defined(SAROTHI_HAS_LLAMA)
    (void)env; (void)handle; (void)jPrompt; (void)jImage; (void)maxTokens; (void)jCallback;
    sarothi::setError(
            "llama.cpp is not compiled into this build; no model output can be produced. "
            "Run scripts/setup_native.sh and rebuild.");
    return SAROTHI_E_UNAVAILABLE;
#else
    Session *session = findSession(handle);
    if (session == nullptr || session->ctx == nullptr || session->sampler == nullptr ||
        session->model == nullptr) {
        sarothi::setError("invalid model handle");
        return SAROTHI_E_BAD_HANDLE;
    }

    const std::string prompt = sarothi::toUtf8(env, jPrompt);
    if (prompt.empty()) {
        sarothi::setError("prompt is empty");
        return SAROTHI_E_DECODE;
    }

    std::vector<uint8_t> imageBytes;
    if (jImage != nullptr) {
        const jsize length = env->GetArrayLength(jImage);
        if (length > 0) {
            imageBytes.resize(static_cast<size_t>(length));
            env->GetByteArrayRegion(jImage, 0, length, reinterpret_cast<jbyte *>(imageBytes.data()));
        }
    }

    session->cancel.store(false);
    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    llama_memory_clear(llama_get_memory(session->ctx), true);

#if defined(SAROTHI_MTMD_HELPER_USABLE)
    if (!imageBytes.empty()) {
        if (session->mtmdCtx == nullptr) {
            sarothi::setError("an image was supplied but this model was loaded without an mmproj file");
            return SAROTHI_E_UNSUPPORTED;
        }
        mtmd_bitmap *bitmap =
                mtmd_bitmap_init_from_raw_data(imageBytes.data(), imageBytes.size());
        if (bitmap == nullptr) {
            sarothi::setError("mtmd_bitmap_init_from_raw_data failed (unsupported image data?)");
            return SAROTHI_E_DECODE;
        }
        mtmd_helper_eval_input input{};
        input.prompt = prompt.c_str();
        input.images = &bitmap;
        input.n_images = 1;
        input.n_tokens = 0;  // filled in by the helper with the new KV position
        const int evalResult = mtmd_helper_eval(session->mtmdCtx, session->ctx, &input);
        mtmd_bitmap_free(bitmap);
        if (evalResult != 0) {
            sarothi::setError("mtmd_helper_eval failed with code " + std::to_string(evalResult));
            return SAROTHI_E_DECODE;
        }
        return sampleLoop(env, session, vocab, maxTokens, jCallback,
                          static_cast<int32_t>(input.n_tokens));
    }
#endif

    if (!imageBytes.empty()) {
        sarothi::setError(
                "Vision grounding is unavailable in this native build: llama.cpp was compiled "
                "without the mtmd helper. The accessibility-tree screen reader is unaffected.");
        return SAROTHI_E_UNSUPPORTED;
    }

    // ---- text-only prompt processing --------------------------------------
    const int needed = -llama_tokenize(vocab, prompt.c_str(), static_cast<int>(prompt.size()),
                                       nullptr, 0, true, true);
    if (needed <= 0) {
        sarothi::setError("llama_tokenize produced no tokens for the prompt");
        return SAROTHI_E_DECODE;
    }
    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    const int written = llama_tokenize(vocab, prompt.c_str(), static_cast<int>(prompt.size()),
                                       tokens.data(), static_cast<int>(tokens.size()), true, true);
    if (written < 0) {
        sarothi::setError("llama_tokenize failed (token buffer too small)");
        return SAROTHI_E_DECODE;
    }
    tokens.resize(static_cast<size_t>(written));

    llama_batch batch = llama_batch_init(session->nBatch, 0, 1);
    const size_t total = tokens.size();
    size_t offset = 0;
    bool decodeFailed = false;
    while (offset < total) {
        batch.n_tokens = 0;
        const size_t chunk = std::min<size_t>(static_cast<size_t>(session->nBatch), total - offset);
        for (size_t i = 0; i < chunk; ++i) {
            const int32_t index = batch.n_tokens++;
            batch.token[index] = tokens[offset + i];
            batch.pos[index] = static_cast<llama_pos>(offset + i);
            batch.n_seq_id[index] = 1;
            batch.seq_id[index][0] = 0;
            batch.logits[index] = (offset + i == total - 1) ? 1 : 0;  // only the last needs logits
        }
        if (llama_decode(session->ctx, batch) != 0) {
            sarothi::setError("llama_decode failed while processing the prompt (context too small?)");
            decodeFailed = true;
            break;
        }
        offset += chunk;
    }
    llama_batch_free(batch);
    if (decodeFailed) return SAROTHI_E_DECODE;

    return sampleLoop(env, session, vocab, maxTokens, jCallback, static_cast<int32_t>(total));
#endif
}

}  // extern "C"

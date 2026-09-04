// Shared JNI helpers for the Sarothi native runtime.
//
// Header-only on purpose: C++17 `inline` variables give a single definition of
// the thread-local error slot across all translation units without needing an
// extra .cpp in the CMake target list.
#pragma once

#include <jni.h>

#include <string>

#define SAROTHI_LOG_TAG "SarothiNative"

namespace sarothi {

// One error message per calling thread, set by the bridges whenever they return
// a failure code. Kotlin reads it through NativeBridge.nativeLastError() so a
// failure is always reported with a real reason instead of a generic -1.
inline thread_local std::string g_error;

inline void setError(const std::string &message) { g_error = message; }

inline std::string takeError() {
    std::string message = g_error.empty() ? std::string("unknown native error") : g_error;
    g_error.clear();
    return message;
}

inline std::string toUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return std::string();
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return std::string();
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

inline jstring toJString(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace sarothi

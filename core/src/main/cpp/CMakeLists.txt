cmake_minimum_required(VERSION 3.22.1)
project(sarothi_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# scripts/setup_native.sh clones the pinned dependencies here.
if(NOT DEFINED SAROTHI_THIRD_PARTY)
    set(SAROTHI_THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party")
endif()
get_filename_component(SAROTHI_THIRD_PARTY "${SAROTHI_THIRD_PARTY}" ABSOLUTE)

set(LLAMA_DIR   "${SAROTHI_THIRD_PARTY}/llama.cpp")
set(WHISPER_DIR "${SAROTHI_THIRD_PARTY}/whisper.cpp")
set(ESPEAK_DIR  "${SAROTHI_THIRD_PARTY}/espeak-ng-android")

message(STATUS "Sarothi: third_party = ${SAROTHI_THIRD_PARTY}")

# ---------------------------------------------------------------------------
# llama.cpp — text orchestrator + (via mtmd) the vision/screen agent model.
# Pinned tag: v0.3.0 (see scripts/setup_native.sh).
# ---------------------------------------------------------------------------
set(SAROTHI_HAS_LLAMA OFF)
set(SAROTHI_HAS_MTMD OFF)
if(EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_COMMON     OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS      OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL             OFF CACHE BOOL "" FORCE)
    set(LLAMA_STRICT           OFF CACHE BOOL "" FORCE)
    # The NDK toolchain does not ship libgomp; ggml's OpenMP backend is off so
    # that inference uses the plain pthreads CPU backend.
    set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
    set(GGML_BLAS              OFF CACHE BOOL "" FORCE)
    set(GGML_CUDA              OFF CACHE BOOL "" FORCE)
    set(GGML_METAL             OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN            OFF CACHE BOOL "" FORCE)
    add_subdirectory("${LLAMA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)
    set(SAROTHI_HAS_LLAMA ON)
    if(TARGET mtmd)
        set(SAROTHI_HAS_MTMD ON)
    endif()
else()
    message(WARNING "Sarothi: llama.cpp not found at ${LLAMA_DIR} — model runtime will report itself unavailable")
endif()

# ---------------------------------------------------------------------------
# whisper.cpp — speech to text. Pinned tag: b4938.
# ---------------------------------------------------------------------------
set(SAROTHI_HAS_WHISPER OFF)
if(EXISTS "${WHISPER_DIR}/CMakeLists.txt")
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
    add_subdirectory("${WHISPER_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp" EXCLUDE_FROM_ALL)
    set(SAROTHI_HAS_WHISPER ON)
else()
    message(WARNING "Sarothi: whisper.cpp not found at ${WHISPER_DIR} — STT will report itself unavailable")
endif()

# ---------------------------------------------------------------------------
# espeak-ng — phonemiser for Piper TTS. Upstream is autotools-based, so it is
# cross-compiled separately by scripts/build_espeak_ng.sh and dropped in as a
# prebuilt shared library plus headers.
# ---------------------------------------------------------------------------
set(SAROTHI_HAS_ESPEAK OFF)
if(EXISTS "${ESPEAK_DIR}/${ANDROID_ABI}/libespeak-ng.so")
    set(SAROTHI_HAS_ESPEAK ON)
else()
    message(WARNING "Sarothi: prebuilt espeak-ng not found for ${ANDROID_ABI} — Piper phonemisation unavailable, TTS falls back to the Android system voice")
endif()

# ---------------------------------------------------------------------------
add_library(sarothi_native SHARED
        sarothi_llama.cpp
        sarothi_whisper.cpp
        sarothi_phonemizer.cpp)

target_include_directories(sarothi_native PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

if(SAROTHI_HAS_LLAMA)
    target_compile_definitions(sarothi_native PRIVATE SAROTHI_HAS_LLAMA=1)
    target_include_directories(sarothi_native PRIVATE "${LLAMA_DIR}/include")
    target_link_libraries(sarothi_native PRIVATE llama)
    if(TARGET ggml)
        target_link_libraries(sarothi_native PRIVATE ggml)
    endif()
    if(SAROTHI_HAS_MTMD)
        target_compile_definitions(sarothi_native PRIVATE SAROTHI_HAS_MTMD=1)
        target_include_directories(sarothi_native PRIVATE
                "${LLAMA_DIR}/tools/mtmd")
        target_link_libraries(sarothi_native PRIVATE mtmd)
        if(EXISTS "${LLAMA_DIR}/tools/mtmd/mtmd-helper.h")
            target_compile_definitions(sarothi_native PRIVATE SAROTHI_HAS_MTMD_HELPER=1)
        endif()
    endif()
endif()

if(SAROTHI_HAS_WHISPER)
    target_compile_definitions(sarothi_native PRIVATE SAROTHI_HAS_WHISPER=1)
    target_include_directories(sarothi_native PRIVATE "${WHISPER_DIR}/include")
    target_link_libraries(sarothi_native PRIVATE whisper)
endif()

if(SAROTHI_HAS_ESPEAK)
    target_compile_definitions(sarothi_native PRIVATE SAROTHI_HAS_ESPEAK=1)
    target_include_directories(sarothi_native PRIVATE "${ESPEAK_DIR}/include")
    target_link_libraries(sarothi_native PRIVATE "${ESPEAK_DIR}/${ANDROID_ABI}/libespeak-ng.so")
endif()

target_link_libraries(sarothi_native PRIVATE log android)

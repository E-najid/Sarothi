#!/usr/bin/env bash
#
# Fetches Sarothi's native dependencies into <repo>/third_party at pinned
# revisions.
#
#   ./scripts/setup_native.sh              clone/fetch every pinned dependency
#   ./scripts/setup_native.sh --check      verify what is present, change nothing
#   ./scripts/setup_native.sh --submodules write .gitmodules entries instead
#   ./scripts/setup_native.sh --with-espeak also cross-compile espeak-ng (slow,
#                                          needs the Android NDK)
#
# third_party/ is gitignored: the sources are large and fully reproducible from
# the pins below. Nothing here downloads model weights - those come from
# ModelCatalog at runtime, verified by SHA-256.
#
# The build degrades honestly without this step: core/build.gradle.kts skips the
# CMake configuration entirely when third_party/ is absent, and LlamaRuntime /
# WhisperRuntime / EspeakPhonemizer then report themselves unavailable through
# NativeBridge instead of returning fabricated output.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
THIRD_PARTY="$REPO_ROOT/third_party"

# ---------------------------------------------------------------------------
# Pinned revisions. Bumping one of these is a deliberate act: the JNI bridge in
# core/src/main/cpp compiles against these APIs, and llama.cpp in particular
# moves fast.
# ---------------------------------------------------------------------------
LLAMA_REPO="https://github.com/ggml-org/llama.cpp.git"
LLAMA_REF="v0.3.0"

WHISPER_REPO="https://github.com/ggml-org/whisper.cpp.git"
WHISPER_REF="b4938"

ESPEAK_REPO="https://github.com/espeak-ng/espeak-ng.git"
ESPEAK_REF="1.52.0"

MODE="fetch"
WITH_ESPEAK=0

usage() {
    sed -n '3,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --check)        MODE="check"; shift ;;
        --submodules)   MODE="submodules"; shift ;;
        --with-espeak)  WITH_ESPEAK=1; shift ;;
        -h|--help)      usage 0 ;;
        *) echo "setup_native.sh: unknown option '$1'" >&2; usage 1 ;;
    esac
done

log()  { printf '  \033[1;34m==>\033[0m %s\n' "$*"; }
ok()   { printf '  \033[1;32m ok\033[0m %s\n' "$*"; }
warn() { printf '  \033[1;33m !!\033[0m %s\n' "$*"; }
die()  { printf '  \033[1;31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

command -v git >/dev/null 2>&1 || die "git is required but was not found on PATH"

# Reports the checked-out revision of a dependency, or MISSING.
describe() {
    local dir="$1"
    if [ ! -d "$dir/.git" ]; then
        echo "MISSING"
        return
    fi
    local tag sha
    tag="$(git -C "$dir" describe --tags --exact-match 2>/dev/null || true)"
    sha="$(git -C "$dir" rev-parse --short HEAD 2>/dev/null || echo '?')"
    if [ -n "$tag" ]; then echo "$tag ($sha)"; else echo "$sha"; fi
}

# ------------------------------------------------------------------ --check
if [ "$MODE" = "check" ]; then
    echo "Sarothi native dependencies in $THIRD_PARTY"
    status=0
    for entry in "llama.cpp:$LLAMA_REF" "whisper.cpp:$WHISPER_REF"; do
        name="${entry%%:*}"; want="${entry##*:}"
        got="$(describe "$THIRD_PARTY/$name")"
        if [ "$got" = "MISSING" ]; then
            warn "$name: not fetched (want $want)"
            status=1
        else
            ok "$name: $got (want $want)"
        fi
    done
    if [ -f "$THIRD_PARTY/espeak-ng-android/include/espeak-ng/speak_lib.h" ]; then
        ok "espeak-ng-android: prebuilt present"
    else
        warn "espeak-ng-android: not built (Piper TTS falls back to the system voice)"
    fi
    if [ "$status" -ne 0 ]; then
        echo
        echo "Run ./scripts/setup_native.sh to fetch the missing dependencies."
    fi
    exit "$status"
fi

# ----------------------------------------------------------- --submodules
if [ "$MODE" = "submodules" ]; then
    cat > "$REPO_ROOT/.gitmodules" <<EOF
# Optional alternative to scripts/setup_native.sh.
#
# Submodules pin the same revisions but put the dependency inside the repository's
# object graph, which costs every cloner a much larger download. The script-based
# flow is the default because third_party/ is reproducible from these pins and can
# be regenerated on a machine that already has the sources cached.
[submodule "third_party/llama.cpp"]
	path = third_party/llama.cpp
	url = $LLAMA_REPO
	shallow = true
[submodule "third_party/whisper.cpp"]
	path = third_party/whisper.cpp
	url = $WHISPER_REPO
	shallow = true
EOF
    log "wrote $REPO_ROOT/.gitmodules"
    git -C "$REPO_ROOT" submodule sync --recursive
    git -C "$REPO_ROOT" submodule update --init --recursive --depth 1 || \
        die "submodule update failed"
    git -C "$REPO_ROOT/third_party/llama.cpp" checkout --detach "$LLAMA_REF"
    git -C "$REPO_ROOT/third_party/whisper.cpp" checkout --detach "$WHISPER_REF"
    ok "submodules checked out at llama.cpp $LLAMA_REF, whisper.cpp $WHISPER_REF"
    echo
    warn "third_party/ is listed in .gitignore. If you want the submodule paths"
    warn "tracked, remove the 'third_party/' line before committing .gitmodules."
    exit 0
fi

# ---------------------------------------------------------------- fetch
mkdir -p "$THIRD_PARTY"

# fetch <dir> <url> <ref>
fetch() {
    local dir="$THIRD_PARTY/$1" url="$2" ref="$3" name="$1"

    if [ -d "$dir/.git" ]; then
        log "$name: updating existing clone to $ref"
        git -C "$dir" fetch --tags --force --prune origin "$ref" || \
            die "$name: could not fetch $ref from $url"
    else
        [ -e "$dir" ] && die "$name: $dir exists but is not a git clone; remove it first"
        log "$name: cloning $url at $ref"
        git clone --depth 1 --branch "$ref" --single-branch "$url" "$dir" || \
            die "$name: clone failed. Is '$ref' a real tag in $url?"
    fi

    git -C "$dir" checkout --detach "$ref" 2>/dev/null || \
        die "$name: checkout of $ref failed"

    # The build reads these paths directly; a tag that exists but lacks them would
    # produce a confusing CMake error later, so check now.
    [ -f "$dir/CMakeLists.txt" ] || die "$name: $ref has no CMakeLists.txt"
    ok "$name: $(describe "$dir")"
}

echo "Sarothi native setup"
echo "  target: $THIRD_PARTY"
echo

fetch llama.cpp   "$LLAMA_REPO"   "$LLAMA_REF"
fetch whisper.cpp "$WHISPER_REPO" "$WHISPER_REF"

# llama.cpp's multimodal interface is what the screen agent needs. If the pinned
# tag does not provide it, say so now rather than letting the vision describer
# report a mysterious failure at runtime.
if [ -d "$THIRD_PARTY/llama.cpp/tools/mtmd" ]; then
    ok "llama.cpp mtmd (multimodal) present - vision screen agent can be built"
else
    warn "llama.cpp $LLAMA_REF has no tools/mtmd - the vision model will be unavailable"
fi

if [ "$WITH_ESPEAK" -eq 1 ]; then
    echo
    # build_espeak_ng.sh is referenced by core/src/main/cpp/CMakeLists.txt and by
    # EspeakPhonemizer, but it is not written yet. Failing here with a clear
    # message beats a cryptic "command not found", and it is why --help lists the
    # flag as needing the NDK rather than promising it works.
    if [ ! -x "$REPO_ROOT/scripts/build_espeak_ng.sh" ]; then
        die "scripts/build_espeak_ng.sh does not exist yet.

  espeak-ng is only needed for Piper TTS (the Bengali voice in ModelCatalog).
  Without it EspeakPhonemizer.availability() returns NO_NATIVE_LIBRARY and
  AndroidVoiceController falls back to the Android system voice -- which is a
  real, working path, not a stub.

  llama.cpp and whisper.cpp were fetched successfully; the text orchestrator,
  the vision screen agent and speech-to-text do not depend on espeak-ng."
    fi
    log "cross-compiling espeak-ng $ESPEAK_REF"
    ESPEAK_REPO="$ESPEAK_REPO" ESPEAK_REF="$ESPEAK_REF" \
        "$REPO_ROOT/scripts/build_espeak_ng.sh"
fi

echo
ok "native dependencies ready"
cat <<EOF

Next:
  ./gradlew :core:externalNativeBuildDebug    compile the JNI bridge
  ./gradlew :app:assembleDebug                 build the APK

Without third_party/ the Kotlin build still succeeds and every model runtime
reports itself unavailable through NativeBridge - it never fabricates output.
EOF

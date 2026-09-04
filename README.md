# সারথি · Sarothi

An on-device AI agent for Android. Sarothi reads your screen, plans a task as
structured JSON, and carries it out through real Android APIs — sending the
message, setting the alarm, opening the app — with every sensitive step gated
behind a confirmation you control.

**No cloud inference. No telemetry. Your password is the only secret.**

```
You:  "বিকাল ৫টায় আম্মুকে ফোন করার কথা মনে করিয়ে দাও"
        ↓
Sarothi plans → [read clock] [create schedule 17:00 daily] [confirm with user]
        ↓
Each step runs through a permission + safety gate, and is written to task_history
```

---

## Current status

This repository contains the **complete `:core` capability layer and the complete
`:plugins` catalogue** — 27,500 lines of Kotlin across 113 files, plus the JNI
bridge to llama.cpp / whisper.cpp / espeak-ng.

| Module | State |
|---|---|
| `:core` — crypto, vault, agent, plugins, screen, voice, models, safety, scheduling | ✅ Implemented |
| `:plugins` — 78 plugins across 9 categories | ✅ Implemented |
| `core/src/main/cpp` — JNI bridge (llama.cpp, whisper.cpp, espeak-ng) | ✅ Implemented |
| `:app` — Compose UI, DI graph, manifest | ❌ **Not yet written** |
| Gradle wrapper (`gradlew`, `gradle-wrapper.jar`) | ❌ Not committed (binary) |
| `docs/`, `scripts/setup_native.sh` | ❌ Not yet written |

**The project does not build yet.** `settings.gradle.kts` declares `:app`, but
that module has only its launcher icons — no `build.gradle.kts`, no manifest, no
Kotlin. See [What is missing](#what-is-missing) below. Nothing in this repo
pretends otherwise: unimplemented capabilities throw or report themselves
unavailable at runtime rather than returning fake results.

### What is verified

`scripts/verify_argon2_rfc9106.py` runs the Argon2id implementation in
`core/…/crypto/Argon2.kt` against the official RFC 9106 test vectors — **14/14
pass**, including the final tag
`0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659`. This is the
key-derivation function the vault's encryption depends on, so it is the one piece
of cryptography that can be proven correct without an Android device.

`scripts/check_kotlin_braces.py` reports 0 unbalanced delimiter files across all
117 Kotlin sources. This is a structural check, **not** a compile — no JDK or
Android SDK was available in the environment where this code was written, so
`:core` and `:plugins` have never been through `kotlinc`. Expect to fix
signature-level errors on first build.

---

## Architecture

```
:app        Compose UI, DI graph, MainActivity           (not yet written)
  │
  ├─ :plugins   78 plugins. Depend on :core's contract only —
  │             capabilities arrive via PluginContext, never by
  │             reaching into :core internals.
  │
  └─ :core      crypto · storage · agent · plugin engine · screen ·
                voice · model runtime · safety · scheduling · net
                  │
                  └─ JNI → llama.cpp v0.3.0 · whisper.cpp b4938 · espeak-ng
```

### The agent loop

`SarothiAgent` asks the resident 350M orchestrator for a plan as constrained
JSON (GBNF grammar — only a valid object can be emitted), then executes it one
step at a time. A failing step triggers a replan against what actually happened,
bounded by `AgentLimits.forTier(ramPolicy.tier)`. State is published as a
`StateFlow<TaskState>` so the UI checklist is a live view of real progress, not a
post-hoc summary.

When the model needs something only you know — a phone number, a bill amount —
it emits `ask_user` and the task **parks**. Sarothi never invents personal data.
Secret answers are masked in the log and kept out of the model's context.

### The vault

Your data lives in a folder *you* choose via SAF (`ACTION_OPEN_DOCUMENT_TREE`),
with a persistable URI grant — so it can sit on an SD card, survive an uninstall,
and be restored on a new phone.

```
/manifest.json          plaintext: schema version, salts, model metadata
/memories/*.json        AES-256-GCM sealed
/plugins_config/        AES-256-GCM sealed
/logs/                  AES-256-GCM sealed, one file per day
/task_history/          AES-256-GCM sealed, one file per task
/models/*.gguf          NOT encrypted — public weights, and decrypting 200 MB
                        on a 3 GB phone would buy nothing
```

Key derivation is **Argon2id** (m=12288 KiB, t=3, p=1) in pure Kotlin — no
BouncyCastle, because Android's bootclasspath shadows BC classes. Two separate
salts: one derives the AES key, one derives a verification hash, so a correct
password can be confirmed without exposing the key. Every sealed file binds its
own vault path as AES-GCM AAD, which makes a file copied between vaults fail to
decrypt rather than silently open.

Lockout is 3 free attempts then exponential backoff. Biometric unlock is
**convenience only**: it unwraps a key the passphrase could have produced anyway,
gated by an Android Keystore AES key. It never authorises anything the password
could not.

There are no server-side secrets and no hardcoded keys. Security comes entirely
from the user's password.

### Models

Two GGUF models, both pinned by SHA-256 in `ModelCatalog`:

| Role | Model | Size |
|---|---|---|
| Text orchestrator (resident) | LiquidAI LFM2.5-350M Q4_0 | 209 MB |
| Screen agent (on-demand, mmap) | LiquidAI LFM2.5-VL-450M Q4_0 + mmproj | 209 MB + 98 MB |
| Speech-to-text | whisper.cpp ggml-base q5_1 | 57 MB |
| TTS (Bengali) | Piper bn_BD-google-medium | 73 MB |

Downloads are resumable over HTTP `Range`, tried against ordered sources, run in
a foreground service, Wi-Fi-only by default, and checksum-verified before use.
When every source fails the UI shows the manual instructions from the catalogue
rather than a spinner that never resolves.

The vision model is loaded on demand and released according to the RAM tier —
`RamPolicy` reads `ActivityManager.getMemoryInfo()` and picks context sizes,
thread counts and residency rules for 3 GB devices.

### Screen perception

Primary path is the **AccessibilityService** window tree: real node text, real
bounds, real `performAction`. The fallback is MediaProjection → screenshot →
VLM, used when the tree is empty or uninformative. Row padding from
`ImageReader.rowStride` is cropped so screenshots do not shear.

Actions go through `performAction` and `dispatchGesture`. Because those are
`final` on `AccessibilityService`, the host interface exposes them as
`runGlobalAction` / `runGesture` / `allWindows`.

### Safety

`InteractiveSafetyGate` is the single choke point. Payments, deletions, outbound
messages, destructive system actions, irreversible installs, credential use and
bulk operations all require an explicit decision, with a preview of what will
actually happen. Unattended tasks (schedules, notification rules) are denied
rather than auto-approved.

Every action is logged to `task_history` and to the daily audit log with a
redacted summary and a SHA-256 digest of its canonical parameters — correlatable
without disclosing the values.

Undo is real: a plugin that can reverse itself returns an `UndoToken` carrying
its own restore data, and `UndoRegistry` replays it through the same permission
and confirmation pipeline.

### Plugins

78 plugins, one contract:

```kotlin
interface Plugin {
    val name: String
    val description: String
    val category: PluginCategory
    val sensitivity: Sensitivity
    val parameters: JsonSchema
    suspend fun availability(context: PluginContext): PluginAvailability
    suspend fun execute(params: JsonObject, context: PluginContext): PluginResult
}
```

`PluginManager` runs a 9-stage pipeline per call — depth limit, enablement,
availability probe, permission verdict, schema validation, confirmation gate,
execution, undo registration, audit — with `MAX_CALL_DEPTH = 3` so a plugin
cannot recurse forever.

`permission_guard` is registered first and is bilingual (বাংলা + English), so a
denied action explains itself in the user's language.

| Category | Examples |
|---|---|
| communication | SMS, call, email, WhatsApp/Telegram deep links |
| productivity | notes, todos, calendar, clock, calculator, memory |
| information | web search, page fetch, weather, translate, news, Wikipedia |
| shopping | product search, price compare, UPI deep-link payment |
| system | app launch, settings, brightness, battery, notifications |
| smart_home | Home Assistant, geofence reminders |
| voice | speak, listen, available voices |
| connector | GitHub, Telegram bot, webhooks |
| meta | persona, permission guard, undo, task history |

Credentials live in `SecretStore` (Keystore-backed `EncryptedSharedPreferences`)
— never on the SD card, never in plaintext.

### Honest unavailability

Where a capability cannot really be delivered, it says so instead of faking it:

- **Bengali OCR** — `com.google.mlkit:text-recognition-bengali` does not exist.
  ML Kit is wired for Latin only, and the screen controller reports
  `LATIN_ONLY_NOTE` rather than returning plausible-looking garbage.
- **Image generation** — no on-device model; reports unavailable.
- **Order tracking** — no carrier API access; reports unavailable.
- **Payments** — real UPI deep links only. Sarothi cannot and does not move money
  itself.

---

## What is missing

To make this build and run, the following still has to be written:

1. **`:app` module** — `build.gradle.kts`, `AndroidManifest.xml`, resources
   (strings/themes), `SarothiApplication`, the DI graph wiring every `:core`
   constructor, `MainActivity`, and the Compose screens: vault setup, unlock,
   chat/task with live checklist, models, plugins, permissions, safety, history,
   schedules, persona. Plus the `Notifier` and `TextModelClient` implementations
   that `:core` declares as interfaces for `:app` to provide.
2. **`scripts/setup_native.sh`** — clones llama.cpp `v0.3.0` and whisper.cpp
   `b4938` into `third_party/`. `core/build.gradle.kts` already skips CMake when
   `third_party/` is absent, so Kotlin-only builds work without it.
3. **`scripts/build_espeak_ng.sh`** — cross-compiles espeak-ng and installs
   `espeak-ng-data` into `core/src/main/assets/`.
4. **Gradle wrapper** — `gradle wrapper --gradle-version 8.11.1` (the jar is
   binary and was not committed).
5. **`docs/`** — build instructions, vault/SD-card portability guide, threat
   model.

The DI graph is the substantial piece: every `:core` class is constructor-injected
with no DI framework, so `:app` has to build the object graph by hand and publish
the pieces Android creates on its own (`AccessibilityService`, foreground
services, `BroadcastReceiver`s) through the registries in `:core` —
`AccessibilityHostRegistry`, `ScreenshotSourceRegistry`, `AgentRunnerRegistry`,
`ScheduleRegistry`, `GeofenceRegistry`, `ModelDownloadRegistry`.

---

## Layout

```
core/src/main/java/com/ngi/sarothi/core/
  agent/       SarothiAgent, Plan, PlanParser, AgentPrompt, TaskState, limits
  capability/  Notifier, TextModelClient (implemented by :app)
  crypto/      Argon2, Blake2b, AesGcm, MasterKeyManager, SecretStore,
               BiometricKeyVault, LockoutTracker, PasswordBytes, envelope format
  data/        Stores + vault-backed implementations, Models
  error/       SarothiException hierarchy
  model/       ModelCatalog (pinned checksums), ModelDownloader, download service
  net/         HttpClient (Range-resumable), NetworkPolicy
  persona/     Persona, language, formality, verbosity
  plugin/      Plugin contract, JsonSchema, PluginManager, PluginContext,
               enablement, config store
  runtime/     LlamaRuntime, WhisperRuntime, PiperRuntime, EspeakPhonemizer,
               ModelSessionManager, RamPolicy, NativeBridge, VisionDescriber
  safety/      InteractiveSafetyGate, PermissionGuard, UndoRegistry, AuditLogger
  schedule/    TaskScheduler, NotificationRuleEngine, receivers, services
  screen/      AccessibilityHost + service, ScreenController, capture service,
               OCR, NotificationFeed, ScreenshotSource
  smart/       GeofenceStore, GeofenceWatcherService, registry
  storage/     VaultManager, VaultManifest, VaultPaths, SafVaultFileSystem
  util/        Json, Hex, Hashing, Ids
  voice/       VoiceController, AndroidVoiceController

plugins/src/main/java/com/ngi/sarothi/plugins/
  BuiltinPlugins.kt    the registry: all 78, permission_guard first
  common/              PluginSupport, Expression, UndoToken
  <category>/          one file per category
```

## Licence

Open source. See `LICENSE` (to be added).

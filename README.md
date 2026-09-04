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
| `.github/workflows/build.yml` — CI | ✅ Implemented |
| `:app` — Compose UI, DI graph, manifest | ❌ **Not yet written** |
| Gradle wrapper (`gradlew`, `gradle-wrapper.jar`) | ❌ Not committed (binary) |
| `scripts/build_espeak_ng.sh`, `docs/` | ❌ Not yet written |

**The project does not build an APK yet.** `settings.gradle.kts` declares `:app`, but
that module has only its launcher icons — no `build.gradle.kts`, no manifest, no
Kotlin. The two library modules do compile on their own, and CI builds them; see
[Continuous integration](#continuous-integration). Nothing in this repo pretends
otherwise: unimplemented capabilities throw or report themselves unavailable at
runtime rather than returning fake results.

## Continuous integration

`.github/workflows/build.yml` runs four jobs:

| Job | What it does | Blocking |
|---|---|---|
| `verify` | Argon2id vs RFC 9106, delimiter balance, build audit, model-catalogue self-consistency. No JDK or Android SDK. | ✅ |
| `build` | `:core:assembleDebug :plugins:assembleDebug` on Gradle 8.11.1 / JDK 17. **Automatically adds `:app:assembleDebug` the moment `app/build.gradle.kts` exists** — no workflow edit needed. | ✅ |
| `lint` | Android lint on the library modules. | ⚠️ non-blocking, but a *separate visible check*, not a swallowed `continue-on-error` step |
| `gradle-wrapper` | Regenerates the uncommitted wrapper and publishes it as an artifact. | — |

Two things shape the workflow and are worth knowing before you run it:

- **There is no `./gradlew`.** The wrapper jar is a binary blob and was never
  committed, so `gradle/actions/setup-gradle` supplies Gradle directly, pinned to
  the same 8.11.1 as `gradle-wrapper.properties`. Dispatch the `gradle-wrapper`
  job once, commit the four artifact files, and CI can switch to `./gradlew`.
- **No APK until `:app` lands.** Gradle tolerates an included project with no build
  script, so `:core` and `:plugins` assemble fine and the APK step reports itself
  skipped in the job summary rather than silently passing.

The native runtime is opt-in: dispatch with `native=true` (or push a tag) to clone
the pinned llama.cpp/whisper.cpp and compile the JNI bridge for both ABIs. The
default path is Kotlin-only, in which `NativeBridge` reports the model runtimes
unavailable — a real code path, not a stub.

A weekly `schedule` trigger re-checks the model catalogue against the Hugging Face
API (metadata only, nothing downloaded), because the pinned files could be replaced
or removed upstream without a single commit here.

### What is verified

Four scripts run in CI on every push. All of them exit non-zero on failure — a
check that cannot fail is not a check.

| Script | What it proves | Result |
|---|---|---|
| `verify_argon2_rfc9106.py` | Argon2id in `core/…/crypto/Argon2.kt` against the official RFC 9106 vectors | **14/14 pass**, final tag `0d640df5…e659` |
| `audit_build.py` | Every intra-project import resolves; every `R.*` reference has a resource in *its own* module; every manifest `android:name` is a real class; every `libs.*` alias exists in the version catalog; every referenced script exists | **0 errors**, 3 known-gap warnings |
| `verify_model_catalog.py --offline` | All 9 catalogue pins are well-formed, uniquely keyed, and locatable upstream | **OK** |
| `check_kotlin_braces.py` | Delimiter balance across all 117 Kotlin sources | **0 unbalanced** |

Argon2id is the one piece of cryptography provable without a device, which matters
because the whole vault rests on it: two independent salts derive the AES key and
the verifier hash, so a password can be confirmed without exposing the key.

`audit_build.py` exists because `android.nonTransitiveRClass=true` makes resource
references a per-module contract that is easy to break silently, and because it
found four real defects in this codebase (see below).

**Not verified: compilation.** No JDK or Android SDK was available where this code
was written and there was no network egress for Gradle, so `:core` and `:plugins`
have never been through `kotlinc`. The checks above are structural and semantic at
the reference level — they are not a type checker. The first real build may still
surface signature-level errors.

### Defects found and fixed by the audit

These were all live in the tree and would have failed a build:

1. **`ConfirmationPreview` imported from the wrong package in 9 plugin files.**
   It is declared in `core.plugin` (Plugin.kt) but `:plugins` imported it from
   `core.safety`. Unresolved reference × 9.
2. **`R.drawable.ic_sarothi_status` referenced by `:core` with no drawable in
   `:core`.** Used by `ModelDownloadService` and `GeofenceWatcherService` for
   their foreground notifications. The icon existed only in `:app`, which
   `nonTransitiveRClass=true` makes invisible to `:core` — and `:core` cannot
   depend on `:app` anyway. Added `core/src/main/res/drawable/ic_sarothi_status.xml`.
3. **`ndkVersion` pinned unconditionally in `core/build.gradle.kts`.** AGP resolves
   it eagerly, so a Kotlin-only build would fail on any machine without that exact
   NDK installed — including CI runners — even with no native code to compile. Now
   set only when `third_party/` is present.
4. **`settings.gradle.kts` includes `:app`, which has no build script.** Not a
   configuration failure (Gradle tolerates it), but it means no APK. Left as-is and
   reported as a warning, since fixing it means writing the module.

Also missing and now written: `scripts/setup_native.sh` (referenced by 9 committed
files) and `scripts/verify_model_catalog.py` (referenced by `ModelCatalog.kt`).

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

To make this build an APK and run, the following still has to be written:

1. **`:app` module** — `build.gradle.kts`, `AndroidManifest.xml`, resources
   (strings/themes), `SarothiApplication`, the DI graph wiring every `:core`
   constructor, `MainActivity`, and the Compose screens: vault setup, unlock,
   chat/task with live checklist, models, plugins, permissions, safety, history,
   schedules, persona. Plus the `Notifier` and `TextModelClient` implementations
   that `:core` declares as interfaces for `:app` to provide.
2. **`scripts/build_espeak_ng.sh`** — cross-compiles espeak-ng `1.52.0` and
   installs `espeak-ng-data` into `core/src/main/assets/`. Only needed for Piper
   TTS; without it `EspeakPhonemizer.availability()` returns `NO_NATIVE_LIBRARY`
   and `AndroidVoiceController` falls back to the Android system voice, which is a
   real working path. `setup_native.sh --with-espeak` fails with an explicit
   message until this exists rather than pretending to work.
3. **Gradle wrapper** — dispatch the `gradle-wrapper` CI job and commit the four
   artifact files, or run `gradle wrapper --gradle-version 8.11.1` locally.
4. **`docs/`** — build instructions, vault/SD-card portability guide, threat model.
5. **`LICENSE`** — the project is intended to be open source but no licence file
   has been chosen or added yet.

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

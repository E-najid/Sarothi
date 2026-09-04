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

The whole app is written and building: **31,200 lines of Kotlin across 132 source
files** in three modules, plus the JNI bridge to llama.cpp / whisper.cpp / espeak-ng,
plus **176 unit tests** in 10 more files. CI assembles two APKs on every push.

| Module | State |
|---|---|
| `:core` — crypto, vault, agent, plugins, screen, voice, models, safety, scheduling (94 files, 17,700 lines) | ✅ Implemented |
| `:plugins` — 78 plugins across 9 categories (20 files, 10,300 lines) | ✅ Implemented |
| `:app` — Compose UI (10 screens), DI graph, manifest (18 files, 3,200 lines) | ✅ Implemented |
| `core/src/main/cpp` — JNI bridge (llama.cpp, whisper.cpp, espeak-ng) | ✅ Implemented |
| `.github/workflows/build.yml` — CI | ✅ Implemented |
| Unit tests — `:core` and `:plugins`, JVM-only | ✅ 176 passing |
| Gradle wrapper (`gradlew`, `gradle-wrapper.jar`) | ❌ Not committed (binary) |
| `scripts/build_espeak_ng.sh`, `docs/`, `LICENSE` | ❌ Not yet written |

CI produces `app-arm64-v8a-debug.apk` (about 65 MB) and `app-armeabi-v7a-debug.apk`
(about 51 MB); see [Continuous integration](#continuous-integration). Nothing in this
repo pretends to more than it does: unimplemented capabilities throw or report
themselves unavailable at runtime rather than returning fake results, and
[What is missing](#what-is-missing) lists what is still outstanding.

## Continuous integration

`.github/workflows/build.yml` runs four jobs:

| Job | What it does | Blocking |
|---|---|---|
| `verify` | Argon2id vs RFC 9106, delimiter balance, build audit, model-catalogue self-consistency. No JDK or Android SDK. | ✅ |
| `build` | `:core:assembleDebug :plugins:assembleDebug :app:assembleDebug` on Gradle 8.11.1 / JDK 17, then `:core:testDebugUnitTest :plugins:testDebugUnitTest`, then the two per-ABI APKs. The test step **counts the tests that ran and fails on zero** — see [Unit tests](#unit-tests). | ✅ |
| `lint` | Android lint on the library modules. | ⚠️ non-blocking, but a *separate visible check*, not a swallowed `continue-on-error` step |
| `gradle-wrapper` | Regenerates the uncommitted wrapper and publishes it as an artifact. | — |

Two things shape the workflow and are worth knowing before you run it:

- **There is no `./gradlew`.** The wrapper jar is a binary blob and was never
  committed, so `gradle/actions/setup-gradle` supplies Gradle directly, pinned to
  the same 8.11.1 as `gradle-wrapper.properties`. Dispatch the `gradle-wrapper`
  job once, commit the four artifact files, and CI can switch to `./gradlew`.
- **A failure is reported on the pull request, not only in the log.** Job logs and
  artifacts live on blob storage that tooling frequently cannot reach, so
  `scripts/report_build_failure.py` mines `build.log` for compiler errors, failing
  tests and Gradle's own explanation, replaces the single marker comment on the PR,
  and emits check-run annotations — the one diagnostic channel served by the ordinary
  REST API. When a build goes green the same comment is replaced with a passing note
  carrying the test counts, so a PR never shows a failure report for an older commit
  as though it described the current one.

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
| `audit_build.py` | Every intra-project import resolves; every `R.*` reference has a resource in *its own* module; every manifest `android:name` is a real class; every `libs.*` alias exists in the version catalog; every referenced script exists | **0 errors**, 1 known-gap warning |
| `verify_model_catalog.py --offline` | All 9 catalogue pins are well-formed, uniquely keyed, and locatable upstream | **OK** |
| `check_kotlin_braces.py` | Delimiter balance across all 147 Kotlin sources (142 `.kt`, 5 `.kts`) | **0 unbalanced** |

Argon2id is the one piece of cryptography provable without a device, which matters
because the whole vault rests on it: two independent salts derive the AES key and
the verifier hash, so a password can be confirmed without exposing the key.

`audit_build.py` exists because `android.nonTransitiveRClass=true` makes resource
references a per-module contract that is easy to break silently, and because it
found four real defects in this codebase (see below).

The scripts above run without a JDK, so they also run for a reviewer who has only
cloned the repository. Compilation, unit tests and lint need a runner, and CI has
one; see the next section for what the tests do and do not cover.

**Not verified: anything that needs a device.** There are no instrumentation tests and
no emulator in CI, so the accessibility service, MediaProjection capture, the native
model runtimes, model downloads and every Compose screen are compiled and reasoned
about but never executed by a machine. What CI proves about them is that they type-check,
that lint accepts them, and that the code paths which cannot work on a given device
report themselves unavailable instead of returning a fake result.

### Unit tests

176 tests in 10 files, all JVM-only: no device, no emulator, no Robolectric, no network.
They cover the logic whose wrong answer is invisible at the moment it happens — a
schedule firing at the wrong time, a vault key that is not the one the RFC specifies, a
plan silently losing a step.

| Suite | Tests | What it pins down |
|---|---|---|
| `crypto/CryptoTest` | 15 | Argon2id against the RFC 9106 §5.3 vector, so key derivation is checked against a published answer rather than against itself; AES-256-GCM round-trip, nonce freshness, wrong key, tampered ciphertext, AAD binding; the sealed-file format's header and path binding, truncation, unknown version, and that no plaintext survives into the bytes |
| `runtime/RamPolicyTest` | 24 | What a device with a given amount of RAM may ask for: the tier boundaries, so a 3 GB phone lands where the documentation says whether it reports 3072 MiB or the ~2800 MiB a real one reports after kernel reservations; the per-tier step and model-call budgets and the invariants they have to satisfy (a plan must fit its task, a generation must fit the task, no ceiling may be zero); context and batch sizes; the vision model never staying resident on a constrained device; the load refusal that stands between a new model and a system-wide low-memory kill; and the persisted tier ordinals, which a reordered enum would silently change the meaning of |
| `crypto/LockoutTrackerTest` | 12 | The brute-force backoff: three free attempts, then a wait that doubles to a 24-hour cap, a shift that cannot overflow into a negative wait, an elapsed window that keeps the count so the next failure escalates instead of restarting, and state that survives a new tracker over the same store -- which is what a force-stop must not be able to reset |
| `plugin/JsonSchemaTest` | 18 | The gate between what a 350 M model emitted and what a plugin is handed. Both halves of its rule: lossless repair is allowed *and reported*, invention is refused. A missing required parameter stays an error, so the agent asks rather than guesses |
| `schedule/ScheduleLogicTest` | 19 | When a task next fires, for every recurrence, including day 31 in a month that has 30 days; and which notifications trip a rule — `ALL` against `ANY`, package scoping, case sensitivity, cooldown, a rule disabled, a notification with no title or body |
| `schedule/SchedulePersistenceTest` | 19 | Tasks and rules written out and read back, as they are after a reboot or a restore onto a new phone. A field written but never read is invisible in the editor and wrong at run time; damaged input has to degrade to something safe rather than to nothing |
| `safety/UndoRegistryTest` | 10 | What Sarothi claims it can take back, and whether it can. The Undo button is offered only for what this registry holds, so a mistake here is a lie in one direction or the other |
| `agent/PlanParserTest` | 29 | Orchestrator output becoming steps: kinds and their synonyms, asking, refusing, fenced and prose-wrapped JSON, trailing commas, every spelling of tool name / arguments / intent / failure policy — and the line it must not cross, which is turning a bare-string step into a tool nobody chose |
| `util/JsonReplyTest` | 17 | Finding the JSON inside a reply that also contains prose, for both models |
| `plugins/BuiltinPluginsTest` | 13 | The contract over all 78 plugins at once: unique snake_case names, no undocumented parameter, no permission string that is not a permission, every category populated, schemas that survive a JSON round trip |

The build fails if that count reaches zero. This is not a hypothetical safeguard: before
these tests existed, `:core:testDebugUnitTest` was already wired into CI and passed,
because a Gradle test task with no matching sources succeeds — and the pull-request
comment reported that the commit "passes its unit tests". `scripts/count_unit_tests.py`
mines the JUnit XML so the claim can only be made when something actually ran.

**What writing them found.** Five real defects, none of them visible by reading:

1. `ScheduledTask.computeNextRun()` treated `HOURLY` as "matches unconditionally" on the
   first pass through a loop whose candidate had already been seeded from `timeOfDay`, so
   an hourly task fired once a day at 09:00 and the `plusHours(1)` step below it was
   unreachable dead code — while the schedule screen told the user "runs at the top of
   every hour".
2. `JsonSchema.fromJson()` dropped constraints that `toJson()` wrote: a text property's
   `maxLength` and a list property's default. A schema that travelled through JSON stopped
   enforcing a limit it still advertised.
3. Both hand-rolled JSON extractors stopped scanning at a brace that never closed — prose
   like "I will use the {tool you named" — and reported no JSON at all, discarding the
   usable plan in the same reply. There is now one implementation, `core/util/JsonReply.kt`,
   shared by the plan parser and the vision describer, whose private copy was weaker still:
   it tried only the first `{`, with no fence stripping and no trailing-comma retry.
4. A reply that was a bare array of step objects lost the whole task. The scanner returned
   the array's first element, which has no kind and no steps, so it parsed as an `ANSWER`
   carrying no text, and every later step was dropped.
5. `extractObject` stopped at the first parseable span whatever it was, so a reply opening
   with a citation — "Sources: [1] and [2]." — reported no object at all.

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
4. **`settings.gradle.kts` included `:app`, which had no build script.** Not a
   configuration failure (Gradle tolerates it), but it meant no APK. The module has
   since been written and the APKs build.

Also missing and now written: `scripts/setup_native.sh` (referenced by 9 committed
files) and `scripts/verify_model_catalog.py` (referenced by `ModelCatalog.kt`).

---

## Architecture

```
:app        Compose UI (10 screens), DI graph, MainActivity
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

The `:app` module, the CI pipeline and the unit-test suite are written and green. What
is still outstanding:

1. ~~**`:app` module**~~ — **written and building.** `build.gradle.kts` (application
   plugin, Compose, `applicationId com.ngi.sarothi`, `en`/`bn` resource configs),
   `AndroidManifest.xml` (Application + single launcher Activity; every permission and
   service stays in `:core`'s manifest and merges in), `strings.xml` / `themes.xml`
   (light + night), `SarothiApplication`, `di/AppGraph.kt` wiring every `:core`
   constructor and publishing all four registry seams, `notify/AndroidNotifier.kt`,
   `di/LlamaTextModelClient.kt` (the one `TextModelClient` implementation),
   `di/PersonaRepository.kt`, and ten Compose screens: task with the live checklist,
   question and confirmation dialogs; vault pick / create / restore / lock / detach;
   models with integrity state and resumable downloads; persona editor; task history;
   audit log; access (special-access settings screens, per-plugin verdicts, and the
   accessibility-service prompt that decides whether Sarothi can see the screen);
   schedules and notification rules, editable by hand as well as created by the agent;
   and settings for mobile-data downloads and biometric unlock; plus connectors, which is
   where the webhook addresses and secrets, the Home Assistant URL and the news plugin's
   fallback RSS feeds are typed by hand. The `webhook` plugin's own refusal message has
   always pointed at "Settings → Connectors → Webhooks"; that destination now exists.


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
6. **Instrumentation tests.** The 176 unit tests are JVM-only, so nothing that needs a
   device is executed by a machine: the accessibility service, MediaProjection capture,
   the native model runtimes, resumable downloads and all ten Compose screens compile and
   pass lint but are untested at runtime. Testing them needs an emulator in CI.

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

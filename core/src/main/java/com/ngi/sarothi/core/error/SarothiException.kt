package com.ngi.sarothi.core.error

/**
 * Root of every error Sarothi raises on purpose.
 *
 * The project has a hard "no fake code" rule: nothing may look like it works
 * when it does not. These types exist so that an unimplemented, unavailable,
 * unconfigured or refused capability always surfaces as a *specific, readable*
 * failure that the UI can show verbatim — never as a plausible-looking empty
 * result or hardcoded sample data.
 */
sealed class SarothiException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Thrown by any code path that has deliberately not been written yet.
 *
 * Kept separate from [PluginUnavailableException] (which means "implemented, but
 * its prerequisites are missing on this device right now"). Every instance is
 * cross-referenced from docs/UNIMPLEMENTED.md so a reader of the source can see
 * the gap without running the app.
 */
class FeatureNotImplementedException(
    val feature: String,
    reason: String,
) : SarothiException("'$feature' is not implemented: $reason")

/** A capability that exists in code but cannot run in this build or on this device. */
class NativeRuntimeUnavailableException(
    val component: String,
    reason: String,
) : SarothiException("Native runtime '$component' is unavailable: $reason")

/** The GGUF/model file the feature needs is not present (or failed checksum) in the vault. */
class ModelNotInstalledException(
    val modelId: String,
    val expectedFileName: String,
    reason: String,
) : SarothiException("Model '$modelId' ($expectedFileName) is not usable: $reason")

/** A plugin that is registered but declares itself unavailable (missing hardware, API, model…). */
class PluginUnavailableException(
    val pluginName: String,
    val reason: String,
) : SarothiException("Plugin '$pluginName' is unavailable: $reason")

/** A plugin that works once the user supplies a credential/endpoint it cannot invent. */
class PluginNotConfiguredException(
    val pluginName: String,
    val whatIsMissing: String,
    val howToConfigure: String,
) : SarothiException(
    "Plugin '$pluginName' is not configured: $whatIsMissing. To fix: $howToConfigure",
)

/** The plugin is disabled by the user, or the OS permission it needs was refused. */
class PermissionDeniedException(
    val pluginName: String,
    val permission: String,
    val howToGrant: String,
) : SarothiException(
    "Plugin '$pluginName' was denied '$permission'. To grant: $howToGrant",
)

/** The safety layer blocked the action (user pressed "cancel", or a timeout elapsed). */
class SafetyDeniedException(
    val action: String,
    val reason: String,
) : SarothiException("Action '$action' was blocked by the safety layer: $reason")

/** The SD-card vault has not been chosen/created yet. */
class VaultNotInitializedException(
    reason: String,
) : SarothiException("Sarothi's storage folder is not set up: $reason")

/** The vault is encrypted and the master key is not currently unlocked. */
class VaultLockedException(
    reason: String,
) : SarothiException("Sarothi's memory is locked: $reason")

/**
 * Wrong master password. Carries the lockout state so the UI can show a real
 * countdown instead of silently accepting unlimited attempts.
 */
class IncorrectPasswordException(
    val attemptsRemaining: Int,
    val lockoutUntilEpochMillis: Long?,
) : SarothiException(
    buildString {
        append("Incorrect master password.")
        if (lockoutUntilEpochMillis != null) {
            append(" Too many attempts — locked until the backoff expires.")
        } else {
            append(" $attemptsRemaining attempt(s) remain before a backoff is applied.")
        }
    },
)

/** A downloaded file did not match the SHA-256 pinned in the model catalogue. */
class ChecksumMismatchException(
    val fileName: String,
    val expectedSha256: String,
    val actualSha256: String,
) : SarothiException(
    "Checksum mismatch for '$fileName'. Expected $expectedSha256 but computed $actualSha256. " +
        "The file was rejected and not marked usable.",
)

/** Every download source failed. Carries the manual-install instructions. */
class DownloadFailedException(
    val fileName: String,
    val sourcesTried: List<String>,
    val manualInstructions: String,
) : SarothiException(
    "All ${sourcesTried.size} download source(s) failed for '$fileName': ${sourcesTried.joinToString()}. " +
        "Manual install: $manualInstructions",
)

/** The user or the agent cancelled a running operation. */
class OperationCancelledException(
    what: String,
) : SarothiException("'$what' was cancelled")

/**
 * The agent reached a step that needs personal data it does not have. It must
 * never invent an email address, password or OTP, so execution pauses and this
 * is delivered to the user as a question.
 */
/**
 * Thrown when a plugin needs something only the user knows.
 *
 * This is the "pause and ask" mechanism: the exception carries the parameter name
 * so the answer can be routed straight back into it, optional [choices] so the UI
 * can offer buttons instead of a free-text field, and [secret] so the input can be
 * masked and kept out of logs and of the model's context.
 */
class MissingInformationException(
    val field: String,
    val questionForUser: String,
    val choices: List<String> = emptyList(),
    val secret: Boolean = false,
) : SarothiException("Missing information '$field': $questionForUser")

/** The device/app fundamentally cannot do what was asked (documented, not faked). */
class UnsupportedCapabilityException(
    val capability: String,
    reason: String,
) : SarothiException("'$capability' is not supported: $reason")

/** A task exceeded its configured step budget. */
class StepBudgetExceededException(
    val taskId: String,
    val maxSteps: Int,
) : SarothiException(
    "Task '$taskId' stopped after reaching its budget of $maxSteps steps. " +
        "Partial progress is preserved in the task history.",
)

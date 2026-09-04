package com.ngi.sarothi.plugins.meta

import com.google.gson.JsonObject
import com.ngi.sarothi.core.plugin.JsonSchema
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.core.plugin.PluginCategory
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.plugin.pluginContext
import com.ngi.sarothi.core.safety.PermissionGuard
import com.ngi.sarothi.core.safety.SpecialAccess
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import com.ngi.sarothi.plugins.common.LaunchOutcome
import com.ngi.sarothi.plugins.common.launchForResult

/**
 * Reports and fixes what Sarothi is allowed to do.
 *
 * This is the first plugin built, because the agent needs it while planning: rather
 * than discovering a missing permission at step four, the model can ask "do I have
 * what this needs?" and get a real answer, including the exact Android settings
 * screen the user has to open.
 */
class PermissionGuardPlugin : Plugin {

    override val name = "permission_guard"

    override val description =
        "Check whether Sarothi has the permissions and special access a task needs, and open the " +
            "matching Android settings screen. Use it before any task that needs the screen, " +
            "microphone, SMS, contacts, calendar, location or notifications, and whenever another " +
            "tool reports a permission problem."

    override val category = PluginCategory.META
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        description = "Leave everything empty for a full report.",
        properties = mapOf(
            "check" to JsonSchema.Property.Text(
                description = "Another tool's name to check, e.g. 'send_sms'. Empty checks Sarothi as a whole.",
            ),
            "open" to JsonSchema.Property.Text(
                description = "Open this settings screen instead of reporting.",
                enum = listOf(
                    PermissionGuard.ACCESSIBILITY,
                    PermissionGuard.BATTERY_OPTIMISATION,
                    PermissionGuard.DRAW_OVER,
                    PermissionGuard.ALL_FILES,
                    PermissionGuard.USAGE_ACCESS,
                    PermissionGuard.WRITE_SETTINGS,
                ),
            ),
        ),
    )

    override val example = """{"check":"send_sms"}"""

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val guard = context.guard

        params.stringOrNull("open")?.takeIf { it.isNotBlank() }?.let { return openSettings(guard, context, it) }

        params.stringOrNull("check")?.takeIf { it.isNotBlank() }?.let { return checkTool(guard, context, it) }

        val special = guard.specialAccess()
        // "All files access" is optional by design: the SAF vault picker is the
        // recommended path, so its absence is not a problem to report.
        val missing = special.filter { !it.granted && !it.notApplicable && it.id != PermissionGuard.ALL_FILES }
        val data = Json.obj {
            add("special_access", Json.arr { special.forEach { add(accessJson(it)) } })
            addProperty("all_clear", missing.isEmpty())
            add("blocking", Json.arr { missing.forEach { add(it.id) } })
        }
        val summary = if (missing.isEmpty()) {
            "Every special access Sarothi relies on is turned on."
        } else {
            "Not granted: " + missing.joinToString("; ") { "${it.displayName} — ${it.consequence}" }
        }
        return PluginResult.Success(summary, data, spoken = if (missing.isEmpty()) null else "কিছু অনুমতি বাকি আছে।")
    }

    private fun accessJson(access: SpecialAccess): JsonObject = Json.obj {
        addProperty("id", access.id)
        addProperty("name", access.displayName)
        addProperty("granted", access.granted)
        addProperty("purpose", access.purpose)
        if (!access.granted) addProperty("without_it", access.consequence)
        if (access.notApplicable) addProperty("not_applicable", true)
    }

    private fun checkTool(guard: PermissionGuard, context: com.ngi.sarothi.core.plugin.PluginContext, toolName: String): PluginResult {
        val plugin = context.plugins.get(toolName)
            ?: return PluginResult.Failure(
                summaryForUser = "'$toolName' is not a Sarothi tool, so there is nothing to check. " +
                    "Known tools: ${context.plugins.all().joinToString { it.name }}",
                errorClass = "UnknownPluginException",
                retriable = false,
            )
        val verdict = guard.verdictFor(plugin)
        val data = Json.obj {
            addProperty("tool", toolName)
            addProperty("declared_sensitivity", plugin.sensitivity.name.lowercase())
            addProperty("allowed", verdict.allowed)
            add("missing_permissions", Json.arr { verdict.missingRuntime.forEach { add(it) } })
            add("missing_special_access", Json.arr { verdict.missingSpecial.forEach { add(it) } })
            verdict.missingRuntime.forEach { permission ->
                val explanation = guard.describe(permission)
                add("why_${permission.substringAfterLast('.')}", Json.obj {
                    addProperty("en", explanation.english)
                    addProperty("bn", explanation.bangla)
                })
            }
        }
        return if (verdict.allowed) {
            PluginResult.Success("'$toolName' has everything it needs.", data)
        } else {
            PluginResult.Failure(
                summaryForUser = verdict.explanation,
                errorClass = "PermissionDeniedException",
                retriable = false,
                data = data,
            )
        }
    }

    private fun openSettings(
        guard: PermissionGuard,
        context: com.ngi.sarothi.core.plugin.PluginContext,
        id: String,
    ): PluginResult {
        val access = guard.specialAccess().firstOrNull { it.id == id }
            ?: return PluginResult.Failure(
                summaryForUser = "'$id' is not a Sarothi special-access id. Known ids: " +
                    guard.specialAccess().joinToString { it.id },
                errorClass = "IllegalArgumentException",
                retriable = false,
            )
        if (access.notApplicable) {
            return PluginResult.Success(
                "${access.displayName} does not exist on this Android version, so there is nothing to turn on.",
                Json.obj { addProperty("not_applicable", true) },
            )
        }
        if (access.granted) {
            return PluginResult.Success(
                "${access.displayName} is already turned on.",
                Json.obj { addProperty("granted", true) },
            )
        }
        val intent = access.settingsIntent
            ?: return PluginResult.Failure(
                summaryForUser = "Android offers no settings screen for ${access.displayName} on this device. " +
                    "Open Settings manually and look for Sarothi.",
                errorClass = "UnsupportedCapabilityException",
                retriable = false,
            )
        return when (val outcome = context.appContext.launchForResult(intent)) {
            LaunchOutcome.Started -> PluginResult.Success(
                summaryForUser = "Opened Android's settings for ${access.displayName}. ${access.purpose}",
                data = Json.obj { addProperty("opened", access.id) },
                spoken = "সেটিংস খুলে দিয়েছি — ${access.displayName} চালু করুন।",
            )
            is LaunchOutcome.NoHandler -> PluginResult.Failure(outcome.reason, "ActivityNotFoundException")
            is LaunchOutcome.Refused -> PluginResult.Failure(outcome.reason, "SecurityException")
        }
    }
}

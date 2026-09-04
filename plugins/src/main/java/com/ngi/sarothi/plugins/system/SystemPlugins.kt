package com.ngi.sarothi.plugins.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import com.google.gson.JsonObject
import com.ngi.sarothi.core.plugin.JsonSchema
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.core.plugin.PluginAvailability
import com.ngi.sarothi.core.plugin.PluginCategory
import com.ngi.sarothi.core.plugin.PluginContext
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.plugin.pluginContext
import com.ngi.sarothi.core.runtime.RamPolicy
import com.ngi.sarothi.core.plugin.ConfirmationPreview
import com.ngi.sarothi.core.safety.ConfirmationReason
import com.ngi.sarothi.core.safety.PermissionGuard
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import com.ngi.sarothi.plugins.common.Formatting
import com.ngi.sarothi.plugins.common.LaunchOutcome
import com.ngi.sarothi.plugins.common.launchForResult

/** Battery level, charging state and a plain-language reading of it. */
class BatteryStatusPlugin : Plugin {
    override val name = "battery_status"
    override val description =
        "Read the battery: percentage, whether it is charging, temperature, health and the phone's own " +
            "power-saving state. Use it before starting anything long — a screen-agent task on 8% will " +
            "not finish."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(properties = emptyMap())

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val appContext = context.appContext
        val manager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

        val level = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level == null || level < 0) {
            return PluginResult.Failure(
                summaryForUser = "Android would not report the battery level on this device.",
                errorClass = "BatteryUnavailableException",
                retriable = true,
            )
        }

        val sticky = runCatching {
            appContext.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val health = sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val temperature = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val voltage = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val batteryPresent = sticky?.getIntExtra(BatteryManager.EXTRA_PRESENT, 1) == 1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val data = Json.obj {
            addProperty("percent", level)
            addProperty("charging", charging)
            addProperty("status", describeStatus(status))
            addProperty("health", describeHealth(health))
            addProperty("plugged_in_with", describePlugged(plugged))
            addProperty("temperature_c", temperature / 10.0)
            addProperty("voltage_v", voltage / 1000.0)
            addProperty("present", batteryPresent)
            powerManager?.let { addProperty("power_save_mode", it.isPowerSaveMode) }
            addProperty("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            addProperty("android_version", Build.VERSION.RELEASE)
        }

        val advice = when {
            level <= 5 && !charging -> "At $level% and not charging, Sarothi should not start anything long."
            level <= 15 && !charging -> "At $level%, a screen-agent or model task may not finish."
            temperature >= 400 -> "The battery is at ${temperature / 10.0}°C, which is hot. Heavy work will throttle."
            else -> null
        }
        advice?.let { data.addProperty("advice", it) }

        return PluginResult.Success(
            summaryForUser = "Battery $level%" +
                (if (charging) ", charging (${describePlugged(plugged)})" else ", not charging") +
                ", ${describeStatus(status)}, ${temperature / 10.0}°C" +
                (advice?.let { ". $it" } ?: ""),
            data = data,
        )
    }

    private fun describeStatus(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
        else -> "unknown"
    }

    private fun describeHealth(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "unspecified failure"
        else -> "unknown"
    }

    private fun describePlugged(plugged: Int): String = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        0 -> "nothing"
        else -> "unknown source"
    }
}

/** Free and total space, internal and SD card. */
class StorageStatusPlugin : Plugin {
    override val name = "storage_status"
    override val description =
        "How much storage is free, on internal storage and on the SD card if Sarothi's vault is there. " +
            "Use it before downloading a model or taking many screenshots."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(properties = emptyMap())

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val appContext = context.appContext

        val internal = runCatching {
            val stats = StatFs(appContext.filesDir.absolutePath)
            stats.totalBytes to stats.availableBytes
        }.getOrNull()

        val storageManager = appContext.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val volumes = storageManager?.storageVolumes ?: emptyList()
        val removable = volumes.filter { it.isRemovable }

        val external = removable.firstNotNullOfOrNull { volume ->
            val directory = runCatching { volume.directory }.getOrNull() ?: return@firstNotNullOfOrNull null
            runCatching {
                val stats = StatFs(directory.absolutePath)
                Triple(volume.toString(), stats.totalBytes, stats.availableBytes)
            }.getOrNull()
        }

        if (internal == null && external == null) {
            return PluginResult.Failure(
                summaryForUser = "Android would not report storage statistics on this device.",
                errorClass = "StorageUnavailableException",
                retriable = true,
            )
        }

        val data = Json.obj {
            internal?.let { (total, available) ->
                addProperty("internal_total_bytes", total)
                addProperty("internal_available_bytes", available)
                addProperty("internal_total", Formatting.bytes(total))
                addProperty("internal_available", Formatting.bytes(available))
                addProperty("internal_used_percent", if (total > 0) ((total - available) * 100 / total) else -1)
            }
            if (external != null) {
                addProperty("removable_total_bytes", external.second)
                addProperty("removable_available_bytes", external.third)
                addProperty("removable_total", Formatting.bytes(external.second))
                addProperty("removable_available", Formatting.bytes(external.third))
            } else {
                addProperty("removable_present", removable.isNotEmpty())
                addProperty("removable_note", if (removable.isEmpty()) {
                    "No removable storage is mounted."
                } else {
                    "Removable storage is mounted but Sarothi has no permission to read its free space."
                })
            }
            addProperty("removable_volume_count", removable.size)
        }

        val largestModel = com.ngi.sarothi.core.model.ModelCatalog.ALL.maxByOrNull { it.sizeBytes }
        largestModel?.let { model ->
            val available = external?.third ?: internal?.second ?: 0L
            data.addProperty("largest_model", model.fileName)
            data.addProperty("largest_model_bytes", model.sizeBytes)
            data.addProperty("largest_model_fits", available >= model.sizeBytes)
        }

        return PluginResult.Success(
            summaryForUser = buildString {
                internal?.let { (total, available) ->
                    append("Internal: ").append(Formatting.bytes(available))
                    append(" free of ").append(Formatting.bytes(total)).append('.')
                }
                if (external != null) {
                    append(" Removable: ").append(Formatting.bytes(external.third))
                    append(" free of ").append(Formatting.bytes(external.second)).append('.')
                }
            },
            data = data,
        )
    }
}

/** RAM situation, and what Sarothi is allowed to load because of it. */
class MemoryStatusPlugin : Plugin {
    override val name = "memory_status"
    override val description =
        "How much RAM this phone has, how much is free, and which of Sarothi's models fit as a result. " +
            "Use it before loading the vision model or when something has failed with an out-of-memory " +
            "error."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(properties = emptyMap())

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        // RamPolicy reads ActivityManager.getMemoryInfo() itself; if the service is
        // missing on this device it throws, and that is reported rather than
        // turned into a plausible-looking zero.
        val policy = runCatching { RamPolicy(context.appContext) }.getOrElse { failure ->
            return PluginResult.Failure(
                summaryForUser = "Android would not report memory statistics: " +
                    "${failure.javaClass.simpleName}: ${failure.message}",
                errorClass = failure.javaClass.simpleName,
                retriable = false,
            )
        }
        val memory = policy.memory

        val data = Json.obj {
            addProperty("total_bytes", memory.totalBytes)
            addProperty("available_bytes", memory.availableBytes)
            addProperty("total", Formatting.bytes(memory.totalBytes))
            addProperty("available", Formatting.bytes(memory.availableBytes))
            addProperty("total_mib", memory.totalMiB)
            addProperty("available_mib", memory.availableMiB)
            addProperty("low_memory_threshold_bytes", memory.lowMemoryThresholdBytes)
            addProperty("system_says_low", memory.isLowMemory)
            addProperty("tier", policy.tier.name.lowercase())
            addProperty("tier_derived", policy.derivedTier.name.lowercase())
            addProperty("tier_overridden", policy.tierOverride != null)
            addProperty("may_keep_vision_resident", policy.mayKeepVisionResident())
            addProperty("text_context_tokens", policy.contextTokens(com.ngi.sarothi.core.model.ModelRole.TEXT_ORCHESTRATOR))
            addProperty("vision_context_tokens", policy.contextTokens(com.ngi.sarothi.core.model.ModelRole.VISION_SCREEN_AGENT))
            addProperty("inference_threads", policy.inferenceThreads())
            addProperty("max_resident_models", policy.maxResidentModels())
            addProperty("batch_tokens", policy.batchTokens())
            addProperty("explanation", policy.describe())
        }
        return PluginResult.Success(
            summaryForUser = policy.describe(),
            data = data,
        )
    }
}

/** Reads the screen brightness. */
class BrightnessPlugin : Plugin {
    override val name = "brightness"
    override val description =
        "Read or set the screen brightness. Setting it needs the 'Modify system settings' special " +
            "permission, which Sarothi cannot grant itself — it will open the right settings screen and " +
            "tell the user what to do. Setting brightness is confirmed first: a phone suddenly going " +
            "dark or blindingly bright is disorienting."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.SENSITIVE

    override val parameters = JsonSchema(
        properties = mapOf(
            "action" to JsonSchema.Property.Text("Read the current level, or set a new one.", enum = listOf("read", "set"), default = "read"),
            "level" to JsonSchema.Property.Integer("Brightness 0-255, or -1 for automatic.", minimum = -1, maximum = 255),
        ),
    )

    override fun describeForConfirmation(params: JsonObject): ConfirmationPreview {
        val action = params.stringOrNull("action") ?: "read"
        val level = params.get("level")?.takeIf { it.isJsonPrimitive }?.asInt
        return ConfirmationPreview(
            title = "Change the screen brightness?",
            detailLines = listOf(
                if (action == "set") {
                    "New level: " + when {
                        level == null -> "(not specified)"
                        level < 0 -> "automatic"
                        else -> "$level of 255"
                    }
                } else {
                    "No change — just reading the current level."
                },
                "This changes the whole screen, for every app, until it is changed back.",
            ),
            reason = ConfirmationReason.DESTRUCTIVE_SYSTEM_ACTION,
            allowRemember = true,
        )
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val action = params.stringOrNull("action") ?: "read"
        val resolver = context.appContext.contentResolver

        val currentMode = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        }.getOrDefault(-1)
        val currentLevel = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        val automatic = currentMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

        if (action != "set") {
            return PluginResult.Success(
                summaryForUser = if (currentLevel < 0) {
                    "Android would not report the brightness level."
                } else {
                    "Screen brightness is $currentLevel of 255" +
                        if (automatic) " and set to automatic, so the system adjusts it." else "."
                },
                data = Json.obj {
                    addProperty("level", currentLevel)
                    addProperty("automatic", automatic)
                    addProperty("mode_raw", currentMode)
                },
            )
        }

        val level = params.get("level")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: throw com.ngi.sarothi.core.error.MissingInformationException(
                field = "level",
                questionForUser = "How bright should the screen be? A number from 0 (dark) to 255 " +
                    "(brightest), or -1 for automatic.",
            )
        if (level !in -1..255) {
            return PluginResult.Failure(
                "Brightness has to be between 0 and 255, or -1 for automatic.",
                "ValueOutOfRangeException",
                retriable = true,
            )
        }

        if (!context.guard.canWriteSettings()) {
            val intent = PermissionGuard(context.appContext).settingsIntentFor(PermissionGuard.WRITE_SETTINGS)
            val launched = intent?.let { context.appContext.launchForResult(it) }
            return PluginResult.Unavailable(
                PluginAvailability.unavailable(
                    reason = "Android does not let Sarothi change system settings. This is a special " +
                        "permission it cannot ask for with a normal dialog.",
                    fixAction = if (launched == LaunchOutcome.Started) {
                        "Sarothi opened Settings → Modify system settings. Turn the switch on for " +
                            "Sarothi, then ask again."
                    } else {
                        "Open Settings → Apps → Sarothi → Modify system settings and turn it on."
                    },
                ),
            )
        }

        val written = runCatching {
            if (level < 0) {
                Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                )
            } else {
                if (automatic) {
                    Settings.System.putInt(
                        resolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    )
                }
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level)
            }
        }
        return written.fold(
            onSuccess = { ok ->
                if (ok) {
                    PluginResult.Success(
                        summaryForUser = if (level < 0) {
                            "Switched the screen to automatic brightness (it was $currentLevel of 255)."
                        } else {
                            "Set the screen brightness to $level of 255 (it was $currentLevel)."
                        },
                        data = Json.obj {
                            addProperty("level", level)
                            addProperty("previous_level", currentLevel)
                            addProperty("was_automatic", automatic)
                        },
                        spoken = "উজ্জ্বলতা বদলে দিয়েছি।",
                    )
                } else {
                    PluginResult.Failure(
                        "Android accepted the request but reported the change did not take effect.",
                        "SettingWriteFailedException",
                        retriable = true,
                    )
                }
            },
            onFailure = { failure ->
                PluginResult.Failure(
                    summaryForUser = "Android refused the brightness change: " +
                        "${failure.javaClass.simpleName}: ${failure.message}",
                    errorClass = failure.javaClass.simpleName,
                    retriable = false,
                )
            },
        )
    }
}

/** Wi-Fi state. Turning it on is not possible from an app on modern Android. */
class WifiStatusPlugin : Plugin {
    override val name = "wifi_status"
    override val description =
        "Read the Wi-Fi and mobile-data state, including the network Sarothi would use. It cannot turn " +
            "Wi-Fi on or off: Android removed that from apps in 2018, so instead it opens the Wi-Fi " +
            "settings screen for the user."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "open_settings" to JsonSchema.Property.Flag("Also open the Wi-Fi settings screen.", default = false),
        ),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val openSettings = params.get("open_settings")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

        val wifiManager = context.appContext.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiEnabled = wifiManager?.isWifiEnabled
        val networkType = context.network.activeType()
        val online = context.network.isOnline()

        val ssid = if (wifiEnabled == true) {
            runCatching { readSsid(context.appContext) }.getOrNull()
        } else {
            null
        }

        val data = Json.obj {
            addProperty("wifi_enabled", wifiEnabled ?: false)
            addProperty("wifi_readable", wifiManager != null)
            ssid?.let { addProperty("ssid", it) }
            addProperty("active_network", networkType.name.lowercase())
            addProperty("online", online)
            context.network.blockReason(allowMobileData = false)?.let { addProperty("download_blocked", it) }
        }

        var opened = false
        if (openSettings) {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            opened = context.appContext.launchForResult(intent) == LaunchOutcome.Started
            data.addProperty("settings_opened", opened)
        }

        return PluginResult.Success(
            summaryForUser = buildString {
                append("Wi-Fi is ").append(if (wifiEnabled == true) "on" else "off")
                ssid?.let { append(" (connected to $it)") }
                append(". Active network: ").append(networkType.name.lowercase())
                if (!online) append(", and the phone is offline")
                if (openSettings) {
                    append(if (opened) ". Sarothi opened the Wi-Fi settings." else ". The Wi-Fi settings could not be opened.")
                }
                append('.')
            },
            data = data,
        )
    }

    /**
     * The connected network's name.
     *
     * On Android 10+ an app needs location permission for this, and returns
     * `<unknown ssid>` without it. That string is not a real network name, so it
     * is reported as null rather than shown to the user as though it were.
     */
    private fun readSsid(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = runCatching { manager?.connectionInfo }.getOrNull()
            val raw = info?.ssid?.trim('"')?.takeIf { it.isNotEmpty() }
            return raw?.takeIf { !it.equals("<unknown ssid>", ignoreCase = true) }
        }
        @Suppress("DEPRECATION")
        val manager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val info = runCatching { manager?.connectionInfo }.getOrNull()
        val raw = info?.ssid?.trim('"')?.takeIf { it.isNotEmpty() }
        return raw?.takeIf { !it.equals("<unknown ssid>", ignoreCase = true) }
    }
}

/** App usage — needs the Usage Access special permission, and says so when it is missing. */
class AppUsagePlugin : Plugin {
    override val name = "app_usage"
    override val description =
        "How much time was spent in each app over a period. Needs the Usage Access special permission, " +
            "which Sarothi cannot request with a normal dialog — it opens the settings screen and tells " +
            "the user what to switch on."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "days" to JsonSchema.Property.Integer("How many days back to look.", minimum = 1, maximum = 30, default = 7),
            "limit" to JsonSchema.Property.Integer("How many apps to list.", minimum = 1, maximum = 40, default = 15),
        ),
    )

    override val requiredPermissions = emptyList<String>()

    override suspend fun availability(context: PluginContext): PluginAvailability =
        if (context.guard.usageAccessGranted()) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                reason = "Reading app usage needs the 'Usage access' special permission, which Sarothi " +
                    "does not have.",
                fixAction = "Run permission_guard with open=usage_access; Sarothi will open the screen " +
                    "and the user can turn it on.",
            )
        }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        if (!context.guard.usageAccessGranted()) {
            return PluginResult.Unavailable(availability(context))
        }
        val days = params.get("days")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, 30) ?: 7
        val limit = params.get("limit")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, 40) ?: 15

        val usageManager = context.appContext.getSystemService(Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager
            ?: return PluginResult.Failure(
                "Android exposes no usage-stats service on this device.",
                "UsageUnavailableException",
                retriable = false,
            )

        val end = System.currentTimeMillis()
        val start = end - days * 86_400_000L
        val stats = runCatching {
            usageManager.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, end)
        }.getOrElse { failure ->
            return PluginResult.Failure(
                summaryForUser = "Android refused the usage query: ${failure.javaClass.simpleName}",
                errorClass = failure.javaClass.simpleName,
                retriable = false,
            )
        }
        if (stats.isNullOrEmpty()) {
            return PluginResult.Failure(
                summaryForUser = "Android returned no usage statistics for the last $days day(s), even " +
                    "though Usage Access is on. Some manufacturers return nothing until the stats " +
                    "service has accumulated data.",
                errorClass = "NoUsageDataException",
                retriable = true,
            )
        }

        val byPackage = linkedMapOf<String, Long>()
        stats.forEach { entry ->
            val time = entry.totalTimeInForeground
            if (time <= 0) return@forEach
            byPackage[entry.packageName] = (byPackage[entry.packageName] ?: 0L) + time
        }
        val sorted = byPackage.entries.sortedByDescending { it.value }.take(limit)
        val totalForeground = byPackage.values.sum()

        val data = Json.obj {
            add("apps", Json.arr {
                sorted.forEach { (packageName, millis) ->
                    add(Json.obj {
                        addProperty("package", packageName)
                        addProperty("app", appLabel(context.appContext, packageName))
                        addProperty("foreground_millis", millis)
                        addProperty("foreground", Formatting.duration(millis))
                        addProperty("share_percent", if (totalForeground > 0) millis * 100 / totalForeground else 0)
                    })
                }
            })
            addProperty("days", days)
            addProperty("total_foreground", Formatting.duration(totalForeground))
            addProperty("app_count", byPackage.size)
        }
        return PluginResult.Success(
            summaryForUser = "Over $days day(s): ${Formatting.duration(totalForeground)} in the foreground " +
                "across ${byPackage.size} apps. Most used: " +
                sorted.take(3).joinToString("; ") { "${appLabel(context.appContext, it.key)} ${Formatting.duration(it.value)}" },
            data = data,
        )
    }

    private fun appLabel(context: Context, packageName: String): String = runCatching {
        val manager = context.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}

/** The phone's own identity and Android version. */
class DeviceInfoPlugin : Plugin {
    override val name = "device_info"
    override val description =
        "This phone's maker, model, Android version, screen size, RAM tier and which of Sarothi's " +
            "capabilities are actually usable here. Use it when the user asks what Sarothi can do on " +
            "their device."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(properties = emptyMap())

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val appContext = context.appContext
        val policy = runCatching { RamPolicy(appContext) }.getOrNull()
        val screen = context.screen.availability()

        val data = Json.obj {
            addProperty("manufacturer", Build.MANUFACTURER)
            addProperty("model", Build.MODEL)
            addProperty("device", Build.DEVICE)
            addProperty("android_version", Build.VERSION.RELEASE)
            addProperty("sdk_int", Build.VERSION.SDK_INT)
            addProperty("native_runtime_present", com.ngi.sarothi.core.runtime.NativeBridge.isLoaded)
            policy?.let { ram ->
                addProperty("ram_total", Formatting.bytes(ram.memory.totalBytes))
                addProperty("ram_available", Formatting.bytes(ram.memory.availableBytes))
                addProperty("ram_tier", ram.tier.name.lowercase())
                addProperty("may_keep_vision_resident", ram.mayKeepVisionResident())
                addProperty("max_resident_models", ram.maxResidentModels())
            }
            add("capabilities", Json.obj {
                addProperty("accessibility_connected", screen.accessibilityConnected)
                addProperty("screen_capture_permitted", screen.capturePermissionGranted)
                addProperty("ocr_available", screen.ocrAvailable)
                addProperty("vision_model_available", screen.visionAvailable)
                addProperty("exact_alarms_allowed", context.scheduler.canScheduleExactAlarms)
                addProperty("usage_access", context.guard.usageAccessGranted())
                addProperty("write_settings", context.guard.canWriteSettings())
                addProperty("draw_over_apps", context.guard.canDrawOverlays())
                addProperty("battery_optimisation_exempt", context.guard.ignoringBatteryOptimisations())
                addProperty("vault_unlocked", context.vault.isUnlocked)
            })
            screen.detail.takeIf { it.isNotBlank() }?.let { addProperty("screen_note", it) }
        }
        return PluginResult.Success(
            summaryForUser = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}" +
                (policy?.let { ", ${it.memory.totalMiB} MiB RAM (${it.tier.name.lowercase()})" } ?: "") +
                ", native runtime " + if (com.ngi.sarothi.core.runtime.NativeBridge.isLoaded) "loaded" else "NOT loaded",
            data = data,
        )
    }
}

/** Opens one of Android's own settings screens. */
class OpenSettingsPlugin : Plugin {
    override val name = "open_settings"
    override val description =
        "Open a specific Android settings screen: wifi, bluetooth, apps, display, sound, battery, " +
            "storage, location, accessibility, notifications, date/time, developer options. Use it when " +
            "the user asks Sarothi to change something only Android itself can change."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "screen" to JsonSchema.Property.Text(
                "Which settings screen.",
                enum = SETTINGS_SCREENS.keys.toList(),
            ),
        ),
        required = listOf("screen"),
    )

    override val example = """{"screen":"accessibility"}"""

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val wanted = params.stringOrNull("screen")?.trim()?.lowercase()
            ?: throw com.ngi.sarothi.core.error.MissingInformationException(
                field = "screen",
                questionForUser = "Which settings screen should Sarothi open?",
                choices = SETTINGS_SCREENS.keys.toList(),
            )
        val factory = SETTINGS_SCREENS[wanted]
            ?: return PluginResult.Failure(
                summaryForUser = "\"$wanted\" is not a settings screen Sarothi knows. It can open: " +
                    SETTINGS_SCREENS.keys.joinToString(),
                errorClass = "UnknownScreenException",
                retriable = true,
            )

        val intent = factory(context.appContext)
            ?: return PluginResult.Unavailable(
                PluginAvailability.unavailable(
                    "Android offers no intent for the '$wanted' screen on this device.",
                    fixAction = "Open the Settings app and look for it manually.",
                ),
            )
        return when (val outcome = context.appContext.launchForResult(intent)) {
            LaunchOutcome.Started -> PluginResult.Success(
                "Opened the $wanted settings screen.",
                Json.obj { addProperty("screen", wanted) },
                spoken = "সেটিংস খুলে দিয়েছি।",
            )
            is LaunchOutcome.NoHandler -> PluginResult.Failure(outcome.reason, "ActivityNotFoundException")
            is LaunchOutcome.Refused -> PluginResult.Failure(outcome.reason, "SecurityException")
        }
    }

    private companion object {
        val SETTINGS_SCREENS: Map<String, (Context) -> Intent?> = linkedMapOf(
            "home" to { Intent(Settings.ACTION_SETTINGS) },
            "wifi" to { Intent(Settings.ACTION_WIFI_SETTINGS) },
            "bluetooth" to { Intent(Settings.ACTION_BLUETOOTH_SETTINGS) },
            "mobile_data" to { Intent(Settings.ACTION_DATA_ROAMING_SETTINGS) },
            "apps" to { Intent(Settings.ACTION_APPLICATION_SETTINGS) },
            "display" to { Intent(Settings.ACTION_DISPLAY_SETTINGS) },
            "sound" to { Intent(Settings.ACTION_SOUND_SETTINGS) },
            "battery" to { Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS) },
            "storage" to { Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS) },
            "location" to { Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
            "accessibility" to { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
            "notifications" to {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                else Intent(Settings.ACTION_SETTINGS)
            },
            "date_time" to { Intent(Settings.ACTION_DATE_SETTINGS) },
            "language" to { Intent(Settings.ACTION_LOCALE_SETTINGS) },
            "security" to { Intent(Settings.ACTION_SECURITY_SETTINGS) },
            "developer" to { Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
            "airplane" to { Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS) },
            "sarothi_app_info" to { context ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
            },
        )
    }
}

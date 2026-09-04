package com.ngi.sarothi.core.agent

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import com.ngi.sarothi.core.runtime.MemoryTier
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The handful of device facts the planner is told, so it can reason about
 * "is it evening", "is the battery low", "am I on a small phone" without a tool
 * call for each one.
 */
data class DeviceBrief(
    val localTime: String,
    val timeZone: String,
    val locale: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: Int,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val memoryTier: MemoryTier,
    val freeStorageMiB: Long?,
    val accessibilityConnected: Boolean,
    val capturePermitted: Boolean,
) {
    fun toPromptBlock(): String = buildString {
        append("NOW: ").append(localTime).append(" (").append(timeZone).append(")\n")
        append("DEVICE: ").append(manufacturer).append(' ').append(model)
            .append(", Android ").append(androidVersion)
        batteryPercent?.let { append(", battery ").append(it).append('%') }
        charging?.let { if (it) append(" (charging)") }
        freeStorageMiB?.let { append(", ").append(it).append(" MiB free storage") }
        append("\nMEMORY TIER: ").append(memoryTier.name.lowercase())
        append(" — keep plans short; large multi-app plans get killed.\n")
        append("SCREEN ACCESS: ")
        append(
            when {
                accessibilityConnected -> "accessibility service connected (can read and control apps)"
                capturePermitted -> "screenshot only (cannot tap or type)"
                else -> "none (screen tools will fail; tell the user to enable accessibility)"
            },
        ).append('\n')
    }

    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEE")

        fun read(context: Context, memoryTier: MemoryTier, accessibilityConnected: Boolean, capturePermitted: Boolean): DeviceBrief {
            val battery = readBattery(context)
            val zone = ZoneId.systemDefault()
            return DeviceBrief(
                localTime = LocalDateTime.now(zone).format(TIME_FORMAT),
                timeZone = zone.id,
                locale = java.util.Locale.getDefault().toLanguageTag(),
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                androidVersion = Build.VERSION.SDK_INT,
                batteryPercent = battery?.first,
                charging = battery?.second,
                memoryTier = memoryTier,
                freeStorageMiB = readFreeStorageMiB(),
                accessibilityConnected = accessibilityConnected,
                capturePermitted = capturePermitted,
            )
        }

        private fun readBattery(context: Context): Pair<Int, Boolean>? {
            val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
            val percent = runCatching { manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
                .getOrDefault(-1)
            val charging = runCatching { manager.isCharging }.getOrDefault(false)
            return if (percent in 0..100) percent to charging else null
        }

        private fun readFreeStorageMiB(): Long? = runCatching {
            val stats = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            stats.availableBytes / (1024L * 1024L)
        }.getOrNull()
    }
}

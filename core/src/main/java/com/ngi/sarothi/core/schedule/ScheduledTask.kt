package com.ngi.sarothi.core.schedule

import com.google.gson.JsonObject
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.arrayOrNull
import com.ngi.sarothi.core.util.stringOrNull
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** How often a scheduled task repeats. */
enum class Recurrence(val displayName: String) {
    ONCE("Once"),
    HOURLY("Every hour"),
    DAILY("Every day"),
    WEEKLY("Every week"),
    MONTHLY("Every month");

    companion object {
        fun fromJson(value: String?): Recurrence? = value?.let { raw ->
            entries.firstOrNull { it.name.equals(raw, true) }
        }
    }
}

/**
 * A task Sarothi should run by itself at a given time.
 *
 * [allowSensitiveSteps] is the important switch: an unattended run has nobody to
 * confirm with, so by default the safety gate denies every SENSITIVE and CRITICAL
 * step. Turning it on does **not** bypass confirmation — it is not possible to
 * bypass it — it only decides whether the task is allowed to contain such steps at
 * all, and the UI says plainly that they will be skipped and reported.
 */
data class ScheduledTask(
    val id: String,
    val title: String,
    val request: String,
    val recurrence: Recurrence,
    val timeOfDay: LocalTime?,
    val daysOfWeek: Set<DayOfWeek>,
    val dayOfMonth: Int?,
    val oneShotAtEpochMillis: Long?,
    val enabled: Boolean,
    val createdAt: String,
    val lastRunAtEpochMillis: Long?,
    val lastRunStatus: String?,
    val lastRunMessage: String?,
    val nextRunAtEpochMillis: Long?,
    val allowSensitiveSteps: Boolean,
    val runCount: Int,
    /** True when the alarm had to be inexact because the OS withheld the permission. */
    val alarmIsApproximate: Boolean,
) {
    fun toJson(): JsonObject = Json.obj {
        addProperty("id", id)
        addProperty("title", title)
        addProperty("request", request)
        addProperty("recurrence", recurrence.name.lowercase())
        timeOfDay?.let { addProperty("time_of_day", it.toString()) }
        if (daysOfWeek.isNotEmpty()) add("days", Json.arr { daysOfWeek.sortedBy { it.value }.forEach { add(it.name.lowercase()) } })
        dayOfMonth?.let { addProperty("day_of_month", it) }
        oneShotAtEpochMillis?.let { addProperty("one_shot_at", it) }
        addProperty("enabled", enabled)
        addProperty("created_at", createdAt)
        lastRunAtEpochMillis?.let { addProperty("last_run_at", it) }
        lastRunStatus?.let { addProperty("last_run_status", it) }
        lastRunMessage?.let { addProperty("last_run_message", it) }
        nextRunAtEpochMillis?.let { addProperty("next_run_at", it) }
        addProperty("allow_sensitive_steps", allowSensitiveSteps)
        addProperty("run_count", runCount)
        addProperty("alarm_approximate", alarmIsApproximate)
    }

    /**
     * When this task should next fire, or null when it will never fire again.
     *
     * Computed from real calendar arithmetic in the device's zone, so "every
     * Monday at 08:00" survives a DST change and a month boundary.
     */
    fun computeNextRun(fromEpochMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!enabled) return null
        val from = Instant.ofEpochMilli(fromEpochMillis).atZone(zone)

        if (recurrence == Recurrence.ONCE) {
            val target = oneShotAtEpochMillis ?: return null
            return if (target > fromEpochMillis) target else null
        }

        // Hourly has to be settled before the candidate below is seeded from timeOfDay.
        // Seeding it first and then matching HOURLY unconditionally returns that daily
        // time on the first pass, so "every hour" fires once a day at 09:00 and the
        // plusHours(1) step is unreachable. A task nobody set a time for has no
        // time-of-day meaning at all, so it is not consulted here.
        if (recurrence == Recurrence.HOURLY) {
            return from.plusHours(1).withMinute(0).withSecond(0).withNano(0)
                .toInstant().toEpochMilli()
        }

        val time = timeOfDay ?: LocalTime.of(9, 0)
        var candidate = from.toLocalDate().atTime(time).atZone(zone)
        if (!candidate.toInstant().isAfter(from.toInstant())) candidate = candidate.plusDays(1)

        // Up to a year of search: a weekly task with one weekday set is at most 7
        // days away, and a monthly one at most 31. Anything further means the
        // configuration can never fire, which is reported as null rather than
        // looping forever.
        repeat(370) {
            val matches = when (recurrence) {
                Recurrence.DAILY -> true
                Recurrence.WEEKLY -> daysOfWeek.isEmpty() || candidate.dayOfWeek in daysOfWeek
                Recurrence.MONTHLY -> {
                    val day = (dayOfMonth ?: 1).coerceIn(1, 31)
                    candidate.dayOfMonth == day ||
                        (day > candidate.toLocalDate().lengthOfMonth() &&
                            candidate.toLocalDate() == candidate.toLocalDate().with(TemporalAdjusters.lastDayOfMonth()))
                }
                else -> false
            }
            if (matches) return candidate.toInstant().toEpochMilli()
            candidate = candidate.plusDays(1)
        }
        return null
    }

    companion object {
        fun fromJson(json: JsonObject): ScheduledTask? {
            val id = json.stringOrNull("id") ?: return null
            val request = json.stringOrNull("request") ?: return null
            return ScheduledTask(
                id = id,
                title = json.stringOrNull("title") ?: request.take(40),
                request = request,
                recurrence = Recurrence.fromJson(json.stringOrNull("recurrence")) ?: Recurrence.ONCE,
                timeOfDay = json.stringOrNull("time_of_day")?.let { raw ->
                    runCatching { LocalTime.parse(raw) }.getOrNull()
                },
                daysOfWeek = json.arrayOrNull("days")?.mapNotNull { element ->
                    if (!element.isJsonPrimitive) return@mapNotNull null
                    runCatching { DayOfWeek.valueOf(element.asString.uppercase()) }.getOrNull()
                }?.toSet() ?: emptySet(),
                dayOfMonth = json.get("day_of_month")?.takeIf { it.isJsonPrimitive }?.asInt,
                oneShotAtEpochMillis = json.get("one_shot_at")?.takeIf { it.isJsonPrimitive }?.asLong,
                enabled = json.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
                createdAt = json.stringOrNull("created_at") ?: Instant.now().toString(),
                lastRunAtEpochMillis = json.get("last_run_at")?.takeIf { it.isJsonPrimitive }?.asLong,
                lastRunStatus = json.stringOrNull("last_run_status"),
                lastRunMessage = json.stringOrNull("last_run_message"),
                nextRunAtEpochMillis = json.get("next_run_at")?.takeIf { it.isJsonPrimitive }?.asLong,
                allowSensitiveSteps = json.get("allow_sensitive_steps")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                runCount = json.get("run_count")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                alarmIsApproximate = json.get("alarm_approximate")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            )
        }

        /** Human-readable schedule, for the UI and for confirmations. */
        fun describe(task: ScheduledTask): String {
            val time = task.timeOfDay?.toString()?.take(5) ?: ""
            return when (task.recurrence) {
                Recurrence.ONCE -> {
                    val moment = task.oneShotAtEpochMillis?.let {
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
                            .toString().take(16).replace('T', ' ')
                    } ?: "an unspecified time"
                    "once, at $moment"
                }
                Recurrence.HOURLY -> "every hour"
                Recurrence.DAILY -> if (time.isBlank()) "every day" else "every day at $time"
                Recurrence.WEEKLY -> {
                    val days = task.daysOfWeek.sortedBy { it.value }.joinToString(", ") { it.name.lowercase().take(3) }
                    if (days.isBlank()) "every week" else "every $days at $time"
                }
                Recurrence.MONTHLY -> "on day ${task.dayOfMonth ?: 1} of each month at $time"
            }
        }
    }
}

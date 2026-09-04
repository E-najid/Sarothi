package com.ngi.sarothi.plugins.productivity

import android.content.Intent
import com.google.gson.JsonObject
import com.ngi.sarothi.core.plugin.JsonSchema
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.core.plugin.PluginAvailability
import com.ngi.sarothi.core.plugin.PluginCategory
import com.ngi.sarothi.core.plugin.PluginContext
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.plugin.pluginContext
import com.ngi.sarothi.core.plugin.ConfirmationPreview
import com.ngi.sarothi.core.safety.ConfirmationReason
import com.ngi.sarothi.core.schedule.NotificationRule
import com.ngi.sarothi.core.schedule.Recurrence
import com.ngi.sarothi.core.schedule.RuleMatch
import com.ngi.sarothi.core.schedule.ScheduledTask
import com.ngi.sarothi.core.schedule.ScheduleReceiver
import com.ngi.sarothi.core.schedule.ScheduleService
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import com.ngi.sarothi.plugins.common.Digits
import com.ngi.sarothi.plugins.common.UndoToken
import com.ngi.sarothi.plugins.common.textOrAsk
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SCHEDULE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")

private fun whenText(epochMillis: Long?): String = epochMillis?.let {
    SCHEDULE_STAMP.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()))
} ?: "not scheduled"

private fun recurrenceText(recurrence: Recurrence): String = recurrence.displayName.lowercase()

/** Reads a time of day from the ways a user or a model actually writes one. */
internal fun parseTimeOnly(text: String): LocalTime? {
    val normalised = Digits.toWestern(text.trim()).replace('.', ':')
    val amPm = Regex("""^(\d{1,2}):(\d{2})\s*(am|pm)$""", RegexOption.IGNORE_CASE).find(normalised)
    if (amPm != null) {
        var hour = amPm.groupValues[1].toIntOrNull() ?: return null
        val minute = amPm.groupValues[2].toIntOrNull() ?: return null
        val isPm = amPm.groupValues[3].lowercase().startsWith("p")
        when {
            hour == 12 -> hour = if (isPm) 12 else 0
            isPm -> hour += 12
        }
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }
    for (pattern in listOf("HH:mm", "H:mm", "HH:mm:ss")) {
        val parsed = runCatching { LocalTime.parse(normalised, DateTimeFormatter.ofPattern(pattern)) }.getOrNull()
        if (parsed != null) return parsed
    }
    return null
}

private fun weekdayOf(name: String): DayOfWeek? = when (name.trim().uppercase()) {
    "MONDAY", "MON", "সোমবার" -> DayOfWeek.MONDAY
    "TUESDAY", "TUE", "TUES", "মঙ্গলবার" -> DayOfWeek.TUESDAY
    "WEDNESDAY", "WED", "বুধবার" -> DayOfWeek.WEDNESDAY
    "THURSDAY", "THU", "THURS", "বৃহস্পতিবার" -> DayOfWeek.THURSDAY
    "FRIDAY", "FRI", "শুক্রবার" -> DayOfWeek.FRIDAY
    "SATURDAY", "SAT", "শনিবার" -> DayOfWeek.SATURDAY
    "SUNDAY", "SUN", "রবিবার" -> DayOfWeek.SUNDAY
    else -> null
}

/** Creates a task Sarothi will run by itself later. */
class ScheduleTaskPlugin : Plugin {
    override val name = "schedule_task"
    override val description =
        "Save a task for Sarothi to run later on its own — 'every morning at 7 tell me the weather', " +
            "'on the 1st of each month remind me about the rent'. Scheduled runs are unattended: nobody " +
            "is there to confirm anything, so steps that send a message, spend money or delete " +
            "something are refused at run time and reported, unless allow_sensitive_steps is set. The " +
            "vault also has to be unlocked when it runs, or the task is postponed with a notification."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.NORMAL
    override val supportsUndo = true

    override val parameters = JsonSchema(
        properties = mapOf(
            "request" to JsonSchema.Property.Text("What Sarothi should do, as if the user had typed it now."),
            "recurrence" to JsonSchema.Property.Text(
                "How often it repeats.",
                enum = listOf("once", "hourly", "daily", "weekly", "monthly"),
                default = "once",
            ),
            "at_time" to JsonSchema.Property.Text("Time of day, HH:mm (24-hour) or '7:15 pm'. Needed for daily, weekly and monthly."),
            "days" to JsonSchema.Property.List("For weekly: MONDAY … SUNDAY. More than one is allowed.", items = JsonSchema.Property.Text("One weekday")),
            "day_of_month" to JsonSchema.Property.Integer("For monthly: 1-31. A month without that day uses its last day.", minimum = 1, maximum = 31),
            "run_at" to JsonSchema.Property.Text("For once: an absolute date/time, YYYY-MM-DD HH:mm. Overrides at_time."),
            "title" to JsonSchema.Property.Text("Short label for the schedule list. Defaults to the start of the request."),
            "allow_sensitive_steps" to JsonSchema.Property.Flag(
                "Let the unattended run attempt sensitive steps. They are still refused at run time " +
                    "unless the user is present; setting this only stops the task being rejected for containing them.",
                default = false,
            ),
        ),
        required = listOf("request"),
    )

    override val example = """{"request":"Tell me today's weather in Dhaka","recurrence":"daily","at_time":"07:00"}"""

    override suspend fun availability(context: PluginContext): PluginAvailability {
        if (!context.vault.isUnlocked) {
            return PluginAvailability.unavailable(
                reason = "The schedule is stored in the encrypted vault, which is locked.",
                fixAction = "Ask the user to unlock Sarothi's vault.",
            )
        }
        // Ready even without the exact-alarm permission: the task can still be
        // scheduled, only approximately. The imprecision is reported when the
        // schedule is created and stored on the task, not hidden here.
        return PluginAvailability.READY
    }

    override fun describeForConfirmation(params: JsonObject): ConfirmationPreview {
        val request = params.stringOrNull("request") ?: "(no request given)"
        val recurrence = params.stringOrNull("recurrence") ?: "once"
        val allowSensitive = params.get("allow_sensitive_steps")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        return ConfirmationPreview(
            title = "Let Sarothi run this by itself?",
            detailLines = buildList {
                add("Task: $request")
                add("Repeats: $recurrence")
                add(
                    "Scheduled tasks run without you watching, and Sarothi will stop and ask before " +
                        "anything that sends a message, spends money or deletes something.",
                )
                if (allowSensitive) {
                    add("This one is allowed to attempt sensitive steps, which will be reported rather than confirmed.")
                }
            },
            reason = ConfirmationReason.UNATTENDED_ACTION,
            allowRemember = false,
        )
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val request = params.textOrAsk("request", "What exactly should Sarothi do at that time?")
        val recurrenceName = (params.stringOrNull("recurrence") ?: "once").lowercase()
        val recurrence = when (recurrenceName) {
            "once", "one-off", "single" -> Recurrence.ONCE
            "hourly", "every hour" -> Recurrence.HOURLY
            "daily", "everyday", "every day" -> Recurrence.DAILY
            "weekly", "every week" -> Recurrence.WEEKLY
            "monthly", "every month" -> Recurrence.MONTHLY
            else -> return PluginResult.Failure(
                summaryForUser = "\"$recurrenceName\" is not a recurrence Sarothi understands. Use once, " +
                    "hourly, daily, weekly or monthly.",
                errorClass = "UnknownRecurrenceException",
                retriable = true,
            )
        }
        val title = params.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: request.take(48)
        val atTimeText = params.stringOrNull("at_time")?.takeIf { it.isNotBlank() }
        val runAtText = params.stringOrNull("run_at")?.takeIf { it.isNotBlank() }
        val dayNames = params.getAsJsonArray("days")?.mapNotNull {
            if (it.isJsonPrimitive) it.asString else null
        } ?: emptyList()
        val dayOfMonth = params.get("day_of_month")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, 31)
        val allowSensitive = params.get("allow_sensitive_steps")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

        val atTime = atTimeText?.let { text ->
            parseTimeOnly(text) ?: return PluginResult.Failure(
                summaryForUser = "\"$text\" is not a time Sarothi can read. Use HH:mm (24-hour) or '7:15 pm'.",
                errorClass = "TimeParseException",
                retriable = true,
            )
        }

        val daysOfWeek = if (recurrence == Recurrence.WEEKLY) {
            if (dayNames.isEmpty()) {
                throw com.ngi.sarothi.core.error.MissingInformationException(
                    field = "days",
                    questionForUser = "Which day(s) of the week should this run?",
                    choices = DayOfWeek.entries.map { it.name },
                )
            }
            val parsed = dayNames.mapNotNull { weekdayOf(it) }.toSet()
            if (parsed.isEmpty()) {
                return PluginResult.Failure(
                    summaryForUser = "None of ${dayNames.joinToString()} is a weekday Sarothi recognises. " +
                        "Use MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY or SUNDAY.",
                    errorClass = "UnknownWeekdayException",
                    retriable = true,
                )
            }
            parsed
        } else {
            emptySet()
        }

        val oneShotAt = when (recurrence) {
            Recurrence.ONCE -> {
                val text = runAtText ?: atTimeText?.let { time ->
                    // A bare time means today if it is still ahead, otherwise tomorrow.
                    val parsed = parseTimeOnly(time)
                        ?: return PluginResult.Failure(
                            "\"$time\" is not a time Sarothi can read. Use HH:mm, or give a full date " +
                                "in run_at as YYYY-MM-DD HH:mm.",
                            "TimeParseException",
                            retriable = true,
                        )
                    val zone = ZoneId.systemDefault()
                    val now = LocalDateTime.now(zone)
                    val candidate = LocalDate.now(zone).atTime(parsed)
                    val target = if (candidate.isAfter(now)) candidate else candidate.plusDays(1)
                    return@let target.atZone(zone).toInstant().toEpochMilli().toString()
                }
                if (text == null) {
                    throw com.ngi.sarothi.core.error.MissingInformationException(
                        field = "run_at",
                        questionForUser = "When exactly should Sarothi run this once? Give a date and " +
                            "time, for example 2026-09-10 18:00.",
                    )
                }
                parseLocalDateTime(text) ?: return PluginResult.Failure(
                    summaryForUser = "\"$text\" is not a date Sarothi can read. Use YYYY-MM-DD HH:mm.",
                    errorClass = "DateTimeParseException",
                    retriable = true,
                )
            }
            else -> null
        }
        if (oneShotAt != null && oneShotAt <= System.currentTimeMillis()) {
            return PluginResult.Failure(
                summaryForUser = "That time (${whenText(oneShotAt)}) has already passed, so a one-off " +
                    "task would never run. Pick a time in the future.",
                errorClass = "PastScheduleException",
                retriable = true,
            )
        }

        val result = runCatching {
            context.scheduler.create(
                title = title,
                request = request,
                recurrence = recurrence,
                timeOfDay = atTime,
                daysOfWeek = daysOfWeek,
                dayOfMonth = dayOfMonth,
                oneShotAtEpochMillis = oneShotAt,
                allowSensitiveSteps = allowSensitive,
            )
        }.getOrElse { failure ->
            return PluginResult.Failure(
                summaryForUser = "Sarothi could not save the schedule: ${failure.message}",
                errorClass = failure.javaClass.simpleName,
                retriable = true,
            )
        }
        val task = result.task

        if (result.armedForEpochMillis == null) {
            return PluginResult.Failure(
                summaryForUser = "The schedule \"${task.title}\" was saved but Sarothi could not work " +
                    "out when it should next run, so it will never fire. ${result.warning ?: ""}".trim(),
                errorClass = "UnschedulableException",
                retriable = true,
                data = Json.obj { addProperty("id", task.id); addProperty("saved", true) },
            )
        }

        return PluginResult.Success(
            summaryForUser = "Scheduled \"${task.title}\" ${recurrenceText(recurrence)}, next run " +
                whenText(result.armedForEpochMillis) +
                (result.warning?.let { ". $it" } ?: "") +
                if (task.alarmIsApproximate) {
                    " Android will not let Sarothi set an exact alarm, so it may be a few minutes late."
                } else "",
            data = Json.obj {
                addProperty("id", task.id)
                addProperty("title", task.title)
                addProperty("request", task.request)
                addProperty("recurrence", recurrence.name.lowercase())
                addProperty("next_run", result.armedForEpochMillis)
                addProperty("next_run_text", whenText(result.armedForEpochMillis))
                addProperty("alarm_exact", !task.alarmIsApproximate)
                addProperty("allow_sensitive_steps", task.allowSensitiveSteps)
                result.warning?.let { addProperty("warning", it) }
            },
            spoken = "সময় ঠিক করে রেখেছি।",
            undoToken = task.id,
            memorable = listOf("scheduled ${recurrenceText(recurrence)}: ${task.title} from ${whenText(result.armedForEpochMillis)}"),
        )
    }

    override suspend fun undo(undoToken: String): PluginResult {
        val context = pluginContext()
        return if (context.scheduler.delete(undoToken)) {
            PluginResult.Success("Cancelled that scheduled task again.", Json.obj { addProperty("deleted", undoToken) })
        } else {
            PluginResult.Failure("That scheduled task is already gone.", "NotFoundException", retriable = false)
        }
    }
}

/** Lists what Sarothi is set to do by itself. */
class ListSchedulesPlugin : Plugin {
    override val name = "list_schedules"
    override val description =
        "List the tasks Sarothi runs by itself: next run time, how often it repeats, whether the alarm " +
            "is exact, and what happened the last time it ran."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "include_disabled" to JsonSchema.Property.Flag("Also show paused tasks.", default = false),
        ),
    )

    override suspend fun availability(context: PluginContext): PluginAvailability =
        if (context.vault.isUnlocked) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                reason = "The schedule is encrypted and the vault is locked.",
                fixAction = "Ask the user to unlock Sarothi's vault.",
            )
        }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val includeDisabled = params.get("include_disabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val tasks = context.scheduler.tasks().filter { includeDisabled || it.enabled }
        val now = System.currentTimeMillis()

        val data = Json.obj {
            add("tasks", Json.arr {
                tasks.forEach { task ->
                    add(Json.obj {
                        addProperty("id", task.id)
                        addProperty("title", task.title)
                        addProperty("request", task.request)
                        addProperty("recurrence", task.recurrence.name.lowercase())
                        addProperty("next_run_text", whenText(task.nextRunAtEpochMillis))
                        task.nextRunAtEpochMillis?.let { addProperty("next_run", it) }
                        addProperty("enabled", task.enabled)
                        addProperty("alarm_exact", !task.alarmIsApproximate)
                        addProperty("allow_sensitive_steps", task.allowSensitiveSteps)
                        addProperty("run_count", task.runCount)
                        addProperty("last_run_status", task.lastRunStatus ?: "never run")
                        addProperty("last_run_text", whenText(task.lastRunAtEpochMillis))
                        task.lastRunMessage?.let { addProperty("last_run_message", it) }
                        // Same cross-module smart-cast problem as in NotesAndTodosPlugins:
                        // the property has to be read into a local before the null check.
                        val nextRun = task.nextRunAtEpochMillis
                        addProperty("overdue", nextRun != null && nextRun < now)
                    })
                }
            })
            addProperty("count", tasks.size)
        }
        val next = tasks.filter { it.nextRunAtEpochMillis != null }.minByOrNull { it.nextRunAtEpochMillis!! }
        return PluginResult.Success(
            summaryForUser = when {
                tasks.isEmpty() -> "Sarothi has nothing scheduled."
                next == null -> "${tasks.size} scheduled task(s), none of which has a next run time."
                else -> "${tasks.size} scheduled task(s); next is \"${next.title}\" at ${whenText(next.nextRunAtEpochMillis)}"
            },
            data = data,
        )
    }
}

/** Pauses, resumes or cancels a scheduled task. */
class CancelSchedulePlugin : Plugin {
    override val name = "cancel_schedule"
    override val description =
        "Pause or delete one of Sarothi's scheduled tasks, by id from list_schedules or by part of its " +
            "title. Always confirms first: cancelling a reminder means the user quietly stops being " +
            "reminded, which is exactly the kind of change worth a second look."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.SENSITIVE
    override val supportsUndo = true

    override val parameters = JsonSchema(
        properties = mapOf(
            "task_id" to JsonSchema.Property.Text("The id from list_schedules."),
            "title_contains" to JsonSchema.Property.Text("Part of the title, when the id is unknown."),
            "pause_only" to JsonSchema.Property.Flag("Disable it but keep it, so it can be resumed.", default = false),
        ),
    )

    override fun describeForConfirmation(params: JsonObject): ConfirmationPreview {
        val target = params.stringOrNull("task_id") ?: params.stringOrNull("title_contains") ?: "(unspecified)"
        val pauseOnly = params.get("pause_only")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        return ConfirmationPreview(
            title = if (pauseOnly) "Pause this scheduled task?" else "Delete this scheduled task?",
            detailLines = listOf(
                "Task: $target",
                if (pauseOnly) "It stays saved but stops running, and can be resumed later."
                else "It is removed from the schedule and stops running.",
            ),
            reason = ConfirmationReason.DELETION,
            allowRemember = false,
        )
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val pauseOnly = params.get("pause_only")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val id = params.stringOrNull("task_id")?.takeIf { it.isNotBlank() }
        val titlePart = params.stringOrNull("title_contains")?.takeIf { it.isNotBlank() }

        val all = context.scheduler.tasks()
        val task = when {
            id != null -> all.firstOrNull { it.id == id }
            titlePart != null -> {
                val matches = all.filter {
                    it.title.contains(titlePart, ignoreCase = true) || it.request.contains(titlePart, ignoreCase = true)
                }
                when {
                    matches.isEmpty() -> return PluginResult.Failure(
                        summaryForUser = "No scheduled task matches \"$titlePart\".",
                        errorClass = "NotFoundException",
                        retriable = true,
                    )
                    matches.size == 1 -> matches.first()
                    else -> return PluginResult.NeedsUserInput(
                        question = "${matches.size} scheduled tasks match \"$titlePart\". Which one?",
                        field = "task_id",
                        choices = matches.take(6).map { "${it.id}: ${it.title} (${whenText(it.nextRunAtEpochMillis)})" },
                    )
                }
            }
            else -> throw com.ngi.sarothi.core.error.MissingInformationException(
                field = "task_id",
                questionForUser = "Which scheduled task should Sarothi ${if (pauseOnly) "pause" else "delete"}?",
            )
        } ?: return PluginResult.Failure(
            summaryForUser = "That scheduled task no longer exists.",
            errorClass = "NotFoundException",
            retriable = false,
        )

        if (pauseOnly) {
            if (!task.enabled) {
                return PluginResult.Success(
                    "\"${task.title}\" is already paused.",
                    Json.obj { addProperty("id", task.id); addProperty("enabled", false) },
                )
            }
            val changed = context.scheduler.setEnabled(task.id, false)
            return if (changed) {
                PluginResult.Success(
                    summaryForUser = "Paused \"${task.title}\". It is still saved and can be resumed.",
                    data = Json.obj { addProperty("id", task.id); addProperty("enabled", false) },
                    spoken = "থামিয়ে রেখেছি।",
                    undoToken = "resume:${task.id}",
                )
            } else {
                PluginResult.Failure("That task disappeared before Sarothi could pause it.", "NotFoundException", retriable = false)
            }
        }

        val deleted = context.scheduler.delete(task.id)
        return if (deleted) {
            PluginResult.Success(
                summaryForUser = "Deleted \"${task.title}\" (it was next due ${whenText(task.nextRunAtEpochMillis)}).",
                data = Json.obj {
                    addProperty("id", task.id)
                    addProperty("title", task.title)
                    addProperty("request", task.request)
                    addProperty("recurrence", task.recurrence.name.lowercase())
                    addProperty("next_run", task.nextRunAtEpochMillis ?: -1L)
                },
                spoken = "মুছে দিয়েছি।",
                // The row is gone from the vault, so everything needed to rebuild
                // it travels inside the token: UndoRegistry hands back one opaque
                // string, possibly after a process restart.
                undoToken = UndoToken.encode(
                    "schedule_restore",
                    Json.obj {
                        addProperty("id", task.id)
                        addProperty("title", task.title)
                        addProperty("request", task.request)
                        addProperty("recurrence", task.recurrence.name.lowercase())
                        task.timeOfDay?.let { addProperty("time_of_day", it.toString()) }
                        add("days", Json.arr { task.daysOfWeek.forEach { add(it.name.lowercase()) } })
                        task.dayOfMonth?.let { addProperty("day_of_month", it) }
                        task.oneShotAtEpochMillis?.let { addProperty("one_shot_at", it) }
                        addProperty("allow_sensitive_steps", task.allowSensitiveSteps)
                    },
                ),
            )
        } else {
            PluginResult.Failure("That task was already gone.", "NotFoundException", retriable = false)
        }
    }

    override suspend fun undo(undoToken: String): PluginResult {
        val context = pluginContext()
        if (undoToken.startsWith("resume:")) {
            val id = undoToken.removePrefix("resume:")
            return if (context.scheduler.setEnabled(id, true)) {
                PluginResult.Success("Resumed that scheduled task.", Json.obj { addProperty("id", id); addProperty("enabled", true) })
            } else {
                PluginResult.Failure("That scheduled task no longer exists.", "NotFoundException", retriable = false)
            }
        }
        val snapshot = UndoToken.decode(undoToken, "schedule_restore")
            ?: return PluginResult.Failure(
                summaryForUser = "That undo token is not one Sarothi issued for a schedule, so there " +
                    "is nothing it can put back.",
                errorClass = "BadUndoTokenException",
                retriable = false,
            )
        val request = snapshot.get("request")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return PluginResult.Failure("The saved copy has no request; cannot restore.", "UndoUnavailableException", retriable = false)
        val title = snapshot.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: request.take(48)
        val recurrence = snapshot.get("recurrence")?.takeIf { it.isJsonPrimitive }?.asString
            ?.let { Recurrence.fromJson(it) } ?: Recurrence.ONCE
        val timeOfDay = snapshot.get("time_of_day")?.takeIf { it.isJsonPrimitive }?.asString
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        val days = snapshot.getAsJsonArray("days")?.mapNotNull { element ->
            if (!element.isJsonPrimitive) return@mapNotNull null
            runCatching { DayOfWeek.valueOf(element.asString.uppercase()) }.getOrNull()
        }?.toSet() ?: emptySet()
        val dayOfMonth = snapshot.get("day_of_month")?.takeIf { it.isJsonPrimitive }?.asInt
        val oneShotAt = snapshot.get("one_shot_at")?.takeIf { it.isJsonPrimitive }?.asLong
        val allowSensitive = snapshot.get("allow_sensitive_steps")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

        val restored = runCatching {
            context.scheduler.create(
                title = title,
                request = request,
                recurrence = recurrence,
                timeOfDay = timeOfDay,
                daysOfWeek = days,
                dayOfMonth = dayOfMonth,
                oneShotAtEpochMillis = oneShotAt,
                allowSensitiveSteps = allowSensitive,
            )
        }.getOrElse { failure ->
            return PluginResult.Failure(
                summaryForUser = "Sarothi could not put the schedule back: ${failure.message}",
                errorClass = failure.javaClass.simpleName,
                retriable = false,
            )
        }
        return PluginResult.Success(
            summaryForUser = "Put \"${restored.task.title}\" back on the schedule" +
                " (with a new id, ${restored.task.id}), next run ${whenText(restored.armedForEpochMillis)}.",
            data = Json.obj { addProperty("id", restored.task.id); addProperty("title", restored.task.title) },
        )
    }
}

/** Asks Sarothi to run one of its scheduled tasks immediately. */
class RunScheduleNowPlugin : Plugin {
    override val name = "run_schedule_now"
    override val description =
        "Run one of Sarothi's scheduled tasks right away instead of waiting for its time. Use it when " +
            "the user says 'do the morning one now'. The task is handed to Sarothi's scheduler service, " +
            "so it starts once the current task finishes and its result arrives as a notification."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "task_id" to JsonSchema.Property.Text("The id from list_schedules."),
        ),
        required = listOf("task_id"),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val id = params.textOrAsk("task_id", "Which scheduled task should run now? Use list_schedules to see the ids.")
        val task = context.scheduler.tasks().firstOrNull { it.id == id }
            ?: return PluginResult.Failure(
                summaryForUser = "There is no scheduled task with id \"$id\".",
                errorClass = "NotFoundException",
                retriable = true,
            )
        if (!task.enabled) {
            return PluginResult.Failure(
                summaryForUser = "\"${task.title}\" is paused, so Sarothi will not run it. Resume it first.",
                errorClass = "TaskDisabledException",
                retriable = true,
            )
        }

        // Same path an alarm takes: hand it to ScheduleService rather than running
        // the agent from inside a task, which would deadlock on the agent's single-
        // task mutex. The service waits for the current task to finish and reports
        // the outcome as a notification.
        val intent = Intent(context.appContext, ScheduleService::class.java)
            .setAction(ScheduleReceiver.ACTION_RUN_TASK)
            .putExtra(ScheduleReceiver.EXTRA_TASK_ID, task.id)
            .putExtra(ScheduleReceiver.EXTRA_REASON, "requested by the user during another task")
        val started = runCatching {
            // startForegroundService is API 26 and minSdk is 26.
            context.appContext.startForegroundService(intent)
        }
        return started.fold(
            onSuccess = {
                PluginResult.Success(
                    summaryForUser = "Sarothi will run \"${task.title}\" as soon as this task finishes; " +
                        "you will get a notification with the result.",
                    data = Json.obj {
                        addProperty("id", task.id)
                        addProperty("title", task.title)
                        addProperty("request", task.request)
                        addProperty("queued", true)
                        addProperty("allow_sensitive_steps", task.allowSensitiveSteps)
                    },
                    spoken = "কাজটি চালানোর জন্য পাঠিয়ে দিয়েছি।",
                )
            },
            onFailure = { failure ->
                PluginResult.Failure(
                    summaryForUser = "Sarothi could not hand the task to its scheduler service: " +
                        "${failure.javaClass.simpleName}: ${failure.message}",
                    errorClass = failure.javaClass.simpleName,
                    retriable = true,
                )
            },
        )
    }
}

/** Adds a rule that fires when a matching notification arrives. */
class AddNotificationRulePlugin : Plugin {
    override val name = "add_notification_rule"
    override val description =
        "Make Sarothi react when a notification arrives — 'when bKash says money arrived, read it out', " +
            "'when WhatsApp messages from Ammu, tell me'. Matching is plain text on the app, title and " +
            "body, so it works with the screen off and costs no model call. Rules run unattended and " +
            "never perform a step that needs confirmation."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.NORMAL
    override val supportsUndo = true

    override val parameters = JsonSchema(
        properties = mapOf(
            "name" to JsonSchema.Property.Text("Short label for the rule."),
            "request" to JsonSchema.Property.Text("What Sarothi should do when it matches, as if the user had typed it."),
            "packages" to JsonSchema.Property.List("Only these apps, e.g. com.whatsapp. Empty means any app.", items = JsonSchema.Property.Text("One package name")),
            "title_contains" to JsonSchema.Property.List("Match if the title contains any of these.", items = JsonSchema.Property.Text("One phrase")),
            "body_contains" to JsonSchema.Property.List("Match if the message body contains any of these.", items = JsonSchema.Property.Text("One phrase")),
            "match" to JsonSchema.Property.Text("Require every phrase, or any one of them.", enum = listOf("any", "all"), default = "any"),
            "case_sensitive" to JsonSchema.Property.Flag("Match capitalisation exactly.", default = false),
            "cooldown_minutes" to JsonSchema.Property.Integer("Minimum minutes between two firings, so a chatty app cannot loop the agent.", minimum = 0, maximum = 1440, default = 5),
        ),
        required = listOf("name", "request"),
    )

    override val example =
        """{"name":"Ammu's WhatsApp","packages":["com.whatsapp"],"title_contains":["Ammu"],"request":"Read the message aloud and tell me who it is from"}"""

    override suspend fun availability(context: PluginContext): PluginAvailability {
        if (!context.vault.isUnlocked) {
            return PluginAvailability.unavailable(
                reason = "Rules are stored in the encrypted vault, which is locked.",
                fixAction = "Ask the user to unlock Sarothi's vault.",
            )
        }
        val state = context.screen.availability()
        return if (state.accessibilityConnected) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                reason = "Notifications reach Sarothi through its accessibility service, which is not " +
                    "connected, so a rule would never fire.",
                fixAction = "Turn Sarothi on in Settings → Accessibility first.",
            )
        }
    }

    override fun describeForConfirmation(params: JsonObject): ConfirmationPreview {
        val name = params.stringOrNull("name") ?: "(unnamed rule)"
        val request = params.stringOrNull("request") ?: "(no action given)"
        val packages = params.getAsJsonArray("packages")?.mapNotNull {
            if (it.isJsonPrimitive) it.asString else null
        } ?: emptyList()
        return ConfirmationPreview(
            title = "Let Sarothi act on notifications by itself?",
            detailLines = listOf(
                "Rule: $name",
                "Watches: " + if (packages.isEmpty()) "every app" else packages.joinToString(),
                "Will do: $request",
                "This runs whenever a matching notification arrives, without you asking. Sarothi will " +
                    "not send a message, spend money or delete anything on its own initiative.",
            ),
            reason = ConfirmationReason.UNATTENDED_ACTION,
            allowRemember = false,
        )
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val ruleName = params.textOrAsk("name", "What should this rule be called?")
        val request = params.textOrAsk("request", "What should Sarothi do when a matching notification arrives?")
        val packages = params.getAsJsonArray("packages")?.mapNotNull {
            if (it.isJsonPrimitive) it.asString else null
        } ?: emptyList()
        val titleContains = params.getAsJsonArray("title_contains")?.mapNotNull {
            if (it.isJsonPrimitive) it.asString else null
        } ?: emptyList()
        val bodyContains = params.getAsJsonArray("body_contains")?.mapNotNull {
            if (it.isJsonPrimitive) it.asString else null
        } ?: emptyList()
        val matchAll = (params.stringOrNull("match") ?: "any").lowercase() == "all"
        val caseSensitive = params.get("case_sensitive")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val cooldownMinutes = params.get("cooldown_minutes")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(0, 1440)
            ?: (NotificationRule.DEFAULT_COOLDOWN_MILLIS / 60_000L).toInt()

        if (packages.isEmpty() && titleContains.isEmpty() && bodyContains.isEmpty()) {
            return PluginResult.Failure(
                summaryForUser = "A rule with no app and no words to match would fire on every " +
                    "notification from every app. Give it at least one condition.",
                errorClass = "RuleTooBroadException",
                retriable = true,
            )
        }

        val rule = runCatching {
            context.scheduler.createRule(
                name = ruleName,
                request = request,
                packageNames = packages,
                titleContains = titleContains,
                bodyContains = bodyContains,
                match = if (matchAll) RuleMatch.ALL else RuleMatch.ANY,
                caseSensitive = caseSensitive,
                cooldownMillis = cooldownMinutes * 60_000L,
            )
        }.getOrElse { failure ->
            return PluginResult.Failure(
                summaryForUser = "Sarothi could not save the rule: ${failure.message}",
                errorClass = failure.javaClass.simpleName,
                retriable = true,
            )
        }

        return PluginResult.Success(
            summaryForUser = "Added the rule \"${rule.name}\". It will ${request.lowercase()} when a " +
                "notification matches ${rule.describeConditions()}.",
            data = Json.obj {
                addProperty("id", rule.id)
                addProperty("name", rule.name)
                addProperty("request", rule.request)
                addProperty("conditions", rule.describeConditions())
                add("packages", Json.arr { rule.packageNames.forEach { add(it) } })
                add("title_contains", Json.arr { rule.titleContains.forEach { add(it) } })
                add("body_contains", Json.arr { rule.bodyContains.forEach { add(it) } })
                addProperty("match", rule.match.name.lowercase())
                addProperty("cooldown_minutes", rule.cooldownMillis / 60_000L)
            },
            spoken = "নিয়মটি যোগ করে দিয়েছি।",
            undoToken = rule.id,
        )
    }

    override suspend fun undo(undoToken: String): PluginResult {
        val context = pluginContext()
        return if (context.scheduler.deleteRule(undoToken)) {
            PluginResult.Success("Removed that rule again.", Json.obj { addProperty("deleted", undoToken) })
        } else {
            PluginResult.Failure("That rule is already gone.", "NotFoundException", retriable = false)
        }
    }
}

/** Lists the notification rules Sarothi watches for. */
class ListNotificationRulesPlugin : Plugin {
    override val name = "list_notification_rules"
    override val description =
        "List the notification rules Sarothi is watching for, what each does, its conditions, and how " +
            "many times it has fired."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(properties = emptyMap())

    override suspend fun availability(context: PluginContext): PluginAvailability =
        if (context.vault.isUnlocked) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                reason = "Rules are encrypted and the vault is locked.",
                fixAction = "Ask the user to unlock Sarothi's vault.",
            )
        }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val rules = context.scheduler.rules()
        val accessibility = context.screen.availability().accessibilityConnected

        val data = Json.obj {
            add("rules", Json.arr {
                rules.forEach { rule ->
                    add(Json.obj {
                        addProperty("id", rule.id)
                        addProperty("name", rule.name)
                        addProperty("request", rule.request)
                        addProperty("conditions", rule.describeConditions())
                        addProperty("enabled", rule.enabled)
                        addProperty("fire_count", rule.fireCount)
                        addProperty("last_fired_text", whenText(rule.lastFiredAtEpochMillis))
                        rule.lastResult?.let { addProperty("last_result", it) }
                    })
                }
            })
            addProperty("count", rules.size)
            addProperty("accessibility_connected", accessibility)
        }
        return PluginResult.Success(
            summaryForUser = when {
                rules.isEmpty() -> "Sarothi is not watching for any notifications."
                !accessibility -> "${rules.size} rule(s) are saved but Sarothi's accessibility service is " +
                    "off, so none of them can fire."
                else -> "${rules.size} rule(s): " + rules.take(4).joinToString("; ") { "${it.name} (${it.fireCount} fired)" }
            },
            data = data,
        )
    }
}

/** Deletes a notification rule. */
class DeleteNotificationRulePlugin : Plugin {
    override val name = "delete_notification_rule"
    override val description =
        "Delete a notification rule, by id from list_notification_rules or by part of its name. If more " +
            "than one rule matches, Sarothi asks which instead of guessing."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.SENSITIVE
    override val supportsUndo = true

    override val parameters = JsonSchema(
        properties = mapOf(
            "rule_id" to JsonSchema.Property.Text("The id from list_notification_rules."),
            "name_contains" to JsonSchema.Property.Text("Part of the rule's name, when the id is unknown."),
        ),
    )

    override fun describeForConfirmation(params: JsonObject): ConfirmationPreview = ConfirmationPreview(
        title = "Delete this notification rule?",
        detailLines = listOf(
            "Rule: " + (params.stringOrNull("rule_id") ?: params.stringOrNull("name_contains") ?: "(unspecified)"),
            "Sarothi will stop reacting to those notifications.",
        ),
        reason = ConfirmationReason.DELETION,
        allowRemember = false,
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val id = params.stringOrNull("rule_id")?.takeIf { it.isNotBlank() }
        val namePart = params.stringOrNull("name_contains")?.takeIf { it.isNotBlank() }
        val all = context.scheduler.rules()

        val rule = when {
            id != null -> all.firstOrNull { it.id == id }
            namePart != null -> {
                val matches = all.filter { it.name.contains(namePart, ignoreCase = true) }
                when {
                    matches.isEmpty() -> return PluginResult.Failure(
                        summaryForUser = "No rule matches \"$namePart\".",
                        errorClass = "NotFoundException",
                        retriable = true,
                    )
                    matches.size == 1 -> matches.first()
                    else -> return PluginResult.NeedsUserInput(
                        question = "${matches.size} rules match \"$namePart\". Which one?",
                        field = "rule_id",
                        choices = matches.take(6).map { "${it.id}: ${it.name}" },
                    )
                }
            }
            else -> throw com.ngi.sarothi.core.error.MissingInformationException(
                field = "rule_id",
                questionForUser = "Which notification rule should Sarothi delete?",
            )
        } ?: return PluginResult.Failure(
            summaryForUser = "That rule no longer exists.",
            errorClass = "NotFoundException",
            retriable = false,
        )

        val deleted = context.scheduler.deleteRule(rule.id)
        return if (deleted) {
            PluginResult.Success(
                summaryForUser = "Deleted the rule \"${rule.name}\" (${rule.describeConditions()}).",
                data = Json.obj {
                    addProperty("id", rule.id)
                    addProperty("name", rule.name)
                    addProperty("request", rule.request)
                    addProperty("conditions", rule.describeConditions())
                },
                spoken = "নিয়মটি মুছে দিয়েছি।",
                undoToken = UndoToken.encode(
                    "rule_restore",
                    Json.obj {
                        addProperty("id", rule.id)
                        addProperty("name", rule.name)
                        addProperty("request", rule.request)
                        add("packages", Json.arr { rule.packageNames.forEach { add(it) } })
                        add("title_contains", Json.arr { rule.titleContains.forEach { add(it) } })
                        add("body_contains", Json.arr { rule.bodyContains.forEach { add(it) } })
                        addProperty("match", rule.match.name.lowercase())
                        addProperty("case_sensitive", rule.caseSensitive)
                        addProperty("cooldown_millis", rule.cooldownMillis)
                    },
                ),
            )
        } else {
            PluginResult.Failure("That rule was already gone.", "NotFoundException", retriable = false)
        }
    }

    override suspend fun undo(undoToken: String): PluginResult {
        val context = pluginContext()
        val snapshot = UndoToken.decode(undoToken, "rule_restore")
            ?: return PluginResult.Failure(
                summaryForUser = "That undo token is not one Sarothi issued for a notification rule, " +
                    "so there is nothing it can put back.",
                errorClass = "BadUndoTokenException",
                retriable = false,
            )
        val request = snapshot.get("request")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return PluginResult.Failure("The saved copy has no action; cannot restore.", "UndoUnavailableException", retriable = false)
        val ruleName = snapshot.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: request.take(40)

        val restored = runCatching {
            context.scheduler.createRule(
                name = ruleName,
                request = request,
                packageNames = snapshot.getAsJsonArray("packages")?.mapNotNull {
                    if (it.isJsonPrimitive) it.asString else null
                } ?: emptyList(),
                titleContains = snapshot.getAsJsonArray("title_contains")?.mapNotNull {
                    if (it.isJsonPrimitive) it.asString else null
                } ?: emptyList(),
                bodyContains = snapshot.getAsJsonArray("body_contains")?.mapNotNull {
                    if (it.isJsonPrimitive) it.asString else null
                } ?: emptyList(),
                match = snapshot.get("match")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.let { if (it.equals("all", true)) RuleMatch.ALL else RuleMatch.ANY } ?: RuleMatch.ANY,
                caseSensitive = snapshot.get("case_sensitive")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                cooldownMillis = snapshot.get("cooldown_millis")?.takeIf { it.isJsonPrimitive }?.asLong
                    ?: NotificationRule.DEFAULT_COOLDOWN_MILLIS,
            )
        }.getOrElse { failure ->
            return PluginResult.Failure(
                summaryForUser = "Sarothi could not put the rule back: ${failure.message}",
                errorClass = failure.javaClass.simpleName,
                retriable = false,
            )
        }
        return PluginResult.Success(
            summaryForUser = "Put the rule \"${restored.name}\" back.",
            data = Json.obj { addProperty("id", restored.id); addProperty("name", restored.name) },
        )
    }
}

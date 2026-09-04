package com.ngi.sarothi.plugins.productivity

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import android.util.Log
import com.google.gson.JsonObject
import com.ngi.sarothi.core.plugin.JsonSchema
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.core.plugin.PluginAvailability
import com.ngi.sarothi.core.plugin.PluginCategory
import com.ngi.sarothi.core.plugin.PluginContext
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.plugin.pluginContext
import com.ngi.sarothi.core.safety.ConfirmationPreview
import com.ngi.sarothi.core.safety.ConfirmationReason
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import com.ngi.sarothi.plugins.common.UndoToken
import com.ngi.sarothi.plugins.common.textOrAsk
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone

private const val TAG = "SarothiCalendar"
private val EVENT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")

/** One calendar account on the device. */
internal data class CalendarAccount(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val timeZone: String,
    val isPrimary: Boolean,
)

internal fun listCalendars(context: Context): List<CalendarAccount> {
    val projection = arrayOf(
        CalendarContract.Calendars._ID,
        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        CalendarContract.Calendars.ACCOUNT_NAME,
        CalendarContract.Calendars.CALENDAR_TIME_ZONE,
        CalendarContract.Calendars.IS_PRIMARY,
        CalendarContract.Calendars.VISIBLE,
    )
    val cursor: Cursor = runCatching {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null, null,
        )
    }.getOrElse { failure ->
        Log.w(TAG, "Calendar query refused", failure)
        throw SecurityException("Android refused to read calendars: ${failure.javaClass.simpleName}.")
    } ?: return emptyList()

    return cursor.use { rows ->
        val idColumn = rows.getColumnIndex(CalendarContract.Calendars._ID)
        val nameColumn = rows.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
        val accountColumn = rows.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
        val zoneColumn = rows.getColumnIndex(CalendarContract.Calendars.CALENDAR_TIME_ZONE)
        val primaryColumn = rows.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
        if (idColumn < 0) return@use emptyList()

        val accounts = mutableListOf<CalendarAccount>()
        while (rows.moveToNext()) {
            accounts += CalendarAccount(
                id = rows.getLong(idColumn),
                displayName = if (nameColumn >= 0) rows.getString(nameColumn) ?: "Calendar" else "Calendar",
                accountName = if (accountColumn >= 0) rows.getString(accountColumn) ?: "" else "",
                timeZone = if (zoneColumn >= 0) rows.getString(zoneColumn) ?: TimeZone.getDefault().id else TimeZone.getDefault().id,
                isPrimary = primaryColumn >= 0 && rows.getInt(primaryColumn) == 1,
            )
        }
        accounts
    }
}

/** Picks the calendar to write into, asking the user when it is genuinely ambiguous. */
internal suspend fun pickCalendar(context: Context, requestedId: Long?): Long {
    val accounts = listCalendars(context)
    if (accounts.isEmpty()) {
        throw com.ngi.sarothi.core.error.MissingInformationException(
            field = "calendar_id",
            questionForUser = "This phone has no calendar accounts, so Sarothi cannot add an event. " +
                "Add an account in the Calendar app first.",
        )
    }
    if (requestedId != null) {
        if (accounts.any { it.id == requestedId }) return requestedId
        throw com.ngi.sarothi.core.error.MissingInformationException(
            field = "calendar_id",
            questionForUser = "There is no calendar with id $requestedId. The calendars on this phone " +
                "are: ${accounts.joinToString { "${it.displayName} (${it.id})" }}. Which one?",
            choices = accounts.map { "${it.displayName} (${it.accountName}) = ${it.id}" },
        )
    }
    val primary = accounts.firstOrNull { it.isPrimary } ?: accounts.singleOrNull()
    if (primary != null) return primary.id
    throw com.ngi.sarothi.core.error.MissingInformationException(
        field = "calendar_id",
        questionForUser = "This phone has ${accounts.size} calendars and none is marked primary. " +
            "Which one should the event go into?",
        choices = accounts.map { "${it.displayName} (${it.accountName}) = ${it.id}" },
    )
}

/** Adds a calendar event with a real reminder. */
class CalendarAddPlugin : Plugin {
    override val name = "calendar_add"
    override val description =
        "Add an event to the phone's calendar. Needs a title and a start time as 'YYYY-MM-DD HH:mm'. " +
            "Give end_at for a timed event, or all_day=true. A reminder is added so the user is actually " +
            "notified — an event with no reminder is a note nobody reads."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.NORMAL
    override val supportsUndo = true
    override val requiredPermissions = listOf(
        android.Manifest.permission.WRITE_CALENDAR,
        android.Manifest.permission.READ_CALENDAR,
    )

    override val parameters = JsonSchema(
        properties = mapOf(
            "title" to JsonSchema.Property.Text("Event title."),
            "start_at" to JsonSchema.Property.Text("Start, local time, YYYY-MM-DD HH:mm."),
            "end_at" to JsonSchema.Property.Text("End, same format. Required unless all_day is true."),
            "all_day" to JsonSchema.Property.Flag("Whole-day event.", default = false),
            "location" to JsonSchema.Property.Text("Where."),
            "description" to JsonSchema.Property.Text("Notes for the event."),
            "reminder_minutes" to JsonSchema.Property.Integer("Reminder this many minutes before.", minimum = 0, maximum = 10080, default = 15),
            "calendar_id" to JsonSchema.Property.Integer("Which calendar. Leave out to use the primary one.", minimum = 1),
        ),
        required = listOf("title", "start_at"),
    )

    override val example = """{"title":"ডাক্তারের অ্যাপয়েন্টমেন্ট","start_at":"2026-09-12 17:30","end_at":"2026-09-12 18:00"}"""

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val accounts = runCatching { listCalendars(context.appContext) }
        return accounts.fold(
            onSuccess = { list ->
                if (list.isEmpty()) {
                    PluginAvailability.unavailable(
                        "This phone has no calendar accounts.",
                        fixAction = "Add a calendar account in the Calendar app, then try again.",
                    )
                } else {
                    PluginAvailability.READY
                }
            },
            onFailure = { PluginAvailability.unavailable(it.message ?: "Calendars could not be read.") },
        )
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val title = params.textOrAsk("title", "What is the event called?")
        val startText = params.textOrAsk("start_at", "When does it start? Give a date and time, e.g. 2026-09-12 17:30.")
        val endText = params.stringOrNull("end_at")?.takeIf { it.isNotBlank() }
        val allDay = params.get("all_day")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val reminderMinutes = params.get("reminder_minutes")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(0, 10080) ?: 15
        val requestedCalendar = params.get("calendar_id")?.takeIf { it.isJsonPrimitive && it.asLong > 0 }?.asLong

        val start = parseLocalDateTime(startText)
            ?: return PluginResult.Failure(
                "\"$startText\" is not a date Sarothi can read. Use YYYY-MM-DD HH:mm.",
                "DateTimeParseException",
                retriable = true,
            )
        val end = endText?.let { text ->
            parseLocalDateTime(text)
                ?: return PluginResult.Failure(
                    "\"$text\" is not a date Sarothi can read. Use YYYY-MM-DD HH:mm.",
                    "DateTimeParseException",
                    retriable = true,
                )
        }
        if (end != null && end <= start) {
            return PluginResult.Failure(
                "The end time has to be after the start time.",
                "DateTimeRangeException",
                retriable = true,
            )
        }

        val zone = ZoneId.systemDefault()
        val startMillis = if (allDay) {
            // All-day events must be stored as midnight UTC of the local date.
            LocalDateTime.ofInstant(Instant.ofEpochMilli(start), zone).toLocalDate()
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            start
        }
        val endMillis = when {
            allDay -> startMillis + 86_400_000L
            end != null -> end
            else -> startMillis + 60 * 60 * 1000L // one hour is the platform default; say so
        }

        val calendarId = pickCalendar(context.appContext, requestedCalendar)
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            params.stringOrNull("description")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            params.stringOrNull("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            if (allDay) {
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_END_TIMEZONE, "UTC")
            } else {
                put(CalendarContract.Events.EVENT_END_TIMEZONE, zone.id)
            }
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.HAS_ALARM, if (reminderMinutes > 0) 1 else 0)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
        }

        val uri = runCatching {
            context.appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        }.getOrElse { failure ->
            Log.w(TAG, "Calendar insert failed", failure)
            return PluginResult.Failure(
                "Android refused to add the calendar event: ${failure.javaClass.simpleName}: ${failure.message}",
                failure.javaClass.simpleName,
                retriable = false,
            )
        }
        val eventId = uri?.lastPathSegment?.toLongOrNull()
            ?: return PluginResult.Failure(
                "The event was written but Android did not return an id, so Sarothi cannot confirm or undo it.",
                "NoEventIdException",
                retriable = false,
            )

        if (reminderMinutes > 0) {
            val reminder = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                put(CalendarContract.Reminders.MINUTES, reminderMinutes)
            }
            runCatching {
                context.appContext.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder)
            }.onFailure { failure ->
                // The event exists; only the reminder failed. Report that honestly.
                Log.w(TAG, "Reminder insert failed for event $eventId", failure)
                return PluginResult.Success(
                    summaryForUser = "Added \"$title\" to the calendar, but the reminder could not be " +
                        "added (${failure.javaClass.simpleName}), so it will not notify you.",
                    data = Json.obj {
                        addProperty("event_id", eventId)
                        addProperty("reminder_added", false)
                        addProperty("start", startMillis)
                    },
                    undoToken = UndoToken.simple("calendar_event", eventId.toString()),
                )
            }
        }

        return PluginResult.Success(
            summaryForUser = "Added \"$title\" to the calendar for " +
                "${EVENT_TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(startMillis), zone))}" +
                if (reminderMinutes > 0) ", reminding you $reminderMinutes minute(s) before" else ", with no reminder",
            data = Json.obj {
                addProperty("event_id", eventId)
                addProperty("calendar_id", calendarId)
                addProperty("title", title)
                addProperty("start", startMillis)
                addProperty("end", endMillis)
                addProperty("all_day", allDay)
                addProperty("reminder_minutes", reminderMinutes)
                addProperty("reminder_added", reminderMinutes > 0)
                addProperty("defaulted_duration", !allDay && end == null)
            },
            spoken = "ক্যালেন্ডারে যোগ করে দিয়েছি।",
            undoToken = UndoToken.simple("calendar_event", eventId.toString()),
            memorable = listOf("calendar: $title on ${EVENT_TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(startMillis), zone))}"),
        )
    }

    override suspend fun undo(undoToken: String): PluginResult {
        val context = pluginContext()
        val eventId = undoToken.toLongOrNull()
            ?: return PluginResult.Failure("That is not an event id Sarothi issued.", "BadUndoTokenException")
        val deleted = runCatching {
            context.appContext.contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null,
            )
        }.getOrElse { 0 }
        return if (deleted > 0) {
            PluginResult.Success("Removed the calendar event again.", Json.obj { addProperty("deleted_event_id", eventId) })
        } else {
            PluginResult.Failure("The event could not be removed (it may already be gone).", "NotFoundException")
        }
    }
}

/** Lists upcoming events. */
class CalendarListPlugin : Plugin {
    override val name = "calendar_list"
    override val description =
        "List calendar events, by default everything from now until a date. Use it when the user asks " +
            "'what's on today' or 'am I free on Friday'. Times come back in the phone's local zone."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.READ_ONLY
    override val requiredPermissions = listOf(android.Manifest.permission.READ_CALENDAR)

    override val parameters = JsonSchema(
        properties = mapOf(
            "from" to JsonSchema.Property.Text("Start of the window, YYYY-MM-DD HH:mm. Defaults to now."),
            "until" to JsonSchema.Property.Text("End of the window. Defaults to 14 days from 'from'."),
            "limit" to JsonSchema.Property.Integer("How many events to return.", minimum = 1, maximum = 60, default = 20),
        ),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val now = System.currentTimeMillis()
        val from = params.stringOrNull("from")?.takeIf { it.isNotBlank() }?.let { text ->
            parseLocalDateTime(text)
                ?: return PluginResult.Failure("\"$text\" is not a readable date. Use YYYY-MM-DD HH:mm.", "DateTimeParseException", retriable = true)
        } ?: now
        val until = params.stringOrNull("until")?.takeIf { it.isNotBlank() }?.let { text ->
            parseLocalDateTime(text)
                ?: return PluginResult.Failure("\"$text\" is not a readable date. Use YYYY-MM-DD HH:mm.", "DateTimeParseException", retriable = true)
        } ?: from + DEFAULT_WINDOW_MILLIS

        if (until <= from) {
            return PluginResult.Failure("The end of the window has to be after the start.", "DateTimeRangeException", retriable = true)
        }
        val limit = params.get("limit")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, 60) ?: 20

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toString())
            .appendPath(until.toString())
            .build()

        val cursor: Cursor = runCatching {
            context.appContext.contentResolver.query(
                uri, projection, null, null, CalendarContract.Instances.BEGIN + " ASC",
            )
        }.getOrElse { failure ->
            Log.w(TAG, "Calendar instances query failed", failure)
            return PluginResult.Failure(
                "Android refused to read the calendar: ${failure.javaClass.simpleName}",
                failure.javaClass.simpleName,
                retriable = false,
            )
        } ?: return PluginResult.Failure("The calendar returned nothing at all.", "CalendarUnavailableException", retriable = true)

        val zone = ZoneId.systemDefault()
        val events = cursor.use { rows ->
            val idColumn = rows.getColumnIndex(CalendarContract.Instances.EVENT_ID)
            val titleColumn = rows.getColumnIndex(CalendarContract.Instances.TITLE)
            val beginColumn = rows.getColumnIndex(CalendarContract.Instances.BEGIN)
            val endColumn = rows.getColumnIndex(CalendarContract.Instances.END)
            val allDayColumn = rows.getColumnIndex(CalendarContract.Instances.ALL_DAY)
            val locationColumn = rows.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
            val calendarColumn = rows.getColumnIndex(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
            if (beginColumn < 0) return@use emptyList()

            val found = mutableListOf<JsonObject>()
            while (rows.moveToNext() && found.size < limit) {
                val begin = rows.getLong(beginColumn)
                val allDay = allDayColumn >= 0 && rows.getInt(allDayColumn) == 1
                found += Json.obj {
                    addProperty("event_id", if (idColumn >= 0) rows.getLong(idColumn) else -1L)
                    addProperty("title", if (titleColumn >= 0) rows.getString(titleColumn) ?: "(untitled)" else "(untitled)")
                    addProperty("begin", begin)
                    addProperty("begin_text", EVENT_TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(begin), zone)))
                    if (endColumn >= 0 && !rows.isNull(endColumn)) {
                        addProperty("end", rows.getLong(endColumn))
                    }
                    addProperty("all_day", allDay)
                    if (locationColumn >= 0 && !rows.isNull(locationColumn)) {
                        addProperty("location", rows.getString(locationColumn))
                    }
                    if (calendarColumn >= 0 && !rows.isNull(calendarColumn)) {
                        addProperty("calendar", rows.getString(calendarColumn))
                    }
                }
            }
            found
        }

        val data = Json.obj {
            add("events", Json.arr { events.forEach { add(it) } })
            addProperty("count", events.size)
            addProperty("window_from", from)
            addProperty("window_until", until)
        }
        return PluginResult.Success(
            if (events.isEmpty()) {
                "Nothing on the calendar between " +
                    "${EVENT_TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(from), zone))} and " +
                    "${EVENT_TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(until), zone))}."
            } else {
                "${events.size} event(s); next is \"" +
                    (events.first().get("title")?.asString ?: "") + "\" at " +
                    (events.first().get("begin_text")?.asString ?: "")
            },
            data,
        )
    }

    private companion object {
        const val DEFAULT_WINDOW_MILLIS = 14L * 24 * 60 * 60 * 1000
    }
}

/** Deletes a calendar event. Always confirmed; reversible. */
class CalendarDeletePlugin : Plugin {
    override val name = "calendar_delete"
    override val description =
        "Delete a calendar event by id, from calendar_list. Sarothi always shows the event's title and " +
            "time and asks for confirmation first, because deleting an appointment is not something to " +
            "do on a guess."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.SENSITIVE
    override val supportsUndo = true
    override val requiredPermissions = listOf(android.Manifest.permission.WRITE_CALENDAR)

    override val parameters = JsonSchema(
        properties = mapOf(
            "event_id" to JsonSchema.Property.Integer("The event id from calendar_list.", minimum = 1),
        ),
        required = listOf("event_id"),
    )

    override fun describeForConfirmation(params: JsonObject): ConfirmationPreview {
        val eventId = params.get("event_id")?.takeIf { it.isJsonPrimitive }?.asLong ?: -1L
        return ConfirmationPreview(
            title = "Delete this calendar event?",
            detailLines = listOf(
                "Event id: $eventId",
                "Sarothi will remove it from your calendar. You can undo it once, right after.",
            ),
            reason = ConfirmationReason.DELETION,
            allowRemember = false,
        )
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val eventId = params.get("event_id")?.takeIf { it.isJsonPrimitive && it.asLong > 0 }?.asLong
            ?: throw com.ngi.sarothi.core.error.MissingInformationException(
                field = "event_id",
                questionForUser = "Which calendar event should Sarothi delete? Run calendar_list first to see the ids.",
            )

        // Read the event before deleting it, so the undo can restore it and the
        // summary can say what actually went.
        val existing = readEvent(context.appContext, eventId)
            ?: return PluginResult.Failure(
                "There is no calendar event with id $eventId.",
                "NotFoundException",
                retriable = true,
            )

        val deleted = runCatching {
            context.appContext.contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null,
            )
        }.getOrElse { failure ->
            return PluginResult.Failure(
                "Android refused to delete the event: ${failure.javaClass.simpleName}",
                failure.javaClass.simpleName,
                retriable = false,
            )
        }
        if (deleted == 0) {
            return PluginResult.Failure("The event was already gone.", "NotFoundException", retriable = false)
        }

        return PluginResult.Success(
            summaryForUser = "Deleted \"${existing.title}\" from the calendar.",
            data = Json.obj {
                addProperty("event_id", eventId)
                addProperty("title", existing.title)
                addProperty("begin", existing.beginMillis)
                addProperty("calendar_id", existing.calendarId)
                addProperty("restorable", true)
            },
            spoken = "ইভেন্টটি মুছে দিয়েছি।",
            // The event row is gone, so everything needed to rebuild it has to
            // travel in the token itself: UndoRegistry hands back one string,
            // possibly after a process restart, and there is nothing left to
            // look up.
            undoToken = UndoToken.encode(
                "calendar_event_restore",
                Json.obj {
                    addProperty("id", eventId)
                    addProperty("title", existing.title)
                    addProperty("begin", existing.beginMillis)
                    existing.calendarId?.let { addProperty("calendar_id", it) }
                    existing.endMillis?.let { addProperty("end", it) }
                    existing.allDay?.let { addProperty("all_day", it) }
                    existing.location?.let { addProperty("location", it) }
                    existing.description?.let { addProperty("description", it) }
                },
            ),
        )
    }

    override suspend fun undo(undoToken: String): PluginResult {
        val context = pluginContext()
        val snapshot = UndoToken.decode(undoToken, "calendar_event_restore")
            ?: return PluginResult.Failure(
                summaryForUser = "That undo token is not one Sarothi issued for a calendar event, so " +
                    "there is nothing it can put back.",
                errorClass = "BadUndoTokenException",
                retriable = false,
            )
        val title = snapshot.get("title")?.takeIf { it.isJsonPrimitive }?.asString
        val begin = snapshot.get("begin")?.takeIf { it.isJsonPrimitive }?.asLong
        if (title == null || begin == null) {
            return PluginResult.Failure(
                "The saved copy of that event is incomplete, so Sarothi will not re-create something " +
                    "different from what was deleted.",
                "UndoUnavailableException",
                retriable = false,
            )
        }
        val end = snapshot.get("end")?.takeIf { it.isJsonPrimitive }?.asLong ?: begin + 60 * 60 * 1000L
        val allDay = snapshot.get("all_day")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val zone = ZoneId.systemDefault()

        val values = ContentValues().apply {
            snapshot.get("calendar_id")?.takeIf { it.isJsonPrimitive }?.asLong?.let {
                put(CalendarContract.Events.CALENDAR_ID, it)
            }
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone.id)
            put(CalendarContract.Events.EVENT_END_TIMEZONE, if (allDay) "UTC" else zone.id)
            if (allDay) put(CalendarContract.Events.ALL_DAY, 1)
            snapshot.get("location")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            snapshot.get("description")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
            put(CalendarContract.Events.HAS_ALARM, 0)
        }
        val uri = runCatching {
            context.appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        }.getOrNull()
        return if (uri != null) {
            PluginResult.Success(
                summaryForUser = "Put \"$title\" back on the calendar. Its reminder could not be " +
                    "recovered, so add one again if you need it.",
                data = Json.obj {
                    addProperty("event_id", uri.lastPathSegment ?: "unknown")
                    addProperty("reminder_restored", false)
                },
            )
        } else {
            PluginResult.Failure(
                "Android refused to re-create the event.",
                "UndoFailedException",
                retriable = false,
            )
        }
    }

    private class EventSnapshot(
        val title: String,
        val beginMillis: Long,
        val endMillis: Long?,
        val allDay: Boolean?,
        val location: String?,
        val description: String?,
        val calendarId: Long?,
    )

    private fun readEvent(context: Context, eventId: Long): EventSnapshot? {
        val cursor = runCatching {
            context.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                arrayOf(
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.ALL_DAY,
                    CalendarContract.Events.EVENT_LOCATION,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.CALENDAR_ID,
                ),
                null, null, null,
            )
        }.getOrNull() ?: return null
        return cursor.use { rows ->
            if (!rows.moveToFirst()) return@use null
            val titleColumn = rows.getColumnIndex(CalendarContract.Events.TITLE)
            val beginColumn = rows.getColumnIndex(CalendarContract.Events.DTSTART)
            val endColumn = rows.getColumnIndex(CalendarContract.Events.DTEND)
            val allDayColumn = rows.getColumnIndex(CalendarContract.Events.ALL_DAY)
            val locationColumn = rows.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
            val descriptionColumn = rows.getColumnIndex(CalendarContract.Events.DESCRIPTION)
            val calendarColumn = rows.getColumnIndex(CalendarContract.Events.CALENDAR_ID)
            EventSnapshot(
                title = if (titleColumn >= 0) rows.getString(titleColumn) ?: "(untitled)" else "(untitled)",
                beginMillis = if (beginColumn >= 0) rows.getLong(beginColumn) else 0L,
                endMillis = if (endColumn >= 0 && !rows.isNull(endColumn)) rows.getLong(endColumn) else null,
                allDay = if (allDayColumn >= 0 && !rows.isNull(allDayColumn)) rows.getInt(allDayColumn) == 1 else null,
                location = if (locationColumn >= 0 && !rows.isNull(locationColumn)) rows.getString(locationColumn) else null,
                description = if (descriptionColumn >= 0 && !rows.isNull(descriptionColumn)) rows.getString(descriptionColumn) else null,
                calendarId = if (calendarColumn >= 0 && !rows.isNull(calendarColumn)) rows.getLong(calendarColumn) else null,
            )
        }
    }
}

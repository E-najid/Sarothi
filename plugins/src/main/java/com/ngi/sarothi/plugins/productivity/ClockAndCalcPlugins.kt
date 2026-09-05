package com.ngi.sarothi.plugins.productivity

import android.content.Intent
import android.provider.AlarmClock
import com.google.gson.JsonObject
import com.ngi.sarothi.core.plugin.JsonSchema
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.core.plugin.PluginAvailability
import com.ngi.sarothi.core.plugin.PluginCategory
import com.ngi.sarothi.core.plugin.PluginContext
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.plugin.pluginContext
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import com.ngi.sarothi.plugins.common.Digits
import com.ngi.sarothi.plugins.common.Expression
import com.ngi.sarothi.plugins.common.LaunchOutcome
import com.ngi.sarothi.plugins.common.launchForResult
import com.ngi.sarothi.plugins.common.textOrAsk
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Reads a time of day from the several ways people and models write one. */
private fun parseTimeOfDay(text: String): LocalTime? {
    val normalised = Digits.toWestern(text.trim()).replace('.', ':')
    val amPm = Regex("""^(\d{1,2}):(\d{2})\s*(am|pm|AM|PM|এএম|পিএম)$""").find(normalised)
    if (amPm != null) {
        var hour = amPm.groupValues[1].toIntOrNull() ?: return null
        val minute = amPm.groupValues[2].toIntOrNull() ?: return null
        val suffix = amPm.groupValues[3].lowercase()
        val isPm = suffix.startsWith("p") || suffix.startsWith("পি")
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

/** Sets an alarm in the system Clock app. */
/**
 * The AOSP DeskClock provider's alarm-instance table.
 *
 * `android.provider.AlarmClock.Instances` is @hide, so its CONTENT_URI and column
 * names cannot be referenced at compile time -- unlike `AlarmClock.ACTION_SET_ALARM`
 * just above, which is public. These are the literal values AOSP DeskClock uses.
 *
 * The provider is not part of any supported API, is not exported on many devices,
 * and is not exported at all on recent Android, so reading it is best-effort and
 * usually returns nothing. Only [SetAlarmPlugin.existingAlarms] uses it, and only to
 * avoid setting a duplicate alarm: failing empty makes Sarothi set the alarm rather
 * than skip one it could not confirm. Nothing here reports the user's alarms.
 */
private val ALARM_INSTANCES_URI =
    android.net.Uri.parse("content://com.android.deskclock/instances")
private const val ALARM_COLUMN_HOUR = "hour"
private const val ALARM_COLUMN_MINUTES = "minutes"
private const val ALARM_COLUMN_MESSAGE = "message"

class SetAlarmPlugin : Plugin {
    override val name = "set_alarm"
    override val description =
        "Set an alarm in the phone's Clock app for a time of day, e.g. '06:30' or '7:15 pm'. With " +
            "skip_if_exists=true Sarothi first checks whether that alarm is already set. The alarm is " +
            "created by the system Clock app, so it survives Sarothi being closed."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "time" to JsonSchema.Property.Text("Time of day, 24-hour ('06:30') or with am/pm ('7:15 pm')."),
            "label" to JsonSchema.Property.Text("What the alarm is for."),
            "days" to JsonSchema.Property.List(
                "Which weekdays: MONDAY, TUESAY, … Leave empty for a one-off alarm tomorrow or today.",
                items = JsonSchema.Property.Text("One weekday"),
            ),
            "vibrate" to JsonSchema.Property.Flag("Vibrate as well as ring.", default = true),
            "skip_if_exists" to JsonSchema.Property.Flag(
                "Do nothing if an alarm for this exact time already exists.",
                default = false,
            ),
        ),
        required = listOf("time"),
    )

    override val example = """{"time":"06:30","label":"ফজর"}"""

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val resolves = context.appContext.packageManager
            .queryIntentActivities(Intent(AlarmClock.ACTION_SET_ALARM), 0)
            .isNotEmpty()
        return if (resolves) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                "This phone has no clock app that accepts alarm requests.",
                fixAction = "Install a clock app, or use schedule_task instead — Sarothi can fire its own alarm.",
            )
        }
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val timeText = params.textOrAsk("time", "What time should the alarm go off?")
        val time = parseTimeOfDay(timeText)
            ?: return PluginResult.Failure(
                "\"$timeText\" is not a time Sarothi can read. Use 24-hour (06:30) or am/pm (7:15 pm).",
                "TimeParseException",
                retriable = true,
            )
        val label = params.stringOrNull("label")?.takeIf { it.isNotBlank() } ?: "Sarothi alarm"
        val vibrate = params.get("vibrate")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        val skipIfExists = params.get("skip_if_exists")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val days = params.getAsJsonArray("days")?.mapNotNull {
            if (it.isJsonPrimitive) weekdayToInt(it.asString) else null
        } ?: emptyList()

        if (skipIfExists && existingAlarms(context.appContext).any { it.first == time.toSecondOfDay() / 60 }) {
            return PluginResult.Success(
                "An alarm for ${TIME_FORMAT.format(time)} is already set, so nothing was added.",
                Json.obj { addProperty("already_set", true); addProperty("time", TIME_FORMAT.format(time)) },
            )
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, time.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_VIBRATE, vibrate)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            if (days.isNotEmpty()) {
                putExtra(AlarmClock.EXTRA_DAYS, days.toIntArray())
            }
        }
        return when (val outcome = context.appContext.launchForResult(intent)) {
            LaunchOutcome.Started -> PluginResult.Success(
                summaryForUser = "Set an alarm for ${TIME_FORMAT.format(time)}" +
                    (if (days.isNotEmpty()) " on ${days.size} weekday(s)" else "") +
                    " (\"$label\"). The Clock app is open so you can check it.",
                data = Json.obj {
                    addProperty("hour", time.hour)
                    addProperty("minute", time.minute)
                    addProperty("label", label)
                    addProperty("vibrate", vibrate)
                    add("days", Json.arr { days.forEach { add(it) } })
                },
                spoken = "${TIME_FORMAT.format(time)}-এ অ্যালার্ম সেট করে দিয়েছি।",
                memorable = listOf("alarm set for ${TIME_FORMAT.format(time)}: $label"),
            )
            is LaunchOutcome.NoHandler -> PluginResult.Failure(outcome.reason, "ActivityNotFoundException")
            is LaunchOutcome.Refused -> PluginResult.Failure(outcome.reason, "SecurityException")
        }
    }

    /**
     * Alarms the system Clock app will show us, as (minutes since midnight, label).
     *
     * `android.provider.AlarmClock.Instances` is @hide, so neither the URI nor the
     * column names can be referenced at compile time; these are the AOSP DeskClock
     * values. That provider is also unexported on many devices and on modern Android,
     * so this is best-effort by nature and returns nothing when it cannot read.
     *
     * The only caller uses it to avoid setting a duplicate alarm, so failing empty is
     * the safe direction: Sarothi sets the alarm rather than skipping one it could not
     * confirm. Nothing here claims to have listed the user's alarms.
     */
    private fun existingAlarms(context: android.content.Context): List<Pair<Int, String>> {
        val cursor = runCatching {
            context.contentResolver.query(
                ALARM_INSTANCES_URI,
                arrayOf(ALARM_COLUMN_HOUR, ALARM_COLUMN_MINUTES, ALARM_COLUMN_MESSAGE),
                null, null, null,
            )
        }.getOrNull() ?: return emptyList()
        return cursor.use { rows ->
            val hourColumn = rows.getColumnIndex(ALARM_COLUMN_HOUR)
            val minuteColumn = rows.getColumnIndex(ALARM_COLUMN_MINUTES)
            val messageColumn = rows.getColumnIndex(ALARM_COLUMN_MESSAGE)
            val found = mutableListOf<Pair<Int, String>>()
            while (rows.moveToNext()) {
                if (hourColumn < 0 || minuteColumn < 0) break
                val minutes = rows.getInt(hourColumn) * 60 + rows.getInt(minuteColumn)
                val message = if (messageColumn >= 0) rows.getString(messageColumn) ?: "" else ""
                found += minutes to message
            }
            found
        }
    }

    private fun weekdayToInt(name: String): Int? = when (name.trim().uppercase()) {
        "MONDAY", "MON" -> java.util.Calendar.MONDAY
        "TUESDAY", "TUE" -> java.util.Calendar.TUESDAY
        "WEDNESDAY", "WED" -> java.util.Calendar.WEDNESDAY
        "THURSDAY", "THU" -> java.util.Calendar.THURSDAY
        "FRIDAY", "FRI" -> java.util.Calendar.FRIDAY
        "SATURDAY", "SAT" -> java.util.Calendar.SATURDAY
        "SUNDAY", "SUN" -> java.util.Calendar.SUNDAY
        else -> null
    }
}

/** Starts a countdown in the system Clock app. */
class StartTimerPlugin : Plugin {
    override val name = "start_timer"
    override val description =
        "Start a countdown timer in the phone's Clock app. Give the length in minutes and/or seconds. " +
            "Use it for cooking, breaks, or anything the user wants to be pinged about in a fixed " +
            "amount of time."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "minutes" to JsonSchema.Property.Integer("Whole minutes.", minimum = 0, maximum = 1440),
            "seconds" to JsonSchema.Property.Integer("Extra seconds.", minimum = 0, maximum = 59),
            "label" to JsonSchema.Property.Text("What the timer is for."),
        ),
    )

    override val example = """{"minutes":10,"label":"ডিম সিদ্ধ"}"""

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val resolves = context.appContext.packageManager
            .queryIntentActivities(Intent(AlarmClock.ACTION_SET_TIMER), 0).isNotEmpty()
        return if (resolves) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                "This phone has no clock app that accepts timer requests.",
                fixAction = "Use schedule_task with a one-off time instead.",
            )
        }
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val minutes = params.get("minutes")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(0, 1440) ?: 0
        val seconds = params.get("seconds")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(0, 59) ?: 0
        val totalSeconds = minutes * 60 + seconds
        if (totalSeconds <= 0) {
            throw com.ngi.sarothi.core.error.MissingInformationException(
                field = "minutes",
                questionForUser = "How long should the timer run? Give minutes, seconds, or both.",
            )
        }
        val label = params.stringOrNull("label")?.takeIf { it.isNotBlank() } ?: "Sarothi timer"

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        return when (val outcome = context.appContext.launchForResult(intent)) {
            LaunchOutcome.Started -> PluginResult.Success(
                summaryForUser = "Started a ${minutes}m ${seconds}s timer (\"$label\").",
                data = Json.obj {
                    addProperty("seconds", totalSeconds)
                    addProperty("label", label)
                    addProperty("finishes_at", System.currentTimeMillis() + totalSeconds * 1000L)
                },
                spoken = "টাইমার চালু করে দিয়েছি।",
            )
            is LaunchOutcome.NoHandler -> PluginResult.Failure(outcome.reason, "ActivityNotFoundException")
            is LaunchOutcome.Refused -> PluginResult.Failure(outcome.reason, "SecurityException")
        }
    }
}

/** Exact arithmetic, so a shopping total is never a model's estimate. */
class CalculatorPlugin : Plugin {
    override val name = "calculator"
    override val description =
        "Evaluate an arithmetic expression exactly: + - * / % ^, brackets, sqrt, abs, round, floor, " +
            "ceil, log10, ln, sin, cos, tan (degrees), pi, e. Bengali digits and × ÷ are accepted. Use " +
            "this for any money or measurement arithmetic instead of working it out yourself."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "expression" to JsonSchema.Property.Text("The expression, e.g. '1240 * 1.15 + 60' or '(৳৫০০ + ৳৩২০) / ২'."),
            "scale" to JsonSchema.Property.Integer("Decimal places to round the answer to.", minimum = 0, maximum = 12, default = 6),
        ),
        required = listOf("expression"),
    )

    override val example = """{"expression":"(1240 * 3) + 150","scale":2}"""

    override suspend fun execute(params: JsonObject): PluginResult {
        val expression = params.textOrAsk("expression", "What should Sarothi calculate?")
        val scale = params.get("scale")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(0, 12) ?: 6

        val result = try {
            Expression.evaluate(expression, scale)
        } catch (failure: Expression.ParseException) {
            return PluginResult.Failure(
                summaryForUser = failure.message ?: "That expression could not be read.",
                errorClass = "ArithmeticParseException",
                retriable = true,
            )
        } catch (failure: ArithmeticException) {
            return PluginResult.Failure(
                summaryForUser = "The calculation cannot be completed exactly: ${failure.message}",
                errorClass = "ArithmeticException",
                retriable = true,
            )
        }

        val plain = result.toPlainString()
        return PluginResult.Success(
            summaryForUser = "$expression = $plain",
            data = Json.obj {
                addProperty("expression", expression)
                addProperty("result", plain)
                addProperty("result_number", result.toDouble())
                addProperty("scale", scale)
                addProperty("bangla", Digits.toBangla(plain))
            },
            spoken = "উত্তর $plain",
        )
    }
}

/** Unit and currency-free conversions that are exact. */
class ConvertUnitsPlugin : Plugin {
    override val name = "convert_units"
    override val description =
        "Convert between length, weight, temperature, area, volume, data-size and time units, exactly. " +
            "This does NOT convert currencies: exchange rates change constantly and Sarothi has no " +
            "offline rate table, so it refuses rather than making one up."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "value" to JsonSchema.Property.Text("The amount, e.g. '1.5'."),
            "from" to JsonSchema.Property.Text("Source unit, e.g. 'km', 'kg', 'celsius', 'mb'."),
            "to" to JsonSchema.Property.Text("Target unit, e.g. 'mile', 'pound', 'fahrenheit', 'gb'."),
        ),
        required = listOf("value", "from", "to"),
    )

    override val example = """{"value":"5","from":"km","to":"mile"}"""

    override suspend fun execute(params: JsonObject): PluginResult {
        val valueText = params.textOrAsk("value", "How much should Sarothi convert?")
        val from = params.textOrAsk("from", "Convert from which unit?").trim().lowercase()
        val to = params.textOrAsk("to", "Convert to which unit?").trim().lowercase()

        val value = runCatching { Digits.toWestern(valueText).replace(",", "").toBigDecimal() }
            .getOrElse {
                return PluginResult.Failure(
                    "\"$valueText\" is not a number.",
                    "NumberFormatException",
                    retriable = true,
                )
            }

        val fromScale = UNITS[from]
        val toScale = UNITS[to]
        if (fromScale == null || toScale == null) {
            val unknown = listOfNotNull(
                if (fromScale == null) "'$from'" else null,
                if (toScale == null) "'$to'" else null,
            )
            return PluginResult.Unavailable(
                PluginAvailability.unavailable(
                    "Sarothi does not know the unit(s) ${unknown.joinToString()}. It supports: " +
                        UNITS.keys.sorted().joinToString(),
                    fixAction = "For currency, say so plainly — Sarothi has no live exchange rates and will not invent one.",
                ),
            )
        }
        if (fromScale.family != toScale.family) {
            return PluginResult.Failure(
                "'$from' measures ${fromScale.family} and '$to' measures ${toScale.family}; they cannot " +
                    "be converted into each other.",
                "UnitMismatchException",
                retriable = true,
            )
        }

        val result = if (fromScale.family == "temperature") {
            convertTemperature(value, from, to)
        } else {
            value.multiply(fromScale.factor).divide(toScale.factor, 12, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
        }
        return PluginResult.Success(
            "${value.toPlainString()} $from = ${result.toPlainString()} $to",
            Json.obj {
                addProperty("value", value.toPlainString())
                addProperty("from", from)
                addProperty("to", to)
                addProperty("result", result.toPlainString())
                addProperty("result_number", result.toDouble())
                addProperty("family", fromScale.family)
                addProperty("bangla", "${Digits.toBangla(value.toPlainString())} $from = ${Digits.toBangla(result.toPlainString())} $to")
            },
        )
    }

    private fun convertTemperature(
        value: java.math.BigDecimal,
        from: String,
        to: String,
    ): java.math.BigDecimal {
        val celsius = when (from) {
            "c", "celsius", "°c" -> value
            "f", "fahrenheit", "°f" -> value.subtract(java.math.BigDecimal(32))
                .multiply(java.math.BigDecimal(5))
                .divide(java.math.BigDecimal(9), 12, java.math.RoundingMode.HALF_UP)
            "k", "kelvin" -> value.subtract(java.math.BigDecimal("273.15"))
            else -> return value
        }
        val out = when (to) {
            "c", "celsius", "°c" -> celsius
            "f", "fahrenheit", "°f" -> celsius.multiply(java.math.BigDecimal(9))
                .divide(java.math.BigDecimal(5), 12, java.math.RoundingMode.HALF_UP)
                .add(java.math.BigDecimal(32))
            "k", "kelvin" -> celsius.add(java.math.BigDecimal("273.15"))
            else -> celsius
        }
        return out.setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
    }

    private class Unit(val family: String, val factor: java.math.BigDecimal)

    private companion object {
        private fun bd(value: String) = java.math.BigDecimal(value)

        val UNITS: Map<String, Unit> = buildMap {
            // length → metres
            listOf("mm" to "0.001", "cm" to "0.01", "m" to "1", "meter" to "1", "metre" to "1",
                "km" to "1000", "inch" to "0.0254", "in" to "0.0254", "ft" to "0.3048", "foot" to "0.3048",
                "feet" to "0.3048", "yd" to "0.9144", "yard" to "0.9144", "mile" to "1609.344",
                "mi" to "1609.344", "nauticalmile" to "1852").forEach { (name, factor) ->
                put(name, Unit("length", bd(factor)))
            }
            // weight → grams
            listOf("mg" to "0.001", "g" to "1", "gram" to "1", "kg" to "1000", "kilogram" to "1000",
                "tonne" to "1000000", "t" to "1000000", "oz" to "28.349523125", "ounce" to "28.349523125",
                "lb" to "453.59237", "pound" to "453.59237", "maund" to "37324.17", "সের" to "933.1043",
                "ser" to "933.1043").forEach { (name, factor) ->
                put(name, Unit("weight", bd(factor)))
            }
            // area → square metres
            listOf("sqm" to "1", "m2" to "1", "sqkm" to "1000000", "km2" to "1000000",
                "sqft" to "0.09290304", "ft2" to "0.09290304", "acre" to "4046.8564224",
                "hectare" to "10000", "bigha" to "1337.7632", "katha" to "66.890036",
                "decimal" to "40.468564224", "shatak" to "40.468564224").forEach { (name, factor) ->
                put(name, Unit("area", bd(factor)))
            }
            // volume → litres
            listOf("ml" to "0.001", "l" to "1", "litre" to "1", "liter" to "1",
                "gallon" to "3.785411784", "gal" to "3.785411784", "quart" to "0.946352946",
                "pint" to "0.473176473", "cup" to "0.2365882365",
                "tbsp" to "0.0147867648", "tsp" to "0.00492892159").forEach { (name, factor) ->
                put(name, Unit("volume", bd(factor)))
            }
            // data → bytes
            listOf("b" to "1", "byte" to "1", "kb" to "1024", "mb" to "1048576",
                "gb" to "1073741824", "tb" to "1099511627776").forEach { (name, factor) ->
                put(name, Unit("data", bd(factor)))
            }
            // time → seconds
            listOf("ms" to "0.001", "s" to "1", "sec" to "1", "second" to "1", "min" to "60",
                "minute" to "60", "h" to "3600", "hr" to "3600", "hour" to "3600",
                "day" to "86400", "week" to "604800", "year" to "31557600").forEach { (name, factor) ->
                put(name, Unit("time", bd(factor)))
            }
            // temperature has no linear factor
            listOf("c", "celsius", "°c", "f", "fahrenheit", "°f", "k", "kelvin").forEach { name ->
                put(name, Unit("temperature", bd("1")))
            }
        }
    }
}

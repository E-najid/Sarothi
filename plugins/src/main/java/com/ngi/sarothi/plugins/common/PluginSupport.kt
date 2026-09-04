package com.ngi.sarothi.plugins.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.JsonObject
import com.ngi.sarothi.core.error.MissingInformationException
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull

/**
 * Reads a parameter, or asks the user for it.
 *
 * This is the single most important helper in the plugin set: it is the reason a
 * plugin never invents a phone number, an amount or an address. A missing value
 * becomes [MissingInformationException], which [com.ngi.sarothi.core.plugin.PluginManager]
 * turns into `NeedsUserInput`, which pauses the task and puts a question on screen.
 */
fun JsonObject.textOrAsk(field: String, question: String, choices: List<String> = emptyList(), secret: Boolean = false): String =
    stringOrNull(field)?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw MissingInformationException(field, question, choices, secret)

fun JsonObject.longOrAsk(field: String, question: String): Long {
    val raw = get(field) ?: throw MissingInformationException(field, question)
    if (raw.isJsonPrimitive && raw.asJsonPrimitive.isNumber) return raw.asLong
    val text = raw.takeIf { it.isJsonPrimitive }?.asString?.trim()
        ?: throw MissingInformationException(field, question)
    return text.toLongOrNull() ?: throw MissingInformationException(
        field,
        "$question (Sarothi needs a whole number; \"$text\" is not one.)",
    )
}

fun JsonObject.doubleOrAsk(field: String, question: String): Double {
    val raw = get(field) ?: throw MissingInformationException(field, question)
    if (raw.isJsonPrimitive && raw.asJsonPrimitive.isNumber) return raw.asDouble
    val text = raw.takeIf { it.isJsonPrimitive }?.asString?.trim()
        ?: throw MissingInformationException(field, question)
    return text.toDoubleOrNull() ?: throw MissingInformationException(
        field,
        "$question (Sarothi needs a number; \"$text\" is not one.)",
    )
}

/** Starts an activity, translating the one failure mode that actually happens. */
fun Context.launchForResult(intent: Intent): LaunchOutcome {
    val flagged = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        startActivity(flagged)
        LaunchOutcome.Started
    } catch (notFound: ActivityNotFoundException) {
        LaunchOutcome.NoHandler(
            "No app on this phone can handle that (${notFound.message ?: "no activity found"}).",
        )
    } catch (security: SecurityException) {
        LaunchOutcome.Refused("Android refused to open it: ${security.message}")
    } catch (failure: Exception) {
        LaunchOutcome.Refused("${failure.javaClass.simpleName}: ${failure.message}")
    }
}

sealed interface LaunchOutcome {
    data object Started : LaunchOutcome
    data class NoHandler(val reason: String) : LaunchOutcome
    data class Refused(val reason: String) : LaunchOutcome
}

fun LaunchOutcome.toResult(successMessage: String): PluginResult = when (this) {
    LaunchOutcome.Started -> PluginResult.Success(successMessage, Json.obj { addProperty("launched", true) })
    is LaunchOutcome.NoHandler -> PluginResult.Failure(
        summaryForUser = reason,
        errorClass = "ActivityNotFoundException",
        retriable = false,
    )
    is LaunchOutcome.Refused -> PluginResult.Failure(
        summaryForUser = reason,
        errorClass = "SecurityException",
        retriable = false,
    )
}

fun uriOrNull(value: String?): Uri? = value?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }

/**
 * Bengali and Western digits.
 *
 * Sarothi's users type both, and a phone number written in Bengali digits must
 * still dial. Normalising on the way in and formatting on the way out is what
 * makes "০১৭১২৩৪৫৬৭" and "01712345678" the same number to every plugin.
 */
object Digits {
    private const val BANGLA = "০১২৩৪৫৬৭৮৯"
    private const val WESTERN = "0123456789"

    fun toWestern(text: String): String = buildString(text.length) {
        text.forEach { char ->
            val index = BANGLA.indexOf(char)
            append(if (index >= 0) WESTERN[index] else char)
        }
    }

    fun toBangla(text: String): String = buildString(text.length) {
        text.forEach { char ->
            val index = WESTERN.indexOf(char)
            append(if (index >= 0) BANGLA[index] else char)
        }
    }

    /** Keeps only characters a dialler accepts, after normalising digits. */
    fun toDiallable(text: String): String =
        toWestern(text).filter { it.isDigit() || it == '+' || it == '*' || it == '#' || it == '-' }

    fun isProbablyPhoneNumber(text: String): Boolean {
        val digits = toDiallable(text).filter { it.isDigit() }
        return digits.length in 6..15
    }
}

/** Formats a duration in words, in the language the user picked. */
object Formatting {
    fun duration(millis: Long, bangla: Boolean): String {
        val seconds = millis / 1000
        return when {
            seconds < 60 -> if (bangla) "${Digits.toBangla(seconds.toString())} সেকেন্ড" else "${seconds}s"
            seconds < 3600 -> {
                val minutes = seconds / 60
                if (bangla) "${Digits.toBangla(minutes.toString())} মিনিট" else "${minutes}m"
            }
            else -> {
                val hours = seconds / 3600
                if (bangla) "${Digits.toBangla(hours.toString())} ঘণ্টা" else "${hours}h"
            }
        }
    }

    fun bytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
        bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MiB"
        else -> "${"%.2f".format(bytes / 1024.0 / 1024.0 / 1024.0)} GiB"
    }
}

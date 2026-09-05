package com.ngi.sarothi.core.util

import java.time.Instant
import java.util.UUID

/**
 * Identifier generation.
 *
 * Ids are time-sortable so vault listings and history screens come out in a
 * natural order without an extra index: `<prefix>-<base36 epoch millis>-<random>`.
 */
object Ids {
    private val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

    fun newId(prefix: String): String =
        "$prefix-${base36(System.currentTimeMillis())}-${randomSuffix(6)}"

    fun conversationId(): String = newId("conv")
    fun taskId(): String = newId("task")
    fun stepId(): String = newId("step")
    fun memoryId(): String = newId("mem")
    fun noteId(): String = newId("note")
    fun todoId(): String = newId("todo")

    /** Today's date as `YYYY-MM-DD`, used for the daily audit-log file name. */
    fun todayIso(): String = Instant.now().toString().substringBefore('T')

    private fun base36(value: Long): String = java.lang.Long.toString(value, 36)

    private fun randomSuffix(length: Int): String {
        val bytes = UUID.randomUUID().toString().replace("-", "").toByteArray()
        val out = StringBuilder(length)
        for (index in 0 until length) {
            out.append(ALPHABET[(bytes[index % bytes.size].toInt() and 0xFF) % ALPHABET.length])
        }
        return out.toString()
    }
}

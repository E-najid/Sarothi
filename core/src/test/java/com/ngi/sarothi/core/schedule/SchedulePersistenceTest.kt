package com.ngi.sarothi.core.schedule

import com.google.gson.JsonObject
import com.ngi.sarothi.core.util.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A schedule and a notification rule, written out and read back.
 *
 * Both live in the vault as JSON and are read again after a process death, a reboot, or
 * a restore onto a new phone. A field that is written but never read back is invisible
 * in the editor -- the task looks right on screen and then does the wrong thing at the
 * moment nobody is holding the phone, which is the only moment these features run.
 */
class SchedulePersistenceTest {

    private val weekly = ScheduledTask(
        id = "sched-weekly",
        title = "Monday morning weather",
        request = "check the weather in Sylhet and message Rina",
        recurrence = Recurrence.WEEKLY,
        timeOfDay = LocalTime.of(7, 45),
        daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
        dayOfMonth = null,
        oneShotAtEpochMillis = null,
        enabled = true,
        createdAt = "2026-09-04T06:00:00Z",
        lastRunAtEpochMillis = 1_757_000_000_000L,
        lastRunStatus = "success",
        lastRunMessage = "Sent to Rina",
        nextRunAtEpochMillis = 1_757_600_000_000L,
        allowSensitiveSteps = true,
        runCount = 7,
        alarmIsApproximate = true,
    )

    private fun roundTrip(task: ScheduledTask): ScheduledTask {
        val restored = ScheduledTask.fromJson(task.toJson())
        assertNotNull("a task that was just written must be readable back", restored)
        return restored!!
    }

    // ------------------------------------------------------------------ the full record

    @Test
    fun a_weekly_task_comes_back_with_the_same_days_and_time() {
        val restored = roundTrip(weekly)
        assertEquals(weekly, restored)
        assertEquals(Recurrence.WEEKLY, restored.recurrence)
        assertEquals(LocalTime.of(7, 45), restored.timeOfDay)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), restored.daysOfWeek)
    }

    @Test
    fun a_monthly_task_keeps_its_day_of_the_month() {
        val restored = roundTrip(weekly.copy(recurrence = Recurrence.MONTHLY, dayOfMonth = 31, daysOfWeek = emptySet()))
        assertEquals(31, restored.dayOfMonth)
    }

    @Test
    fun a_one_off_keeps_the_instant_it_was_set_for() {
        val restored = roundTrip(
            weekly.copy(recurrence = Recurrence.ONCE, oneShotAtEpochMillis = 1_767_225_600_000L),
        )
        assertEquals(1_767_225_600_000L, restored.oneShotAtEpochMillis)
        assertEquals(Recurrence.ONCE, restored.recurrence)
    }

    /** The history is what the Schedule screen shows to explain a task that did not run. */
    @Test
    fun the_run_history_survives_so_a_failure_is_still_visible_after_a_restart() {
        val restored = roundTrip(weekly.copy(lastRunStatus = "failed", lastRunMessage = "No network"))
        assertEquals(1_757_000_000_000L, restored.lastRunAtEpochMillis)
        assertEquals("failed", restored.lastRunStatus)
        assertEquals("No network", restored.lastRunMessage)
        assertEquals(7, restored.runCount)
        assertEquals(1_757_600_000_000L, restored.nextRunAtEpochMillis)
    }

    /**
     * An inexact alarm means the OS may fire this task minutes or hours late, and the UI
     * says so. Losing the flag on reload would leave a user believing in a punctuality
     * the device never promised.
     */
    @Test
    fun the_approximate_alarm_warning_survives() {
        assertTrue(
            "the flag was written but not read back",
            roundTrip(weekly).alarmIsApproximate,
        )
        assertFalse(roundTrip(weekly.copy(alarmIsApproximate = false)).alarmIsApproximate)
    }

    /**
     * This is the switch that decides whether an unattended run may contain a payment or
     * a deletion. It has to survive as true when the user set it, and default to false
     * when the file does not say -- a restored schedule must never come back with more
     * authority than the one it was given.
     */
    @Test
    fun allow_sensitive_steps_survives_and_defaults_to_denied() {
        assertTrue(roundTrip(weekly).allowSensitiveSteps)
        assertFalse(roundTrip(weekly.copy(allowSensitiveSteps = false)).allowSensitiveSteps)

        val silent = JsonObject().apply {
            addProperty("id", "sched-x")
            addProperty("request", "do the thing")
        }
        assertFalse(
            "an absent flag must mean the safer answer",
            ScheduledTask.fromJson(silent)!!.allowSensitiveSteps,
        )
    }

    @Test
    fun a_disabled_task_stays_disabled() {
        assertFalse("a task the user switched off must not come back on", roundTrip(weekly.copy(enabled = false)).enabled)
    }

    /** Writing a null field as an absent key keeps the vault files readable by hand. */
    @Test
    fun absent_values_are_not_written_as_nulls() {
        val json = weekly.copy(oneShotAtEpochMillis = null, dayOfMonth = null, lastRunStatus = null).toJson()
        assertFalse(json.toString(), json.has("one_shot_at"))
        assertFalse(json.toString(), json.has("day_of_month"))
        assertFalse(json.toString(), json.has("last_run_status"))
        assertTrue("but a field that is set is written", json.has("time_of_day"))
    }

    @Test
    fun writing_then_reading_then_writing_again_produces_the_same_file() {
        val once = Json.stringify(weekly.toJson())
        val twice = Json.stringify(roundTrip(weekly).toJson())
        assertEquals("a round trip that is not stable rewrites the vault on every load", once, twice)
    }

    // ------------------------------------------------------------------ damaged input

    @Test
    fun a_record_with_no_id_or_no_request_is_refused_not_half_built() {
        assertNull(ScheduledTask.fromJson(JsonObject().apply { addProperty("request", "x") }))
        assertNull(ScheduledTask.fromJson(JsonObject().apply { addProperty("id", "x") }))
    }

    @Test
    fun an_unrecognised_recurrence_becomes_a_one_off_that_never_fires() {
        val restored = ScheduledTask.fromJson(
            JsonObject().apply {
                addProperty("id", "sched-x")
                addProperty("request", "do the thing")
                addProperty("recurrence", "fortnightly")
            },
        )!!
        assertEquals(
            "a recurrence Sarothi does not know must not be read as one it does",
            Recurrence.ONCE,
            restored.recurrence,
        )
        assertNull("and with no instant set, it never runs", restored.computeNextRun(1_757_000_000_000L))
    }

    @Test
    fun a_damaged_time_is_dropped_without_losing_the_task() {
        val restored = ScheduledTask.fromJson(
            JsonObject().apply {
                addProperty("id", "sched-x")
                addProperty("request", "do the thing")
                addProperty("recurrence", "daily")
                addProperty("time_of_day", "half past seven")
            },
        )
        assertNotNull("one bad field must not discard the whole schedule", restored)
        assertNull(restored!!.timeOfDay)
        assertEquals(Recurrence.DAILY, restored.recurrence)
    }

    @Test
    fun an_unrecognised_weekday_is_dropped_and_the_others_kept() {
        val restored = ScheduledTask.fromJson(
            JsonObject().apply {
                addProperty("id", "sched-x")
                addProperty("request", "do the thing")
                addProperty("recurrence", "weekly")
                add("days", Json.arr { add("monday"); add("someday"); add("friday") })
            },
        )!!
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), restored.daysOfWeek)
    }

    @Test
    fun recurrence_parses_case_insensitively_and_refuses_what_it_does_not_know() {
        assertEquals(Recurrence.HOURLY, Recurrence.fromJson("hourly"))
        assertEquals(Recurrence.HOURLY, Recurrence.fromJson("HOURLY"))
        assertNull(Recurrence.fromJson("every so often"))
        assertNull("nothing written means nothing known", Recurrence.fromJson(null))
    }

    // ------------------------------------------------------------------ notification rules

    private val rule = NotificationRule(
        id = "rule-otp",
        name = "Bank OTP",
        enabled = true,
        packageNames = listOf("com.example.bank", "com.example.bank.beta"),
        titleContains = listOf("otp", "code"),
        bodyContains = listOf("verify"),
        match = RuleMatch.ANY,
        caseSensitive = true,
        request = "read the code out loud",
        cooldownMillis = 5 * 60 * 1000L,
        createdAt = "2026-09-04T06:00:00Z",
        lastFiredAtEpochMillis = 1_757_000_000_000L,
        fireCount = 3,
        lastResult = "Read the code aloud",
    )

    @Test
    fun a_notification_rule_comes_back_whole() {
        val restored = NotificationRule.fromJson(rule.toJson())
        assertNotNull(restored)
        assertEquals(rule, restored)
        assertEquals(listOf("com.example.bank", "com.example.bank.beta"), restored!!.packageNames)
        assertEquals(RuleMatch.ANY, restored.match)
        assertTrue("case sensitivity decides whether a rule fires at all", restored.caseSensitive)
        assertEquals(5 * 60 * 1000L, restored.cooldownMillis)
        assertEquals(3, restored.fireCount)
    }

    @Test
    fun a_rule_with_no_stated_defaults_comes_back_conservative() {
        val bare = NotificationRule.fromJson(
            JsonObject().apply {
                addProperty("id", "rule-x")
                addProperty("request", "tell me about it")
            },
        )!!
        assertEquals(RuleMatch.ALL, bare.match)
        assertFalse(bare.caseSensitive)
        assertEquals(NotificationRule.DEFAULT_COOLDOWN_MILLIS, bare.cooldownMillis)
        assertTrue(bare.packageNames.isEmpty())
    }

    @Test
    fun a_rule_with_no_id_or_no_request_is_refused() {
        assertNull(NotificationRule.fromJson(JsonObject().apply { addProperty("request", "x") }))
        assertNull(NotificationRule.fromJson(JsonObject().apply { addProperty("id", "x") }))
    }

    @Test
    fun an_unrecognised_match_mode_falls_back_to_requiring_everything() {
        val restored = NotificationRule.fromJson(
            JsonObject().apply {
                addProperty("id", "rule-x")
                addProperty("request", "tell me")
                addProperty("match", "sometimes")
            },
        )!!
        assertEquals(
            "ALL is the mode that fires least often, which is the right guess to make",
            RuleMatch.ALL,
            restored.match,
        )
    }

    @Test
    fun a_rule_round_trip_is_stable() {
        assertEquals(
            Json.stringify(rule.toJson()),
            Json.stringify(NotificationRule.fromJson(rule.toJson())!!.toJson()),
        )
    }
}

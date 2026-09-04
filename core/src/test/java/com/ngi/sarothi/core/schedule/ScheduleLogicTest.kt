package com.ngi.sarothi.core.schedule

import com.ngi.sarothi.core.screen.ObservedNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When a schedule fires, and which notifications trip a rule.
 *
 * Both are decided while nobody is watching: an alarm from `AlarmManager`, a notification
 * arriving with the screen off. There is no user to notice a wrong answer, so a task that
 * fires at the wrong time or a rule that never matches is invisible until the reminder
 * simply does not arrive. Fixed zone throughout, so the expectations do not move with the
 * machine running the tests.
 */
class ScheduleLogicTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")

    private fun task(
        recurrence: Recurrence,
        timeOfDay: LocalTime? = null,
        daysOfWeek: Set<DayOfWeek> = emptySet(),
        dayOfMonth: Int? = null,
        oneShotAtEpochMillis: Long? = null,
        enabled: Boolean = true,
    ) = ScheduledTask(
        id = "sched-test",
        title = "test",
        request = "check the weather and message me",
        recurrence = recurrence,
        timeOfDay = timeOfDay,
        daysOfWeek = daysOfWeek,
        dayOfMonth = dayOfMonth,
        oneShotAtEpochMillis = oneShotAtEpochMillis,
        enabled = enabled,
        createdAt = "2026-09-04T00:00:00Z",
        lastRunAtEpochMillis = null,
        lastRunStatus = null,
        lastRunMessage = null,
        nextRunAtEpochMillis = null,
        allowSensitiveSteps = false,
        runCount = 0,
        alarmIsApproximate = false,
    )

    private fun at(
        year: Int, month: Int, day: Int, hour: Int, minute: Int = 0,
    ): ZonedDateTime = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)

    private fun ZonedDateTime.millis(): Long = toInstant().toEpochMilli()

    private fun instantOf(millis: Long): ZonedDateTime =
        ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone)

    // ------------------------------------------------------------------ recurrence

    /**
     * "Every hour" has to mean every hour. The implementation seeds its candidate from
     * `timeOfDay` (defaulting to 09:00) and then matches HOURLY unconditionally, so if the
     * hourly branch is not taken before that seeding the task fires once a day and the
     * plusHours(1) step is dead code.
     */
    @Test
    fun hourly_fires_at_the_next_top_of_the_hour_not_once_a_day() {
        val from = at(2026, 9, 4, 9, 23)
        val next = task(Recurrence.HOURLY).computeNextRun(from.millis(), zone)

        assertNotNull("An hourly task is always armed", next)
        val result = instantOf(next!!)
        assertEquals("Must be the next hour, not tomorrow morning", 10, result.hour)
        assertEquals(0, result.minute)
        assertEquals(4, result.dayOfMonth)
        assertTrue(result.isAfter(from))
    }

    @Test
    fun hourly_ignores_a_time_of_day_because_it_has_no_meaning_there() {
        val from = at(2026, 9, 4, 14, 5)
        val next = task(Recurrence.HOURLY, timeOfDay = LocalTime.of(7, 30))
            .computeNextRun(from.millis(), zone)

        val result = instantOf(next!!)
        assertEquals(15, result.hour)
        assertEquals(0, result.minute)
    }

    @Test
    fun daily_later_today_is_still_today() {
        val from = at(2026, 9, 4, 6, 0)
        val next = task(Recurrence.DAILY, timeOfDay = LocalTime.of(7, 30))
            .computeNextRun(from.millis(), zone)

        val result = instantOf(next!!)
        assertEquals(4, result.dayOfMonth)
        assertEquals(7, result.hour)
        assertEquals(30, result.minute)
    }

    @Test
    fun daily_after_today_s_time_rolls_to_tomorrow() {
        val from = at(2026, 9, 4, 10, 0)
        val next = task(Recurrence.DAILY, timeOfDay = LocalTime.of(7, 30))
            .computeNextRun(from.millis(), zone)

        val result = instantOf(next!!)
        assertEquals(5, result.dayOfMonth)
        assertEquals(7, result.hour)
    }

    @Test
    fun weekly_lands_on_the_requested_weekday_within_seven_days() {
        val from = at(2026, 9, 4, 12, 0)
        val next = task(
            Recurrence.WEEKLY,
            timeOfDay = LocalTime.of(8, 0),
            daysOfWeek = setOf(DayOfWeek.MONDAY),
        ).computeNextRun(from.millis(), zone)

        val result = instantOf(next!!)
        assertEquals(DayOfWeek.MONDAY, result.dayOfWeek)
        assertEquals(8, result.hour)
        val daysAhead = java.time.Duration.between(from, result).toDays()
        assertTrue("a weekly task is at most 7 days away, was $daysAhead", daysAhead in 1..7)
    }

    /**
     * Day 31 does not exist in September. Firing on the last day of the month is the
     * behaviour the implementation promises; silently skipping the month would mean a
     * "pay the rent on the 31st" reminder vanishing four times a year.
     */
    @Test
    fun monthly_on_the_31st_falls_back_to_the_last_day_of_a_shorter_month() {
        val from = at(2026, 9, 15, 0, 0)
        val next = task(Recurrence.MONTHLY, timeOfDay = LocalTime.of(9, 0), dayOfMonth = 31)
            .computeNextRun(from.millis(), zone)

        val result = instantOf(next!!)
        assertEquals(9, result.monthValue)
        assertEquals(30, result.dayOfMonth)
        assertEquals(
            "must be the month's actual last day",
            result.toLocalDate().lengthOfMonth(),
            result.dayOfMonth,
        )
    }

    @Test
    fun once_in_the_future_fires_at_that_instant() {
        val target = at(2026, 12, 25, 18, 30).millis()
        val next = task(Recurrence.ONCE, oneShotAtEpochMillis = target)
            .computeNextRun(at(2026, 9, 4, 12, 0).millis(), zone)
        assertEquals(target, next)
    }

    /**
     * A missed one-off must not quietly become a recurring task. Returning null is what
     * lets ScheduleService report the missed run instead of inventing a new date.
     */
    @Test
    fun once_in_the_past_returns_null_rather_than_rescheduling_itself() {
        val next = task(Recurrence.ONCE, oneShotAtEpochMillis = at(2026, 1, 1, 9, 0).millis())
            .computeNextRun(at(2026, 9, 4, 12, 0).millis(), zone)
        assertNull("a one-off in the past is done, not rescheduled", next)
    }

    @Test
    fun a_disabled_task_is_never_armed() {
        assertNull(
            task(Recurrence.DAILY, timeOfDay = LocalTime.of(7, 0), enabled = false)
                .computeNextRun(at(2026, 9, 4, 6, 0).millis(), zone),
        )
    }

    @Test
    fun weekly_with_no_weekday_chosen_behaves_like_daily() {
        val from = at(2026, 9, 4, 6, 0)
        val next = task(Recurrence.WEEKLY, timeOfDay = LocalTime.of(7, 0))
            .computeNextRun(from.millis(), zone)
        val result = instantOf(next!!)
        assertEquals("no weekday set means every day is eligible", 4, result.dayOfMonth)
    }

    // ------------------------------------------------------------ notification rules

    private fun rule(
        enabled: Boolean = true,
        packageNames: List<String> = emptyList(),
        titleContains: List<String> = emptyList(),
        bodyContains: List<String> = emptyList(),
        match: RuleMatch = RuleMatch.ALL,
        caseSensitive: Boolean = false,
        cooldownMillis: Long = 0L,
        lastFiredAtEpochMillis: Long? = null,
    ) = NotificationRule(
        id = "rule-test",
        name = "test",
        enabled = enabled,
        packageNames = packageNames,
        titleContains = titleContains,
        bodyContains = bodyContains,
        match = match,
        caseSensitive = caseSensitive,
        request = "tell me about it",
        cooldownMillis = cooldownMillis,
        createdAt = "2026-09-04T00:00:00Z",
        lastFiredAtEpochMillis = lastFiredAtEpochMillis,
        fireCount = 0,
        lastResult = null,
    )

    private fun notification(
        packageName: String = "com.example.shop",
        title: String? = "Order shipped",
        text: String? = "Your parcel arrives tomorrow",
        receivedAt: Long = 1_000L,
    ) = ObservedNotification(packageName, title, text, receivedAt)

    @Test
    fun all_requires_every_condition_to_hold() {
        val both = rule(titleContains = listOf("shipped"), bodyContains = listOf("tomorrow"))
        assertTrue(both.matches(notification()))
        assertFalse(
            "ALL means both, so one match is not enough",
            both.matches(notification(text = "delayed by a week")),
        )
    }

    @Test
    fun any_requires_only_one_condition() {
        val either = rule(
            titleContains = listOf("shipped"),
            bodyContains = listOf("delayed"),
            match = RuleMatch.ANY,
        )
        assertTrue(either.matches(notification()))
        assertTrue(either.matches(notification(title = "Order delayed", text = "sorry")))
        assertFalse(either.matches(notification(title = "Order confirmed", text = "thank you")))
    }

    @Test
    fun the_package_filter_blocks_notifications_from_other_apps() {
        val scoped = rule(packageNames = listOf("com.example.bank"), titleContains = listOf("otp"))
        assertFalse(scoped.matches(notification(packageName = "com.example.shop", title = "otp 1234")))
        assertTrue(scoped.matches(notification(packageName = "com.example.bank", title = "otp 1234")))
    }

    /** A rule scoped to one app and nothing else watches all of that app's traffic. */
    @Test
    fun a_package_only_rule_matches_every_notification_from_that_app() {
        val scoped = rule(packageNames = listOf("com.example.bank"))
        assertTrue(scoped.matches(notification(packageName = "com.example.bank", title = null, text = null)))
        assertFalse(scoped.matches(notification(packageName = "com.other")))
    }

    @Test
    fun case_sensitivity_is_honoured_both_ways() {
        val insensitive = rule(titleContains = listOf("OTP"))
        assertTrue(insensitive.matches(notification(title = "your otp is 4417")))

        val sensitive = rule(titleContains = listOf("OTP"), caseSensitive = true)
        assertFalse(sensitive.matches(notification(title = "your otp is 4417")))
        assertTrue(sensitive.matches(notification(title = "your OTP is 4417")))
    }

    /** Without this a chatty app loops the agent, on battery and on the model budget. */
    @Test
    fun the_cooldown_suppresses_a_second_fire_inside_the_window() {
        val cooled = rule(
            titleContains = listOf("shipped"),
            cooldownMillis = 30 * 60 * 1000L,
            lastFiredAtEpochMillis = 10_000L,
        )
        assertFalse(
            "ten minutes after the last fire is inside a 30 minute cooldown",
            cooled.matches(notification(), nowEpochMillis = 10_000L + 600_000L),
        )
        assertTrue(
            cooled.matches(notification(), nowEpochMillis = 10_000L + 31 * 60 * 1000L),
        )
    }

    @Test
    fun a_disabled_rule_never_matches() {
        assertFalse(
            rule(enabled = false, titleContains = listOf("shipped")).matches(notification()),
        )
    }

    /**
     * Notifications routinely arrive with no title or no text -- ongoing notifications,
     * media sessions. A null there must not become a crash inside a listener service.
     */
    @Test
    fun a_notification_with_no_title_or_body_does_not_crash_the_match() {
        assertFalse(rule(titleContains = listOf("shipped")).matches(notification(title = null, text = null)))
        assertTrue(rule(packageNames = listOf("com.example.shop")).matches(notification(title = null, text = null)))
    }

    @Test
    fun describe_conditions_names_what_the_rule_actually_watches() {
        val description = rule(
            packageNames = listOf("com.example.bank"),
            titleContains = listOf("otp"),
            cooldownMillis = 60_000L,
        ).describeConditions()
        assertTrue(description, description.contains("com.example.bank"))
        assertTrue(description, description.contains("otp"))
        assertTrue(description, description.contains("1 min"))
    }
}

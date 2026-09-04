package com.ngi.sarothi.core.crypto

import com.ngi.sarothi.core.error.IncorrectPasswordException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The backoff that stands between a phone on a desk and the vault inside it.
 *
 * Argon2id makes each guess expensive; this is what stops the guessing. Both of its
 * failure modes are serious and neither is visible in use: a backoff that never engages
 * means the password can be brute-forced at whatever speed the hardware allows, and one
 * that never expires means the owner is locked out of their own memories. The state also
 * has to survive a restart, because a lockout that a force-stop clears is not a lockout.
 */
class LockoutTrackerTest {

    /** Stands in for the Keystore-backed store, which cannot exist on a JVM. */
    private class MemoryStore : LockoutStore {
        val ints = mutableMapOf<String, Int>()
        val longs = mutableMapOf<String, Long>()

        override fun getInt(key: String, fallback: Int): Int = ints[key] ?: fallback
        override fun putInt(key: String, value: Int) {
            ints[key] = value
        }

        override fun getLong(key: String, fallback: Long): Long = longs[key] ?: fallback
        override fun putLong(key: String, value: Long) {
            longs[key] = value
        }
    }

    private val store = MemoryStore()
    private val tracker = LockoutTracker(store)

    /** Fails [count] times from [now], one millisecond apart, and returns the last state. */
    private fun failTimes(count: Int, now: Long = 0L): LockoutTracker.State {
        var state = tracker.state()
        repeat(count) { i -> state = tracker.recordFailure(now + i) }
        return state
    }

    // ------------------------------------------------------------------ the free attempts

    @Test
    fun the_first_three_failures_cost_nothing() {
        repeat(3) { i ->
            val state = tracker.recordFailure(nowMillis = 0L)
            assertNull(
                "failure ${i + 1} of ${tracker.freeAttempts} should not start a backoff",
                state.lockedUntilEpochMillis,
            )
            assertEquals(i + 1, state.failedAttempts)
        }
        assertEquals(3, tracker.state().failedAttempts)
    }

    @Test
    fun the_fourth_failure_starts_the_backoff() {
        val state = failTimes(4)
        assertEquals(
            "the first lockout is the base wait",
            LockoutTracker.BASE_LOCKOUT_MILLIS,
            state.lockedUntilEpochMillis!! - 3L,
        )
        assertEquals(1, state.escalationStep)
    }

    /** Doubling is the whole point: a linear wait is a queue, not a deterrent. */
    @Test
    fun each_further_failure_doubles_the_wait() {
        val waits = (4..8).map { failures ->
            val fresh = LockoutTracker(MemoryStore())
            var state = fresh.state()
            repeat(failures) { i -> state = fresh.recordFailure(nowMillis = 0L + i) }
            state.lockedUntilEpochMillis!! - (failures - 1).toLong()
        }
        assertEquals(
            listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L),
            waits,
        )
    }

    @Test
    fun the_wait_is_capped_and_never_exceeds_it() {
        val state = failTimes(16)
        assertEquals(
            "sixteen failures is where doubling reaches the cap",
            LockoutTracker.MAX_LOCKOUT_MILLIS,
            state.lockedUntilEpochMillis!! - 15L,
        )
    }

    /**
     * The shift is bounded as well as the result, so a long run of failures cannot
     * overflow into a negative wait -- which would read as a lockout that expired
     * before it began.
     */
    @Test
    fun forty_failures_still_produce_the_cap_and_not_an_overflow() {
        val state = failTimes(40)
        val wait = state.lockedUntilEpochMillis!! - 39L
        assertEquals(LockoutTracker.MAX_LOCKOUT_MILLIS, wait)
        assertTrue("a negative or zero wait is no lockout at all: $wait", wait > 0)
    }

    // ------------------------------------------------------------------ the gate itself

    @Test
    fun the_gate_throws_while_the_window_is_open() {
        failTimes(4, now = 1_000L)
        val lockedUntil = tracker.state().lockedUntilEpochMillis!!

        val thrown = try {
            tracker.requireNotLocked(nowMillis = lockedUntil - 1)
            fail("a locked vault must refuse to derive a key")
            null
        } catch (expected: IncorrectPasswordException) {
            expected
        }
        assertEquals(lockedUntil, thrown?.lockoutUntilEpochMillis)
        assertEquals(0, thrown?.attemptsRemaining)
    }

    @Test
    fun the_gate_opens_once_the_window_has_elapsed() {
        failTimes(4, now = 1_000L)
        val lockedUntil = tracker.state().lockedUntilEpochMillis!!

        tracker.requireNotLocked(nowMillis = lockedUntil)
        assertNull(
            "an elapsed window is cleared so the next failure escalates from nothing",
            tracker.state().lockedUntilEpochMillis,
        )
    }

    /**
     * Clearing the window must not clear the count. If it did, an attacker could wait out
     * thirty seconds and start again at thirty seconds, forever, and the doubling would
     * never happen.
     */
    @Test
    fun an_elapsed_window_keeps_the_count_so_the_next_failure_escalates() {
        failTimes(4, now = 0L)
        // Taken from the state rather than written as a literal: the window opens at
        // the fourth failure's own timestamp plus the base wait, and a hardcoded
        // instant that lands a millisecond early would test the wrong branch.
        val lockedUntil = tracker.state().lockedUntilEpochMillis!!
        tracker.requireNotLocked(nowMillis = lockedUntil)

        assertEquals("the count must survive an elapsed window", 4, tracker.state().failedAttempts)

        val escalated = tracker.recordFailure(nowMillis = lockedUntil + 10_000L)
        assertEquals(
            "the fifth failure has to cost a minute, not another thirty seconds",
            60_000L,
            escalated.lockedUntilEpochMillis!! - (lockedUntil + 10_000L),
        )
    }

    @Test
    fun a_success_clears_the_counters_entirely() {
        failTimes(6)
        tracker.recordSuccess()

        assertEquals(LockoutTracker.State.UNLOCKED, tracker.state())
        assertEquals(tracker.freeAttempts, tracker.attemptsRemaining())
    }

    @Test
    fun attempts_remaining_counts_down_and_stops_at_zero() {
        assertEquals(3, tracker.attemptsRemaining())
        tracker.recordFailure(0L)
        assertEquals(2, tracker.attemptsRemaining())
        tracker.recordFailure(0L)
        assertEquals(1, tracker.attemptsRemaining())
        tracker.recordFailure(0L)
        assertEquals(0, tracker.attemptsRemaining())
        tracker.recordFailure(0L)
        assertEquals("it must not go negative", 0, tracker.attemptsRemaining())
    }

    /**
     * The counters live in device storage rather than in memory for one reason: force
     * stopping the app must not reset them. A fresh tracker over the same store is what
     * a process restart looks like from here.
     */
    @Test
    fun the_state_survives_a_new_tracker_over_the_same_store() {
        failTimes(5, now = 1_000L)
        val before = tracker.state()

        val afterRestart = LockoutTracker(store).state()

        assertEquals(before, afterRestart)
        assertEquals(5, afterRestart.failedAttempts)
        assertEquals(before.lockedUntilEpochMillis, afterRestart.lockedUntilEpochMillis)
    }

    @Test
    fun a_fresh_store_starts_unlocked() {
        val fresh = LockoutTracker(MemoryStore())
        assertEquals(LockoutTracker.State.UNLOCKED, fresh.state())
        fresh.requireNotLocked(nowMillis = 0L)
    }
}

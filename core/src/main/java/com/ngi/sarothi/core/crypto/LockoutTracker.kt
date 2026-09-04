package com.ngi.sarothi.core.crypto

import com.ngi.sarothi.core.error.IncorrectPasswordException
import kotlin.math.min

/**
 * Failed-attempt lockout for the master password.
 *
 * Argon2id already makes each guess expensive, but on a device that is sitting on
 * a desk an attacker can simply keep trying. This adds exponential backoff on top:
 * the first [freeAttempts] failures cost nothing, then each further failure
 * doubles the wait, capped at [maxLockoutMillis].
 *
 * State is persisted in the Keystore-backed [SecretStore], so it survives an app
 * restart and cannot be reset by simply force-stopping Sarothi. Clearing app data
 * does reset it — but it also drops the vault folder URI, so the attacker still
 * needs the passphrase plus physical access to the SD card.
 */
/**
 * The four counters [LockoutTracker] persists, as the seam it actually needs.
 *
 * Declared by the consumer rather than by [SecretStore], so the backoff can be tested on
 * a JVM against a map instead of against Android Keystore-backed preferences -- which
 * cannot be created on a machine that has no Keystore, and therefore cannot be tested at
 * all. The brute-force backoff is the one protection that has to keep working across a
 * restart, so it is worth an interface to make it checkable.
 */
interface LockoutStore {
    fun getInt(key: String, fallback: Int = 0): Int
    fun putInt(key: String, value: Int)
    fun getLong(key: String, fallback: Long = 0L): Long
    fun putLong(key: String, value: Long)
}

class LockoutTracker(private val secrets: LockoutStore) {

    data class State(
        val failedAttempts: Int,
        val lockedUntilEpochMillis: Long?,
        val escalationStep: Int,
    ) {
        companion object {
            val UNLOCKED = State(0, null, 0)
        }
    }

    fun state(): State = State(
        failedAttempts = secrets.getInt(SecretStore.KEY_LOCKOUT_FAILURES, 0),
        lockedUntilEpochMillis = secrets.getLong(SecretStore.KEY_LOCKOUT_UNTIL, 0L)
            .takeIf { it > 0L },
        escalationStep = secrets.getInt(SecretStore.KEY_LOCKOUT_ESCALATION, 0),
    )

    /**
     * Blocks (by throwing) while a backoff window is active. Called *before*
     * deriving a key so an attacker cannot use the derivation itself as a timing
     * oracle or burn CPU while locked out.
     */
    fun requireNotLocked(nowMillis: Long = System.currentTimeMillis()) {
        val current = state()
        val lockedUntil = current.lockedUntilEpochMillis ?: return
        if (nowMillis >= lockedUntil) {
            // The window elapsed; keep the failure count so the next failure
            // escalates further rather than restarting the backoff.
            secrets.putLong(SecretStore.KEY_LOCKOUT_UNTIL, 0L)
            return
        }
        throw IncorrectPasswordException(
            attemptsRemaining = 0,
            lockoutUntilEpochMillis = lockedUntil,
        )
    }

    /** Records a failure and applies the next backoff step. Returns the new state. */
    fun recordFailure(nowMillis: Long = System.currentTimeMillis()): State {
        val previous = state()
        val failures = previous.failedAttempts + 1
        secrets.putInt(SecretStore.KEY_LOCKOUT_FAILURES, failures)

        if (failures <= freeAttempts) {
            return previous.copy(failedAttempts = failures)
        }

        val step = previous.escalationStep + 1
        secrets.putInt(SecretStore.KEY_LOCKOUT_ESCALATION, step)
        val lockoutMillis = min(
            baseLockoutMillis shl min(step - 1, MAX_SHIFT),
            maxLockoutMillis,
        )
        val lockedUntil = nowMillis + lockoutMillis
        secrets.putLong(SecretStore.KEY_LOCKOUT_UNTIL, lockedUntil)
        return State(failures, lockedUntil, step)
    }

    /** A successful unlock clears the counters entirely. */
    fun recordSuccess() {
        secrets.putInt(SecretStore.KEY_LOCKOUT_FAILURES, 0)
        secrets.putInt(SecretStore.KEY_LOCKOUT_ESCALATION, 0)
        secrets.putLong(SecretStore.KEY_LOCKOUT_UNTIL, 0L)
    }

    fun attemptsRemaining(): Int = (freeAttempts - state().failedAttempts).coerceAtLeast(0)

    companion object {
        const val FREE_ATTEMPTS_DEFAULT = 3
        const val BASE_LOCKOUT_MILLIS = 30_000L          // 30 s
        const val MAX_LOCKOUT_MILLIS = 24L * 60 * 60 * 1000 // 24 h
        private const val MAX_SHIFT = 20
    }

    val freeAttempts: Int = FREE_ATTEMPTS_DEFAULT
    val baseLockoutMillis: Long = BASE_LOCKOUT_MILLIS
    val maxLockoutMillis: Long = MAX_LOCKOUT_MILLIS
}

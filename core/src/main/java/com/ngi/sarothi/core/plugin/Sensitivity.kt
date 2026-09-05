package com.ngi.sarothi.core.plugin

/**
 * How much an action can hurt.
 *
 * Every plugin declares this for itself — there is no default, because a wrong
 * default is the difference between "reads a page" and "sends money". The safety
 * layer keys its behaviour off it:
 *
 *  - [READ_ONLY] never needs confirmation and is never audited as a change;
 *  - [NORMAL] is audited and runs;
 *  - [SENSITIVE] always stops for confirmation;
 *  - [CRITICAL] stops for confirmation, cannot be auto-approved by a schedule,
 *    and is never remembered as "always allow".
 */
enum class Sensitivity {
    READ_ONLY,
    NORMAL,
    SENSITIVE,
    CRITICAL;

    val requiresConfirmation: Boolean get() = this == SENSITIVE || this == CRITICAL

    /** Rules and schedules may only trigger actions at or below this level. */
    val allowedUnattended: Boolean get() = this == READ_ONLY || this == NORMAL

    companion object {
        fun fromJson(value: String?): Sensitivity? = value?.let { raw ->
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }
    }
}

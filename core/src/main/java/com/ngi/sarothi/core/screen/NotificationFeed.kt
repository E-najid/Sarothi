package com.ngi.sarothi.core.screen

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One notification Sarothi saw, via `typeNotificationStateChanged` accessibility
 * events. This is what the notification-triggered rules engine listens to; it
 * needs no extra permission beyond the accessibility service the user already
 * granted, and it never touches the notification's action buttons.
 */
data class ObservedNotification(
    val packageName: String,
    val title: String?,
    val text: String?,
    val receivedAtEpochMillis: Long,
) {
    /** Stable-ish key so the same notification is not matched by two rules. */
    val fingerprint: String get() = "$packageName|${title ?: ""}|${(text ?: "").take(120)}"
}

/**
 * Publishes notifications to rule evaluators.
 *
 * A `MutableSharedFlow` with replay 0 and `DROP_OLDEST` is deliberate: a busy
 * notification stream must never block the accessibility service thread, and a
 * missed notification is better than an ANR. Rule matching is best-effort by
 * design and says so in the UI.
 */
object NotificationFeed {
    private const val RING_CAPACITY = 100

    private val flow = MutableSharedFlow<ObservedNotification>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    val notifications: SharedFlow<ObservedNotification> = flow.asSharedFlow()

    /**
     * The last [RING_CAPACITY] notifications, newest first.
     *
     * This is what makes a `read_notifications` tool possible without asking for
     * NotificationListenerService: the accessibility service already receives
     * `typeNotificationStateChanged` events, and keeping a bounded ring of them
     * costs a few kilobytes. It is best-effort and says so — a notification that
     * arrived before Sarothi's service was bound is simply not here.
     */
    private val ring = ArrayDeque<ObservedNotification>()

    internal fun publish(notification: ObservedNotification) {
        synchronized(ring) {
            ring.addFirst(notification)
            while (ring.size > RING_CAPACITY) ring.removeLast()
        }
        flow.tryEmit(notification)
    }

    fun recent(limit: Int = 20): List<ObservedNotification> = synchronized(ring) {
        ring.take(limit.coerceAtLeast(0)).toList()
    }

    fun fromPackage(packageName: String, limit: Int = 20): List<ObservedNotification> = synchronized(ring) {
        ring.filter { it.packageName.equals(packageName, ignoreCase = true) }.take(limit).toList()
    }

    /** Drops the ring; called when the vault locks so nothing outlives the session. */
    internal fun clear() = synchronized(ring) { ring.clear() }
}

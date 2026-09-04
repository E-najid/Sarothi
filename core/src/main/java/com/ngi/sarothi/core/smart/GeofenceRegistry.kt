package com.ngi.sarothi.core.smart

import com.ngi.sarothi.core.capability.Notifier

/**
 * Publishes the geofence machinery to components Android creates on its own.
 *
 * [GeofenceWatcherService] is started by the system (and restarted by
 * `START_STICKY`), so it cannot be constructor-injected, and [GeofenceStore] needs
 * the vault — which is only reachable once the app's graph has been built and the
 * user has unlocked. The service checks this registry first and stops itself with
 * an honest notification when nothing is published, rather than watching with no
 * reminders to watch for.
 */
object GeofenceRegistry {
    @Volatile
    private var store: GeofenceStore? = null

    @Volatile
    private var notifierInstance: Notifier? = null

    fun attach(store: GeofenceStore, notifier: Notifier) {
        this.store = store
        this.notifierInstance = notifier
    }

    fun detach(store: GeofenceStore) {
        if (this.store === store) {
            this.store = null
            this.notifierInstance = null
        }
    }

    val current: GeofenceStore? get() = store

    val notifier: Notifier? get() = notifierInstance
}

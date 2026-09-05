package com.ngi.sarothi.core.smart

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.ngi.sarothi.core.R
import com.ngi.sarothi.core.capability.Notifier
import com.ngi.sarothi.core.data.TaskTrigger
import com.ngi.sarothi.core.schedule.AgentRunnerRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches the phone's location and fires geofence reminders.
 *
 * Foreground-typed `location`, and started only while at least one geofence is
 * armed — this is the honest cost of not using Play Services geofencing, which
 * would let the system wake Sarothi without a resident service. On a 3 GB device
 * that resident service matters, so the notification says plainly what is running
 * and Settings can stop it.
 *
 * Everything it needs comes from the registries, because a service Android starts
 * cannot be constructor-injected. When the scheduler or the vault is not available
 * — the normal state after a reboot, before the user has unlocked — it stops
 * itself and says so rather than pretending to watch.
 */
class GeofenceWatcherService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var work: Job? = null
    private var listener: LocationListener? = null

    private val notifier: Notifier?
        get() = GeofenceRegistry.notifier

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopWatching()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNow()
        work?.cancel()
        work = scope.launch { startWatching() }
        return START_STICKY
    }

    override fun onDestroy() {
        stopWatching()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startWatching() {
        val store = GeofenceRegistry.current
        if (store == null) {
            Log.w(TAG, "No geofence store registered; the app graph is not built or the vault is locked")
            notifyWarning(
                "Geofence watching is paused",
                "Open Sarothi and unlock your vault; the watcher starts again once Sarothi can read " +
                    "your reminders.",
            )
            stopSelf()
            return
        }
        val armed = store.enabled()
        if (armed.isEmpty()) {
            Log.i(TAG, "No enabled geofences; stopping the watcher")
            stopSelf()
            return
        }

        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            notifyWarning("Geofence watching stopped", "This device exposes no location service.")
            stopSelf()
            return
        }
        if (!locationEnabled(manager)) {
            notifyWarning(
                "Geofence watching is paused",
                "Location is turned off on this phone. Turn it on and Sarothi will start watching " +
                    "${armed.size} place(s) again.",
            )
            stopSelf()
            return
        }

        // Checked here rather than left to the caller: permissions can be revoked while
        // this service is running, and requestLocationUpdates throws SecurityException
        // without one. The runCatching below would swallow that into a generic failure,
        // which tells the user nothing about what to do. Stopping with a specific
        // notification is the honest outcome, and it is the shape every other guard in
        // this method already uses.
        val locationGranted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!locationGranted) {
            notifyWarning(
                "Geofence watching stopped",
                "Sarothi no longer has permission to read this phone's location, so it cannot tell " +
                    "when you arrive at one of your ${armed.size} place(s). Allow Location for " +
                    "Sarothi in Android settings and it will start watching again.",
            )
            stopSelf()
            return
        }

        val provider = chooseProvider(manager)
        if (provider == null) {
            notifyWarning(
                "Geofence watching stopped",
                "Neither GPS nor network location is available on this phone, so Sarothi cannot tell " +
                    "where it is.",
            )
            stopSelf()
            return
        }

        val minIntervalMillis = intervalFor(armed)
        Log.i(TAG, "Watching ${armed.size} geofence(s) via $provider every ${minIntervalMillis / 1000}s")
        updateOngoingNotification(armed.size, provider)

        val newListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                scope.launch { onFix(store, location) }
            }

            override fun onProviderDisabled(providerName: String) {
                Log.i(TAG, "Provider $providerName disabled")
                notifyWarning(
                    "Geofence watching is paused",
                    "The $providerName location provider was turned off.",
                )
                stopSelf()
            }

            override fun onProviderEnabled(providerName: String) = Unit

            @Deprecated("Required by the interface on older API levels")
            override fun onStatusChanged(providerName: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = newListener

        val granted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.requestLocationUpdates(provider, minIntervalMillis, MIN_DISTANCE_METRES, newListener)
            } else {
                @Suppress("DEPRECATION")
                manager.requestLocationUpdates(
                    provider, minIntervalMillis, MIN_DISTANCE_METRES, newListener, null,
                )
            }
            true
        }.getOrElse { failure ->
            Log.w(TAG, "requestLocationUpdates refused", failure)
            notifyWarning(
                "Geofence watching stopped",
                "Android refused location updates: ${failure.javaClass.simpleName}. Sarothi needs the " +
                    "Location permission and, on Android 12+, 'Allow all the time' for background fixes.",
            )
            false
        }
        if (!granted) stopSelf()
    }

    private suspend fun onFix(store: GeofenceStore, location: Location) {
        val evaluations = runCatching {
            store.evaluate(location.latitude, location.longitude)
        }.getOrElse { failure ->
            Log.w(TAG, "Geofence evaluation failed", failure)
            return
        }
        val fired = evaluations.filter { it.crossed }
        evaluations.filter { it.blockedByCooldown }.forEach { evaluation ->
            Log.i(
                TAG,
                "Crossed ${evaluation.reminder.label} but it is in cooldown " +
                    "(${evaluation.distanceMetres.toInt()} m away)",
            )
        }
        for (evaluation in fired) {
            val reminder = evaluation.reminder
            val direction = evaluation.direction?.name?.lowercase() ?: "boundary"
            Log.i(TAG, "Geofence fired: ${reminder.label} ($direction)")
            val message = if (reminder.runAgent) {
                runAgentRequest(reminder, direction)
            } else {
                "You ${if (direction == "enter") "arrived at" else "left"} ${reminder.label}."
            }
            notifyResult(reminder, direction, message)
        }
        if (fired.isEmpty() && evaluations.isNotEmpty()) {
            val nearest = evaluations.minByOrNull { it.distanceMetres }
            nearest?.let {
                updateOngoingNotification(
                    armedCount = evaluations.size,
                    provider = listener?.let { _ -> "location" } ?: "location",
                    detail = "${it.reminder.label}: ${it.distanceMetres.toInt()} m away",
                )
            }
        }
    }

    /**
     * Runs a geofence's request through the agent, unattended.
     *
     * Sensitive steps are refused by the safety gate exactly as they are for a
     * schedule: nobody is watching, so there is nobody to confirm with.
     */
    private suspend fun runAgentRequest(reminder: GeofenceReminder, direction: String): String {
        val runner = AgentRunnerRegistry.current
            ?: return "You ${if (direction == "enter") "arrived at" else "left"} ${reminder.label}. " +
                "Sarothi could not run the follow-up because the agent is not initialised — open " +
                "Sarothi once and it will work next time."
        val outcome = runCatching {
            runner.runUnattended(reminder.request, TaskTrigger.NOTIFICATION_RULE, allowSensitiveSteps = false)
        }.getOrElse { failure ->
            return "You ${if (direction == "enter") "arrived at" else "left"} ${reminder.label}, but the " +
                "follow-up failed: ${failure.javaClass.simpleName}: ${failure.message}"
        }
        return outcome.message.ifBlank {
            "You ${if (direction == "enter") "arrived at" else "left"} ${reminder.label}."
        }
    }

    private fun stopWatching() {
        listener?.let { registered ->
            runCatching {
                (getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                    ?.removeUpdates(registered)
            }
        }
        listener = null
    }

    /**
     * Whether any usable location provider is switched on.
     *
     * `FUSED_PROVIDER` only exists from API 31 and asking about it earlier throws for an
     * unknown provider, so it carries the same guard [chooseProvider] gives it instead of
     * being left for the runCatching to swallow -- which would report "location off" for
     * the wrong reason and hide a genuine failure underneath.
     */
    private fun locationEnabled(manager: LocationManager): Boolean = runCatching {
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                manager.isProviderEnabled(LocationManager.FUSED_PROVIDER))
    }.getOrDefault(false)

    /**
     * GPS when it is on (accurate enough for a 150 m circle), otherwise network.
     *
     * `FUSED_PROVIDER` is preferred where present because it switches for us, but
     * it only exists from API 31 and is not on every device even then.
     */
    private fun chooseProvider(manager: LocationManager): String? = runCatching {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
    }.getOrNull()

    /**
     * Fix interval from the smallest radius in play.
     *
     * A 20 m circle needs frequent fixes or the phone will step straight over it
     * between samples; a 5 km one does not, and polling it every 30 seconds would
     * be a battery disaster on a 3 GB phone.
     */
    private fun intervalFor(reminders: List<GeofenceReminder>): Long {
        val smallest = reminders.minOf { it.radiusMetres }
        return when {
            smallest <= 50.0 -> 30_000L
            smallest <= 200.0 -> 60_000L
            smallest <= 1000.0 -> 3 * 60_000L
            else -> 10 * 60_000L
        }
    }

    // --- foreground notification --------------------------------------------

    private fun startForegroundNow() {
        val notification = buildNotification("Watching for your places", "Reading your location.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateOngoingNotification(armedCount: Int, provider: String, detail: String? = null) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val text = buildString {
            append(armedCount).append(" place(s) armed via ").append(provider)
            detail?.let { append(" · ").append(it) }
            append(". Tap to stop watching.")
        }
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification("Watching for your places", text)) }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, GeofenceWatcherService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, GeofenceWatcherService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_sarothi_status)
            .setOngoing(true)
            .setContentIntent(openIntent)
        // Notification.Action.Builder is API 20 and minSdk is 26, so the Stop action is
        // always added; guarding it meant a wearable-only code path in a phone app.
        builder.addAction(
            Notification.Action.Builder(null, "Stop watching", stopIntent).build(),
        )
        return builder.build()
    }

    private fun notifyWarning(title: String, text: String) {
        notifier?.warning(TAG, title, text) ?: fallbackNotify(title, text)
    }

    private fun notifyResult(reminder: GeofenceReminder, direction: String, message: String) {
        val title = if (direction == "enter") "Arrived: ${reminder.label}" else "Left: ${reminder.label}"
        notifier?.info("geo-${reminder.id}", title, message) ?: fallbackNotify(title, message)
    }

    private fun fallbackNotify(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            manager.notify(
                RESULT_NOTIFICATION_ID,
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_sarothi_status)
                    .build(),
            )
        }
    }

    private fun createChannel() {
        // NotificationChannel is API 26 and minSdk is 26.
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Location watching",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while Sarothi is watching for the places you set reminders on."
            setShowBadge(false)
        }
        runCatching { manager.createNotificationChannel(channel) }
    }

    companion object {
        private const val TAG = "SarothiGeofence"
        const val ACTION_START = "com.ngi.sarothi.core.smart.action.GEOFENCE_START"
        const val ACTION_STOP = "com.ngi.sarothi.core.smart.action.GEOFENCE_STOP"
        private const val CHANNEL_ID = "sarothi_geofence"
        private const val NOTIFICATION_ID = 4210
        private const val RESULT_NOTIFICATION_ID = 4211
        private const val MIN_DISTANCE_METRES = 10f

        /** Starts the watcher, or restarts it so it picks up changed reminders. */
        fun sync(context: Context) {
            val intent = Intent(context, GeofenceWatcherService::class.java).setAction(ACTION_START)
            runCatching {
                // startForegroundService is API 26 and minSdk is 26.
                context.startForegroundService(intent)
            }.onFailure { failure ->
                Log.w(TAG, "Could not start the geofence watcher", failure)
            }
        }

        /** Stops the watcher; called when the last geofence is deleted or disabled. */
        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, GeofenceWatcherService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}

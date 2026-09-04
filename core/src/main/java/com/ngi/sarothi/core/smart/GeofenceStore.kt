package com.ngi.sarothi.core.smart

import com.ngi.sarothi.core.data.VaultJsonCollection
import com.ngi.sarothi.core.storage.VaultManager
import com.ngi.sarothi.core.storage.VaultPaths
import com.ngi.sarothi.core.util.Ids
import java.time.Instant

/**
 * Geofence reminders, in the encrypted vault.
 *
 * In the vault rather than in device-local storage for the same reason schedules
 * are: they are the user's data, they travel with the SD card, and a fresh install
 * that restores the vault gets its reminders back.
 */
class GeofenceStore(private val vault: VaultManager) {

    private val collection = VaultJsonCollection(
        vault = vault,
        path = VaultPaths.GEOFENCES,
        arrayKey = "geofences",
        toItem = { GeofenceReminder.fromJson(it) },
        fromItem = { it.toJson() },
    )

    suspend fun all(): List<GeofenceReminder> = collection.snapshot()

    suspend fun enabled(): List<GeofenceReminder> = collection.read { items ->
        items.filter { it.enabled }
    }

    suspend fun byId(id: String): GeofenceReminder? = collection.read { items ->
        items.firstOrNull { it.id == id }
    }

    suspend fun create(
        label: String,
        latitude: Double,
        longitude: Double,
        radiusMetres: Double,
        trigger: GeofenceTrigger,
        request: String,
        runAgent: Boolean,
        cooldownMillis: Long,
    ): GeofenceReminder = collection.mutate { items ->
        require(request.isNotBlank()) { "A geofence needs something for Sarothi to do" }
        require(latitude in -90.0..90.0) { "Latitude has to be between -90 and 90" }
        require(longitude in -180.0..180.0) { "Longitude has to be between -180 and 180" }
        require(radiusMetres in 20.0..GeofenceReminder.MAX_RADIUS_METRES) {
            "Radius has to be between 20 m and ${GeofenceReminder.MAX_RADIUS_METRES.toInt()} m"
        }
        val reminder = GeofenceReminder(
            id = Ids.newId("geo"),
            label = label.trim().ifBlank { request.take(40) },
            latitude = latitude,
            longitude = longitude,
            radiusMetres = radiusMetres,
            trigger = trigger,
            request = request.trim(),
            runAgent = runAgent,
            enabled = true,
            createdAt = Instant.now().toString(),
            lastTriggeredAtEpochMillis = null,
            triggerCount = 0,
            cooldownMillis = cooldownMillis.coerceAtLeast(0L),
            lastKnownInside = null,
        )
        items += reminder
        reminder
    }

    suspend fun delete(id: String): Boolean = collection.mutate { items ->
        val before = items.size
        items.removeAll { it.id == id }
        items.size < before
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Boolean = collection.mutate { items ->
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@mutate false
        items[index] = items[index].copy(enabled = enabled)
        true
    }

    /**
     * Records one fix against every reminder and returns the crossings to act on.
     *
     * Also persists the new inside/outside state, which is what stops a restart
     * from looking like a fresh entry into every circle. A reminder in cooldown is
     * reported with [GeofenceEvaluation.blockedByCooldown] = true rather than
     * silently dropped, so the caller can log that it saw the crossing.
     */
    suspend fun evaluate(
        latitude: Double,
        longitude: Double,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): List<GeofenceEvaluation> = collection.mutate { items ->
        val evaluations = mutableListOf<GeofenceEvaluation>()
        for (index in items.indices) {
            val reminder = items[index]
            if (!reminder.enabled) continue
            val distance = GeofenceReminder.distanceMetres(latitude, longitude, reminder.latitude, reminder.longitude)
            val inside = distance <= reminder.radiusMetres
            val wasInside = reminder.lastKnownInside
            val entered = inside && wasInside == false
            val exited = !inside && wasInside == true
            val crossed = when (reminder.trigger) {
                GeofenceTrigger.ENTER -> entered
                GeofenceTrigger.EXIT -> exited
                GeofenceTrigger.BOTH -> entered || exited
            }
            val direction = when {
                !crossed -> null
                entered -> GeofenceTrigger.ENTER
                else -> GeofenceTrigger.EXIT
            }
            val inCooldown = reminder.lastTriggeredAtEpochMillis != null &&
                nowEpochMillis - reminder.lastTriggeredAtEpochMillis < reminder.cooldownMillis

            // The state has to be written back whether or not the crossing is acted
            // on: a crossing suppressed by cooldown is still a crossing, and the
            // next fix must compare against this one.
            items[index] = reminder.copy(
                lastKnownInside = inside,
                lastTriggeredAtEpochMillis = if (crossed && !inCooldown) nowEpochMillis
                else reminder.lastTriggeredAtEpochMillis,
                triggerCount = if (crossed && !inCooldown) reminder.triggerCount + 1 else reminder.triggerCount,
            )

            evaluations += GeofenceEvaluation(
                reminder = items[index],
                inside = inside,
                wasInside = wasInside ?: inside,
                distanceMetres = distance,
                crossed = crossed && !inCooldown,
                direction = direction,
                blockedByCooldown = crossed && inCooldown,
            )
        }
        evaluations
    }

    suspend fun count(): Int = collection.size()
}

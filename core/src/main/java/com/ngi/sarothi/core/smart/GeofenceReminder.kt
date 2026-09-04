package com.ngi.sarothi.core.smart

import com.google.gson.JsonObject
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What crossing the boundary should do. */
enum class GeofenceTrigger { ENTER, EXIT, BOTH }

/**
 * A place Sarothi should react to arriving at or leaving.
 *
 * Deliberately not Google Play Services geofencing: `GeofencingClient` needs Play
 * Services, which a large share of the 3 GB devices Sarothi targets do not have or
 * do not keep updated, and a reminder that silently never fires is worse than one
 * Sarothi computes itself. The trade is honest and stated in the plugin: this
 * watcher needs a foreground service and therefore uses battery while it is armed.
 */
data class GeofenceReminder(
    val id: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMetres: Double,
    val trigger: GeofenceTrigger,
    /** What Sarothi should do on the crossing, as if the user had asked. */
    val request: String,
    /** False means only notify; true also runs [request] through the agent. */
    val runAgent: Boolean,
    val enabled: Boolean,
    val createdAt: String,
    val lastTriggeredAtEpochMillis: Long?,
    val triggerCount: Int,
    /** Minimum gap between two firings, so a boundary being crossed repeatedly cannot loop. */
    val cooldownMillis: Long,
    /**
     * Whether the last fix was inside the circle, or null before the first fix.
     *
     * A crossing is a *change* in this value, which is why it has to be persisted:
     * the watcher service is stopped and started with the phone, and without the
     * previous state every restart would look like a fresh entry into every circle.
     */
    val lastKnownInside: Boolean? = null,
) {
    fun toJson(): JsonObject = Json.obj {
        addProperty("id", id)
        addProperty("label", label)
        addProperty("latitude", latitude)
        addProperty("longitude", longitude)
        addProperty("radius_metres", radiusMetres)
        addProperty("trigger", trigger.name.lowercase())
        addProperty("request", request)
        addProperty("run_agent", runAgent)
        addProperty("enabled", enabled)
        addProperty("created_at", createdAt)
        lastTriggeredAtEpochMillis?.let { addProperty("last_triggered_at", it) }
        addProperty("trigger_count", triggerCount)
        addProperty("cooldown_millis", cooldownMillis)
        lastKnownInside?.let { addProperty("last_known_inside", it) }
    }

    companion object {
        const val DEFAULT_COOLDOWN_MILLIS = 15 * 60 * 1000L
        const val DEFAULT_RADIUS_METRES = 150.0
        const val MAX_RADIUS_METRES = 50_000.0

        fun fromJson(json: JsonObject): GeofenceReminder? {
            val id = json.stringOrNull("id") ?: return null
            val request = json.stringOrNull("request") ?: return null
            val latitude = json.get("latitude")?.takeIf { it.isJsonPrimitive }?.asDouble ?: return null
            val longitude = json.get("longitude")?.takeIf { it.isJsonPrimitive }?.asDouble ?: return null
            return GeofenceReminder(
                id = id,
                label = json.stringOrNull("label") ?: request.take(40),
                latitude = latitude,
                longitude = longitude,
                radiusMetres = json.get("radius_metres")?.takeIf { it.isJsonPrimitive }?.asDouble
                    ?: DEFAULT_RADIUS_METRES,
                trigger = json.stringOrNull("trigger")?.let { raw ->
                    GeofenceTrigger.entries.firstOrNull { it.name.equals(raw, true) }
                } ?: GeofenceTrigger.ENTER,
                request = request,
                runAgent = json.get("run_agent")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                enabled = json.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
                createdAt = json.stringOrNull("created_at") ?: "",
                lastTriggeredAtEpochMillis = json.get("last_triggered_at")
                    ?.takeIf { it.isJsonPrimitive }?.asLong,
                triggerCount = json.get("trigger_count")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                cooldownMillis = json.get("cooldown_millis")?.takeIf { it.isJsonPrimitive }?.asLong
                    ?: DEFAULT_COOLDOWN_MILLIS,
                lastKnownInside = json.get("last_known_inside")?.takeIf { it.isJsonPrimitive }?.asBoolean,
            )
        }

        /**
         * Great-circle distance in metres, haversine.
         *
         * Written out rather than pulled from a library: it is eight lines, it is
         * the formula everyone uses, and having it here means the geofence has no
         * dependency that could quietly change its meaning.
         */
        fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val radius = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            return radius * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}

/** One evaluation of one reminder against one fix. */
data class GeofenceEvaluation(
    val reminder: GeofenceReminder,
    val inside: Boolean,
    val wasInside: Boolean,
    val distanceMetres: Double,
    val crossed: Boolean,
    val direction: GeofenceTrigger?,
    val blockedByCooldown: Boolean,
)

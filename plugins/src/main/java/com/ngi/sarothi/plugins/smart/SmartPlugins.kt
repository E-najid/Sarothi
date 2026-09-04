package com.ngi.sarothi.plugins.smart

import android.Manifest
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ngi.sarothi.core.plugin.JsonSchema
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.core.plugin.PluginAvailability
import com.ngi.sarothi.core.plugin.PluginCategory
import com.ngi.sarothi.core.plugin.PluginContext
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.plugin.pluginContext
import com.ngi.sarothi.core.plugin.ConfirmationPreview
import com.ngi.sarothi.core.safety.ConfirmationReason
import com.ngi.sarothi.core.smart.GeofenceRegistry
import com.ngi.sarothi.core.smart.GeofenceReminder
import com.ngi.sarothi.core.smart.GeofenceTrigger
import com.ngi.sarothi.core.smart.GeofenceWatcherService
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import com.ngi.sarothi.plugins.common.doubleOrAsk
import com.ngi.sarothi.plugins.common.textOrAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home Assistant control.
 *
 * Talks to the user's own Home Assistant over its REST API with a long-lived
 * access token they paste in Settings → Connectors. The token goes to
 * [com.ngi.sarothi.core.crypto.SecretStore] — device-local, Keystore-backed, never
 * on the SD card — while the non-secret base URL lives in the plugin's vault
 * config so it travels with a restored vault.
 *
 * Turning a light on is NORMAL; anything that unlocks a door, opens a gate or
 * disarms an alarm is CRITICAL and always confirmed, because "Sarothi let someone
 * in" is not a mistake to make quietly.
 */
class HomeAssistantPlugin : Plugin {
    override val name = "home_assistant"
    override val description =
        "Control a Home Assistant server on your own network: list entities, read one entity's state, " +
            "or call a service (light.turn_on, switch.turn_off, climate.set_temperature). Needs the " +
            "server address and a long-lived access token added in Settings → Connectors → Home " +
            "Assistant. Anything that unlocks a door or disarms an alarm is confirmed first."
    override val category = PluginCategory.SMART_HOME
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "action" to JsonSchema.Property.Text("What to do.", enum = listOf("list_entities", "get_state", "call_service")),
            "entity_id" to JsonSchema.Property.Text("The entity, e.g. 'light.living_room' or 'domain.name' to list a whole domain."),
            "service" to JsonSchema.Property.Text("Service to call, e.g. 'light.turn_on'."),
            "service_data" to JsonSchema.Property.Record("Service fields, e.g. {\"brightness_pct\": 60}.", fields = JsonSchema(properties = emptyMap())),
            "domain" to JsonSchema.Property.Text("Only entities in this domain, e.g. 'light' or 'lock'."),
        ),
        required = listOf("action"),
    )

    override val example = """{"action":"call_service","service":"light.turn_on","entity_id":"light.living_room","service_data":{"brightness_pct":60}}"""

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val url = context.config.string(CONFIG_BASE_URL)
        val token = context.secrets.getString(SECRET_TOKEN_KEY)
        return when {
            !context.network.isOnline() -> PluginAvailability.unavailable(
                reason = "Home Assistant is reached over the network and this phone is offline.",
                fixAction = "Connect to the same Wi-Fi as your Home Assistant server.",
            )
            url.isNullOrBlank() -> PluginAvailability.unavailable(
                reason = "No Home Assistant server address is configured.",
                fixAction = "Settings → Connectors → Home Assistant, and enter your server's address, " +
                    "e.g. http://homeassistant.local:8123",
            )
            token.isNullOrBlank() -> PluginAvailability.unavailable(
                reason = "No Home Assistant access token is saved on this device.",
                fixAction = "In Home Assistant: your profile → Long-Lived Access Tokens → create one, " +
                    "then paste it in Settings → Connectors → Home Assistant.",
            )
            else -> PluginAvailability.READY
        }
    }

    override fun describeForConfirmation(params: JsonObject): ConfirmationPreview? {
        val action = params.stringOrNull("action") ?: return null
        if (action != "call_service") return null
        val service = params.stringOrNull("service") ?: "(no service)"
        val entityId = params.stringOrNull("entity_id") ?: "(no entity)"
        val sensitive = SENSITIVE_DOMAINS.any { domain ->
            entityId.startsWith("$domain.") || service.startsWith("$domain.")
        }
        if (!sensitive) return null
        return ConfirmationPreview(
            title = "Confirm this home action",
            detailLines = listOf(
                "Service: $service",
                "Entity: $entityId",
                "Data: " + (params.get("service_data")?.takeIf { it.isJsonObject }?.toString() ?: "{}"),
                "This controls a lock, alarm or garage — the kind of thing that should never happen " +
                    "on a guess.",
            ),
            reason = ConfirmationReason.DESTRUCTIVE_SYSTEM_ACTION,
            allowRemember = false,
        )
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val action = params.textOrAsk("action", "What should Sarothi do with Home Assistant?")
        val baseUrl = context.config.string(CONFIG_BASE_URL)
            ?: return PluginResult.Unavailable(availability(context))
        val token = context.secrets.getString(SECRET_TOKEN_KEY)
            ?: return PluginResult.Unavailable(availability(context))

        val root = baseUrl.trimEnd('/')
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/json",
        )

        return when (action) {
            "list_entities" -> {
                val domain = params.stringOrNull("domain")?.takeIf { it.isNotBlank() }
                val url = "$root/api/states"
                val response = get(context, url, headers) ?: return transportFailure(url)
                if (!response.isSuccess) return httpFailure(action, response.statusCode, response.bodyText())
                val states = runCatching { JsonParser.parseString(response.bodyText()).asJsonArray }
                    .getOrElse { return malformedFailure(response.bodyText()) }
                val filtered = states.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
                    .filter { entity ->
                        domain == null || entity.stringOrNull("entity_id")?.startsWith("$domain.") == true
                    }
                PluginResult.Success(
                    summaryForUser = "${filtered.size} Home Assistant entit" +
                        (if (filtered.size == 1) "y" else "ies") +
                        if (domain != null) " in domain '$domain'" else "" +
                        (if (filtered.isNotEmpty()) ": " + filtered.take(6).joinToString {
                            "${it.stringOrNull("entity_id")}=${it.stringOrNull("state")}"
                        } else ""),
                    data = Json.obj {
                        add("entities", Json.arr {
                            filtered.take(MAX_ENTITIES).forEach { entity ->
                                add(Json.obj {
                                    addProperty("entity_id", entity.stringOrNull("entity_id") ?: "")
                                    addProperty("state", entity.stringOrNull("state") ?: "")
                                    entity.getAsJsonObject("attributes")?.stringOrNull("friendly_name")
                                        ?.let { addProperty("name", it) }
                                    entity.stringOrNull("last_changed")?.let { addProperty("last_changed", it) }
                                })
                            }
                        })
                        addProperty("count", filtered.size)
                        if (filtered.size > MAX_ENTITIES) addProperty("truncated_to", MAX_ENTITIES)
                    },
                )
            }

            "get_state" -> {
                val entityId = params.textOrAsk(
                    "entity_id",
                    "Which Home Assistant entity should Sarothi read?",
                )
                val url = "$root/api/states/$entityId"
                val response = get(context, url, headers) ?: return transportFailure(url)
                if (!response.isSuccess) return httpFailure(action, response.statusCode, response.bodyText())
                val entity = runCatching { JsonParser.parseString(response.bodyText()).asJsonObject }
                    .getOrElse { return malformedFailure(response.bodyText()) }
                val attributes = entity.getAsJsonObject("attributes")
                PluginResult.Success(
                    summaryForUser = "${attributes?.stringOrNull("friendly_name") ?: entityId} is " +
                        "${entity.stringOrNull("state")} " +
                        (attributes?.stringOrNull("unit_of_measurement")?.let { " $it" } ?: "") +
                        " (last changed ${entity.stringOrNull("last_changed")}).",
                    data = Json.obj {
                        addProperty("entity_id", entity.stringOrNull("entity_id") ?: entityId)
                        addProperty("state", entity.stringOrNull("state") ?: "")
                        addProperty("last_changed", entity.stringOrNull("last_changed") ?: "")
                        if (attributes != null) add("attributes", attributes)
                    },
                )
            }

            "call_service" -> {
                val service = params.textOrAsk(
                    "service",
                    "Which Home Assistant service should Sarothi call, e.g. light.turn_on?",
                )
                val parts = service.split('.')
                if (parts.size != 2 || parts.any { it.isBlank() }) {
                    return PluginResult.Failure(
                        summaryForUser = "\"$service\" is not a Home Assistant service name. They look " +
                            "like 'light.turn_on' — a domain, a dot, then the service.",
                        errorClass = "InvalidServiceException",
                        retriable = true,
                    )
                }
                val serviceDomain = parts[0]
                val serviceName = parts[1]
                val serviceData = JsonObject().apply {
                    params.get("service_data")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.entrySet()?.forEach { (key, value) -> add(key, value) }
                    params.stringOrNull("entity_id")?.takeIf { it.isNotBlank() }
                        ?.let { addProperty("entity_id", it) }
                }
                val url = "$root/api/services/$serviceDomain/$serviceName"
                val response = post(context, url, serviceData.toString(), headers) ?: return transportFailure(url)
                if (!response.isSuccess) return httpFailure(action, response.statusCode, response.bodyText())
                val changed = runCatching { JsonParser.parseString(response.bodyText()).asJsonArray }
                    .getOrNull()
                val count = changed?.size() ?: 0
                PluginResult.Success(
                    summaryForUser = if (count == 0) {
                        "Home Assistant accepted '$service' but reported no entity changed. The service " +
                            "may not apply to that entity, or it was already in that state."
                    } else {
                        "Called $service; $count entit" + (if (count == 1) "y" else "ies") + " changed: " +
                            (changed?.mapNotNull { element ->
                                if (!element.isJsonObject) return@mapNotNull null
                                element.asJsonObject.stringOrNull("entity_id")
                            }?.take(6)?.joinToString() ?: "")
                    },
                    data = Json.obj {
                        addProperty("service", service)
                        addProperty("entities_changed", count)
                        add("service_data", serviceData)
                        if (changed != null) add("result", changed)
                    },
                    spoken = "কাজটি করে দিয়েছি।",
                )
            }

            else -> PluginResult.Failure(
                summaryForUser = "\"$action\" is not a Home Assistant action Sarothi supports. It can do: " +
                    "list_entities, get_state, call_service.",
                errorClass = "UnknownActionException",
                retriable = true,
            )
        }
    }

    private suspend fun get(
        context: PluginContext,
        url: String,
        headers: Map<String, String>,
    ): com.ngi.sarothi.core.net.HttpClient.Response? = withContext(Dispatchers.IO) {
        runCatching { context.http.get(url, headers) }.getOrNull()
    }

    private suspend fun post(
        context: PluginContext,
        url: String,
        body: String,
        headers: Map<String, String>,
    ): com.ngi.sarothi.core.net.HttpClient.Response? = withContext(Dispatchers.IO) {
        runCatching {
            context.http.post(url, body.toByteArray(Charsets.UTF_8), "application/json", headers)
        }.getOrNull()
    }

    private fun transportFailure(url: String) = PluginResult.Failure(
        summaryForUser = "Sarothi could not reach ${url.substringBefore("/api/")}. Is the phone on the " +
            "same network as your Home Assistant server, and is the address right?",
        errorClass = "UnreachableException",
        retriable = true,
    )

    private fun malformedFailure(body: String) = PluginResult.Failure(
        summaryForUser = "Home Assistant answered with something that is not JSON " +
            "(${body.take(120)}). Check the address in Settings → Connectors.",
        errorClass = "MalformedResponseException",
        retriable = false,
    )

    private fun httpFailure(action: String, status: Int, body: String): PluginResult {
        val message = runCatching { JsonParser.parseString(body).asJsonObject.stringOrNull("message") }.getOrNull()
        return when (status) {
            401 -> PluginResult.Unavailable(
                PluginAvailability.unavailable(
                    reason = "Home Assistant rejected the saved token (401). Long-lived tokens expire " +
                        "when you delete them in your profile.",
                    fixAction = "Settings → Connectors → Home Assistant and paste a fresh token.",
                ),
            )
            404 -> PluginResult.Failure(
                summaryForUser = "Home Assistant has no such resource (404): ${message ?: "not found"}. " +
                    "Check the entity id or service name with list_entities.",
                errorClass = "NotFoundException",
                retriable = true,
            )
            else -> PluginResult.Failure(
                summaryForUser = "Home Assistant answered HTTP $status for '$action': ${message ?: body.take(200)}",
                errorClass = "HttpErrorException",
                retriable = status in 500..599,
            )
        }
    }

    private companion object {
        const val CONFIG_BASE_URL = "base_url"
        const val SECRET_TOKEN_KEY = "connector.homeassistant.token"
        const val MAX_ENTITIES = 60
        /**
         * Domains whose services change physical access to a home. Never NORMAL.
         */
        val SENSITIVE_DOMAINS = setOf("lock", "alarm_control_panel", "cover", "garage_door", "switch")
    }
}

/**
 * Location-based reminders.
 *
 * Backed by Sarothi's own watcher service rather than Play Services geofencing —
 * see [GeofenceWatcherService] for why, and what it costs.
 */
class GeofenceReminderPlugin : Plugin {
    override val name = "geofence_reminder"
    override val description =
        "Remind the user when they arrive at or leave a place, e.g. 'when I get home, remind me to pay " +
            "the bill'. Needs a latitude and longitude — Sarothi will not guess coordinates for a place " +
            "name. Watching uses a foreground location service, which the user is told about and can " +
            "stop from the notification."
    override val category = PluginCategory.SMART_HOME
    override val sensitivity = Sensitivity.NORMAL
    override val supportsUndo = true
    override val requiredPermissions = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    )

    override val parameters = JsonSchema(
        properties = mapOf(
            "action" to JsonSchema.Property.Text("What to do.", enum = listOf("add", "list", "delete", "stop_watching"), default = "add"),
            "label" to JsonSchema.Property.Text("A short name for the place, e.g. 'home'."),
            "latitude" to JsonSchema.Property.Number("Latitude, -90 to 90. Never guessed.", minimum = -90.0, maximum = 90.0),
            "longitude" to JsonSchema.Property.Number("Longitude, -180 to 180. Never guessed.", minimum = -180.0, maximum = 180.0),
            "radius_metres" to JsonSchema.Property.Number("Circle radius in metres.", minimum = 20.0, maximum = 50000.0, default = 150.0),
            "trigger" to JsonSchema.Property.Text("Fire on arriving, leaving, or both.", enum = listOf("enter", "exit", "both"), default = "enter"),
            "request" to JsonSchema.Property.Text("What Sarothi should do on the crossing, as if the user had asked."),
            "run_agent" to JsonSchema.Property.Flag("Also run that request through Sarothi's agent, not just notify.", default = false),
            "geofence_id" to JsonSchema.Property.Text("For delete: the id from action=list."),
            "cooldown_minutes" to JsonSchema.Property.Integer("Minimum minutes between two firings.", minimum = 0, maximum = 1440, default = 15),
        ),
    )

    override val example =
        """{"action":"add","label":"home","latitude":24.3745,"longitude":91.4158,"trigger":"enter","request":"Remind me to take the medicine"}"""

    override suspend fun availability(context: PluginContext): PluginAvailability {
        if (GeofenceRegistry.current == null) {
            return PluginAvailability.unavailable(
                reason = "Sarothi's vault is locked, so its reminders cannot be read or written.",
                fixAction = "Ask the user to unlock Sarothi's vault.",
            )
        }
        val verdict = context.guard.verdictFor(this)
        return if (verdict.allowed) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                reason = verdict.explanation,
                fixAction = "Grant Location, and on Android 10+ choose 'Allow all the time' — a geofence " +
                    "cannot work with foreground-only location.",
            )
        }
    }

    override fun describeForConfirmation(params: JsonObject): ConfirmationPreview? {
        val action = params.stringOrNull("action") ?: "add"
        if (action != "add") return null
        val runAgent = params.get("run_agent")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        if (!runAgent) return null
        return ConfirmationPreview(
            title = "Let Sarothi act when you arrive?",
            detailLines = listOf(
                "Place: " + (params.stringOrNull("label") ?: "(unnamed)"),
                "Will do: " + (params.stringOrNull("request") ?: "(nothing specified)"),
                "This runs by itself, with nobody watching, and Sarothi will also keep reading your " +
                    "location in the background while it is armed.",
            ),
            reason = ConfirmationReason.UNATTENDED_ACTION,
            allowRemember = false,
        )
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val store = GeofenceRegistry.current
            ?: return PluginResult.Unavailable(availability(context))
        val action = params.stringOrNull("action")?.trim()?.lowercase() ?: "add"

        return when (action) {
            "add" -> addReminder(context, store, params)
            "list" -> listReminders(store)
            "delete" -> deleteReminder(store, params)
            "stop_watching" -> {
                GeofenceWatcherService.stop(context.appContext)
                PluginResult.Success(
                    "Stopped watching for your places. The reminders are still saved; Sarothi will " +
                        "start watching again when one is added or the app is opened.",
                    Json.obj { addProperty("watching", false) },
                )
            }
            else -> PluginResult.Failure(
                summaryForUser = "\"$action\" is not a geofence action Sarothi supports. It can do: add, " +
                    "list, delete, stop_watching.",
                errorClass = "UnknownActionException",
                retriable = true,
            )
        }
    }

    private suspend fun addReminder(
        context: PluginContext,
        store: com.ngi.sarothi.core.smart.GeofenceStore,
        params: JsonObject,
    ): PluginResult {
        val label = params.stringOrNull("label")?.takeIf { it.isNotBlank() } ?: ""
        val latitude = params.doubleOrAsk(
            "latitude",
            "What is the latitude of that place? Sarothi will not look it up or guess it — give the " +
                "number from a map.",
        )
        val longitude = params.doubleOrAsk(
            "longitude",
            "What is the longitude of that place? Sarothi will not look it up or guess it — give the " +
                "number from a map.",
        )
        val request = params.textOrAsk(
            "request",
            "What should Sarothi do when you get there?",
        )
        val radius = params.get("radius_metres")?.takeIf { it.isJsonPrimitive }?.asDouble
            ?.coerceIn(20.0, GeofenceReminder.MAX_RADIUS_METRES)
            ?: GeofenceReminder.DEFAULT_RADIUS_METRES
        val triggerName = (params.stringOrNull("trigger") ?: "enter").lowercase()
        val trigger = when (triggerName) {
            "enter", "arrive", "arrival" -> GeofenceTrigger.ENTER
            "exit", "leave", "leaving" -> GeofenceTrigger.EXIT
            "both" -> GeofenceTrigger.BOTH
            else -> return PluginResult.Failure(
                summaryForUser = "\"$triggerName\" is not a trigger Sarothi understands. Use enter, exit or both.",
                errorClass = "UnknownTriggerException",
                retriable = true,
            )
        }
        val runAgent = params.get("run_agent")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val cooldownMinutes = params.get("cooldown_minutes")?.takeIf { it.isJsonPrimitive }?.asInt
            ?.coerceIn(0, 1440) ?: (GeofenceReminder.DEFAULT_COOLDOWN_MILLIS / 60_000L).toInt()

        val created = runCatching {
            store.create(
                label = label,
                latitude = latitude,
                longitude = longitude,
                radiusMetres = radius,
                trigger = trigger,
                request = request,
                runAgent = runAgent,
                cooldownMillis = cooldownMinutes * 60_000L,
            )
        }.getOrElse { failure ->
            return PluginResult.Failure(
                summaryForUser = "Sarothi could not save that reminder: ${failure.message}",
                errorClass = failure.javaClass.simpleName,
                retriable = true,
            )
        }
        GeofenceWatcherService.sync(context.appContext)

        return PluginResult.Success(
            summaryForUser = "Sarothi will ${if (runAgent) request.lowercase() else "remind you: \"$request\""} " +
                "when you ${triggerText(trigger)} ${created.label.ifBlank { "that place" }} " +
                "(${created.radiusMetres.toInt()} m circle). It is now watching your location in the " +
                "background; the notification has a Stop button.",
            data = Json.obj {
                addProperty("id", created.id)
                addProperty("label", created.label)
                addProperty("latitude", created.latitude)
                addProperty("longitude", created.longitude)
                addProperty("radius_metres", created.radiusMetres)
                addProperty("trigger", triggerName)
                addProperty("request", created.request)
                addProperty("run_agent", created.runAgent)
                addProperty("cooldown_minutes", cooldownMinutes)
                addProperty("watching", true)
                addProperty("total_armed", store.enabled().size)
            },
            spoken = "জায়গায় পৌঁছালে মনে করিয়ে দেব।",
            undoToken = created.id,
            memorable = listOf("geofence: ${created.label} at ${created.latitude},${created.longitude}"),
        )
    }

    private suspend fun listReminders(store: com.ngi.sarothi.core.smart.GeofenceStore): PluginResult {
        val reminders = store.all()
        val data = Json.obj {
            add("reminders", Json.arr {
                reminders.forEach { reminder ->
                    add(Json.obj {
                        addProperty("id", reminder.id)
                        addProperty("label", reminder.label)
                        addProperty("latitude", reminder.latitude)
                        addProperty("longitude", reminder.longitude)
                        addProperty("radius_metres", reminder.radiusMetres)
                        addProperty("trigger", reminder.trigger.name.lowercase())
                        addProperty("request", reminder.request)
                        addProperty("run_agent", reminder.runAgent)
                        addProperty("enabled", reminder.enabled)
                        addProperty("trigger_count", reminder.triggerCount)
                        reminder.lastKnownInside?.let { addProperty("currently_inside", it) }
                    })
                }
            })
            addProperty("count", reminders.size)
            addProperty("armed", reminders.count { it.enabled })
        }
        return PluginResult.Success(
            summaryForUser = if (reminders.isEmpty()) {
                "No place reminders are set."
            } else {
                "${reminders.count { it.enabled }} armed reminder(s): " +
                    reminders.filter { it.enabled }.take(4).joinToString("; ") {
                        "${it.label} (${triggerText(it.trigger)}, ${it.radiusMetres.toInt()} m)"
                    }
            },
            data = data,
        )
    }

    private suspend fun deleteReminder(
        store: com.ngi.sarothi.core.smart.GeofenceStore,
        params: JsonObject,
    ): PluginResult {
        val id = params.stringOrNull("geofence_id")?.takeIf { it.isNotBlank() }
            ?: throw com.ngi.sarothi.core.error.MissingInformationException(
                field = "geofence_id",
                questionForUser = "Which place reminder should Sarothi delete? Use action=list to see them.",
            )
        val existing = store.byId(id)
            ?: return PluginResult.Failure(
                "There is no reminder with id \"$id\".", "NotFoundException", retriable = true,
            )
        val deleted = store.delete(id)
        if (!deleted) {
            return PluginResult.Failure("That reminder was already gone.", "NotFoundException", retriable = false)
        }
        return PluginResult.Success(
            summaryForUser = "Deleted the reminder for \"${existing.label}\"." +
                if (store.enabled().isEmpty()) {
                    " That was the last one, so Sarothi stopped watching your location."
                } else "",
            data = Json.obj {
                addProperty("id", existing.id)
                addProperty("label", existing.label)
                addProperty("still_armed", store.enabled().size)
            },
            spoken = "রিমাইন্ডারটি মুছে দিয়েছি।",
            undoToken = com.ngi.sarothi.plugins.common.UndoToken.encode(
                "geofence_restore",
                Json.obj {
                    addProperty("label", existing.label)
                    addProperty("latitude", existing.latitude)
                    addProperty("longitude", existing.longitude)
                    addProperty("radius_metres", existing.radiusMetres)
                    addProperty("trigger", existing.trigger.name.lowercase())
                    addProperty("request", existing.request)
                    addProperty("run_agent", existing.runAgent)
                    addProperty("cooldown_millis", existing.cooldownMillis)
                },
            ),
        )
    }

    override suspend fun undo(undoToken: String): PluginResult {
        val context = pluginContext()
        val store = GeofenceRegistry.current
            ?: return PluginResult.Unavailable(availability(context))
        val payload = com.ngi.sarothi.plugins.common.UndoToken.decode(undoToken, "geofence_restore")
            ?: return if (store.delete(undoToken)) {
                // A bare id is the token addReminder issues.
                PluginResult.Success("Deleted that reminder again.", Json.obj { addProperty("deleted", undoToken) })
            } else {
                PluginResult.Failure(
                    "That undo token is not one Sarothi issued.", "BadUndoTokenException", retriable = false,
                )
            }

        val restored = runCatching {
            store.create(
                label = payload.get("label")?.asString ?: "",
                latitude = payload.get("latitude")?.asDouble
                    ?: return PluginResult.Failure("The saved reminder has no latitude.", "UndoUnavailableException"),
                longitude = payload.get("longitude")?.asDouble
                    ?: return PluginResult.Failure("The saved reminder has no longitude.", "UndoUnavailableException"),
                radiusMetres = payload.get("radius_metres")?.asDouble ?: GeofenceReminder.DEFAULT_RADIUS_METRES,
                trigger = payload.get("trigger")?.asString?.let { raw ->
                    GeofenceTrigger.entries.firstOrNull { it.name.equals(raw, true) }
                } ?: GeofenceTrigger.ENTER,
                request = payload.get("request")?.asString
                    ?: return PluginResult.Failure("The saved reminder has no request.", "UndoUnavailableException"),
                runAgent = payload.get("run_agent")?.asBoolean ?: false,
                cooldownMillis = payload.get("cooldown_millis")?.asLong ?: GeofenceReminder.DEFAULT_COOLDOWN_MILLIS,
            )
        }.getOrElse { failure ->
            return PluginResult.Failure(
                "Sarothi could not put that reminder back: ${failure.message}",
                failure.javaClass.simpleName,
                retriable = false,
            )
        }
        GeofenceWatcherService.sync(context.appContext)
        return PluginResult.Success(
            "Put the reminder for \"${restored.label}\" back (with a new id, ${restored.id}).",
            Json.obj { addProperty("id", restored.id); addProperty("label", restored.label) },
        )
    }

    private fun triggerText(trigger: GeofenceTrigger): String = when (trigger) {
        GeofenceTrigger.ENTER -> "arrive at"
        GeofenceTrigger.EXIT -> "leave"
        GeofenceTrigger.BOTH -> "arrive at or leave"
    }
}

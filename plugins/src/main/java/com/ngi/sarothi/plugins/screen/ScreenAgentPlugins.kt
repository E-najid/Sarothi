package com.ngi.sarothi.plugins.screen

import com.google.gson.JsonObject
import com.ngi.sarothi.core.plugin.JsonSchema
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.core.plugin.PluginAvailability
import com.ngi.sarothi.core.plugin.PluginCategory
import com.ngi.sarothi.core.plugin.PluginContext
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.plugin.pluginContext
import com.ngi.sarothi.core.screen.VisionGrounding
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import com.ngi.sarothi.plugins.common.textOrAsk

/**
 * Looks at a screenshot with the on-demand vision model and says where things are.
 *
 * This is the fallback for screens the accessibility tree cannot describe: games,
 * maps, photo editors, WebView canvases, video players. The vision model is loaded
 * only for this call and, on a 3 GB phone, unloaded straight afterwards.
 *
 * Coordinates come back in real screen pixels, so they can be passed to `tap_at`
 * directly. By default the tool only *reports* what it saw: tapping is a separate,
 * explicit step, because a 450 M model's idea of where a button is should be
 * visible to the user before a finger is put there.
 */
class ScreenAgentPlugin : Plugin {
    override val name = "screen_agent"
    override val description =
        "Look at a screenshot with the vision model and find where something is, when read_screen " +
            "returns an empty or useless tree (games, maps, video players, WebView canvases). Returns " +
            "a description plus tap coordinates in real screen pixels. Set act=true only to tap the " +
            "best match immediately."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "goal" to JsonSchema.Property.Text(
                "What to find, e.g. 'the Send button' or 'the price of the first item'.",
            ),
            "act" to JsonSchema.Property.Flag(
                "Tap the best match straight away instead of only reporting it.",
                default = false,
            ),
            "max_edge" to JsonSchema.Property.Integer(
                "Longest edge of the screenshot sent to the model. Smaller is faster and uses less RAM.",
                minimum = 240, maximum = 1024, default = 640,
            ),
        ),
        required = listOf("goal"),
    )

    override val example = """{"goal":"the Send button"}"""

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val state = context.screen.availability()
        if (!state.capturePermissionGranted) {
            return PluginAvailability.unavailable(
                reason = "Screen capture has not been permitted, so there is no screenshot to look at.",
                fixAction = "Ask the user to allow screen capture in Sarothi's Settings → Screen.",
            )
        }
        if (!state.visionAvailable) {
            return PluginAvailability.unavailable(
                reason = "The vision model cannot be used right now.",
                fixAction = "Install 'LFM2.5-VL-450M' and its mmproj in Settings → Models, and make " +
                    "sure the native build includes multimodal support.",
            )
        }
        return PluginAvailability.READY
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val goal = params.textOrAsk("goal", "What should Sarothi look for on the screen?")
        val act = params.get("act")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val maxEdge = params.get("max_edge")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(240, 1024) ?: 640

        val grounding = context.screen.describeScreen(goal, maxEdge)
        if (!grounding.available) {
            return PluginResult.Failure(
                summaryForUser = "The vision model could not look at the screen: ${grounding.reason}",
                errorClass = "VisionUnavailableException",
                retriable = false,
                data = Json.obj { addProperty("reason", grounding.reason ?: "unknown") },
            )
        }

        val data = groundingJson(goal, grounding)
        if (grounding.taps.isEmpty()) {
            return PluginResult.Success(
                summaryForUser = "The vision model looked at the screen but found nothing matching " +
                    "\"$goal\". ${grounding.reason ?: grounding.description ?: ""}".trim(),
                data = data,
            )
        }

        if (!act) {
            val best = grounding.taps.first()
            return PluginResult.Success(
                summaryForUser = "Found ${grounding.taps.size} candidate(s) for \"$goal\"; the best is " +
                    "\"${best.label}\" at (${best.screenX},${best.screenY}). Use tap_at to tap it, or " +
                    "read_screen first if a real control exists there.",
                data = data,
            )
        }

        val best = grounding.taps.first()
        val result = context.screen.tapAt(best.screenX, best.screenY)
        data.addProperty("acted", true)
        data.addProperty("acted_on", best.label)
        return when (result) {
            is com.ngi.sarothi.core.screen.ActionResult.Done -> PluginResult.Success(
                summaryForUser = "Tapped \"${best.label}\" at (${best.screenX},${best.screenY}) using the " +
                    "vision model" + if (result.verified) "" else " (the screen change could not be confirmed)",
                data = data,
            )
            is com.ngi.sarothi.core.screen.ActionResult.Failed -> PluginResult.Failure(
                summaryForUser = "Found \"${best.label}\" but the tap failed: ${result.detail}",
                errorClass = "ScreenActionFailedException",
                retriable = result.retriable,
                data = data,
            )
            is com.ngi.sarothi.core.screen.ActionResult.Unavailable -> PluginResult.Unavailable(
                PluginAvailability.unavailable(result.detail),
            )
        }
    }

    private fun groundingJson(goal: String, grounding: VisionGrounding): JsonObject = Json.obj {
        addProperty("goal", goal)
        addProperty("model", grounding.modelId ?: "unknown")
        grounding.description?.let { addProperty("description", it) }
        grounding.reason?.let { addProperty("note", it) }
        addProperty("elapsed_millis", grounding.elapsedMillis)
        addProperty("image_size", "${grounding.imageWidth}x${grounding.imageHeight}")
        addProperty("screen_size", "${grounding.screenWidth}x${grounding.screenHeight}")
        add("taps", Json.arr {
            grounding.taps.forEach { tap ->
                add(Json.obj {
                    addProperty("label", tap.label)
                    addProperty("x", tap.screenX)
                    addProperty("y", tap.screenY)
                    addProperty("image_x", tap.imageX)
                    addProperty("image_y", tap.imageY)
                    tap.confidenceNote?.let { addProperty("confidence", it) }
                })
            }
        })
    }
}

/**
 * Summarises whatever is on screen in the user's language.
 *
 * Uses the accessibility tree when it has content (exact, fast, works in Bengali)
 * and falls back to the vision model only when the tree is empty. It says which
 * source it used, because "I read the screen" means very different things for the
 * two.
 */
class SummarizeScreenPlugin : Plugin {
    override val name = "summarize_screen"
    override val description =
        "Describe what is on the screen right now in plain language, using the accessibility tree " +
            "when it has content and the vision model only when the tree is empty. Use it after " +
            "read_screen when the user asks 'what does this say' or 'what should I do here'."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "focus" to JsonSchema.Property.Text("Optional: what the user cares about, e.g. 'the total price'."),
            "max_nodes" to JsonSchema.Property.Integer("How much of the tree to read.", minimum = 10, maximum = 200, default = 80),
        ),
    )

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val state = context.screen.availability()
        return if (state.canReadScreen) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(state.detail, FIX_ACCESSIBILITY_HINT)
        }
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val focus = params.stringOrNull("focus")?.takeIf { it.isNotBlank() }
        val maxNodes = params.get("max_nodes")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(10, 200) ?: 80

        val snapshot = context.screen.snapshot()
        val labelled = snapshot.nodes.filter { it.visibleToUser && it.isOnScreen && !it.label.isNullOrBlank() }
        val source = snapshot.source.name.lowercase()

        if (labelled.isEmpty() && snapshot.ocrText.isNullOrBlank()) {
            val grounding = context.screen.describeScreen(
                focus ?: "Describe this screen and its main controls",
                VISION_EDGE,
            )
            if (!grounding.available) {
                return PluginResult.Failure(
                    summaryForUser = "Sarothi could not read this screen: the accessibility tree is " +
                        "empty and the vision model is unavailable (${grounding.reason}).",
                    errorClass = "ScreenUnreadableException",
                    retriable = false,
                )
            }
            return PluginResult.Success(
                summaryForUser = "From a screenshot (vision model): ${grounding.description ?: "no description returned"}",
                data = Json.obj {
                    addProperty("source", "vision")
                    addProperty("model", grounding.modelId ?: "unknown")
                    grounding.description?.let { addProperty("description", it) }
                    add("taps", Json.arr {
                        grounding.taps.forEach { tap ->
                            add(Json.obj {
                                addProperty("label", tap.label)
                                addProperty("x", tap.screenX)
                                addProperty("y", tap.screenY)
                            })
                        }
                    })
                },
            )
        }

        val heading = labelled.take(3).joinToString(" · ") { it.label!! }
        val actions = labelled.filter { it.isInteractive }.take(maxNodes)
        val text = labelled.filterNot { it.isInteractive }.take(maxNodes)

        val data = Json.obj {
            addProperty("app", snapshot.packageName ?: "unknown")
            snapshot.windowTitle?.let { addProperty("window", it) }
            addProperty("source", source)
            addProperty("heading", heading)
            addProperty("node_count", labelled.size)
            add("actions", Json.arr {
                actions.forEach { node ->
                    add(Json.obj {
                        addProperty("id", node.id)
                        addProperty("label", node.label)
                        node.shortClass?.let { addProperty("type", it) }
                        if (node.editable) addProperty("editable", true)
                        if (!node.enabled) addProperty("disabled", true)
                    })
                }
            })
            add("text", Json.arr { text.forEach { node -> add(node.label) } })
            snapshot.ocrText?.let { addProperty("ocr_text", it) }
            add("limitations", Json.arr { snapshot.limitations.forEach { add(it) } })
        }

        val summary = buildString {
            append(snapshot.packageName ?: "An unknown app")
            append(": ").append(heading.ifBlank { "a screen with ${labelled.size} readable item(s)" })
            append(". ").append(actions.size).append(" thing(s) you can act on")
            if (text.isNotEmpty()) append(", ").append(text.size).append(" text item(s)")
            append(". Read from ").append(snapshot.source.displayName.lowercase()).append('.')
        }
        return PluginResult.Success(summary, data)
    }

    private companion object {
        const val VISION_EDGE = 640
        const val FIX_ACCESSIBILITY_HINT =
            "Ask the user to turn on Sarothi in Settings → Accessibility."
    }
}

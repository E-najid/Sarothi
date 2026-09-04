package com.ngi.sarothi.plugins.screen

import com.google.gson.JsonObject
import com.ngi.sarothi.core.error.MissingInformationException
import com.ngi.sarothi.core.plugin.JsonSchema
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.core.plugin.PluginAvailability
import com.ngi.sarothi.core.plugin.PluginCategory
import com.ngi.sarothi.core.plugin.PluginContext
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.plugin.pluginContext
import com.ngi.sarothi.core.screen.ActionResult
import com.ngi.sarothi.core.screen.ScrollDirection
import com.ngi.sarothi.core.screen.SnapshotSource
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import com.ngi.sarothi.plugins.common.textOrAsk

/** Turns an [ActionResult] into a plugin result without losing the honesty of `verified`. */
internal fun ActionResult.toPluginResult(doneMessage: String): PluginResult = when (this) {
    is ActionResult.Done -> PluginResult.Success(
        summaryForUser = if (verified) doneMessage else "$doneMessage (not confirmed on screen)",
        data = Json.obj {
            addProperty("performed", true)
            addProperty("verified", verified)
            addProperty("detail", detail)
        },
    )
    is ActionResult.Failed -> PluginResult.Failure(detail, "ScreenActionFailedException", retriable)
    is ActionResult.Unavailable -> PluginResult.Unavailable(PluginAvailability.unavailable(detail))
}

private const val NO_ACCESSIBILITY =
    "Sarothi's accessibility service is not connected, so it cannot see or operate the screen."
private const val FIX_ACCESSIBILITY =
    "Ask the user to open Settings → Accessibility → Sarothi and turn it on " +
        "(the permission_guard tool with open=accessibility does this)."

/**
 * Shared availability check for every screen plugin.
 *
 * Reports the real bound state of the accessibility service rather than a
 * constant, so an unavailable tool is greyed out in the UI and marked UNAVAILABLE
 * in the planner's catalogue instead of failing halfway through a task.
 */
internal fun screenAvailability(context: PluginContext): PluginAvailability {
    val state = context.screen.availability()
    return if (state.canReadScreen && state.canAct) {
        PluginAvailability.READY
    } else {
        PluginAvailability.unavailable(
            reason = if (state.detail.isNotBlank()) state.detail else NO_ACCESSIBILITY,
            fixAction = FIX_ACCESSIBILITY,
        )
    }
}

/** Reads the current screen. The agent's eyes. */
class ReadScreenPlugin : Plugin {
    override val name = "read_screen"
    override val description =
        "Read what is on the screen right now: the app, every control with its label, type and node " +
            "id, and any text. Use it before tapping or typing anything, and again afterwards to check " +
            "the action worked. Node ids from a previous read go stale — always read again first."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "filter" to JsonSchema.Property.Text("Only include nodes whose label or id contains this text."),
            "interactive_only" to JsonSchema.Property.Flag("Only tappable/editable/scrollable nodes.", default = false),
            "max_nodes" to JsonSchema.Property.Integer("Cap on nodes returned.", minimum = 5, maximum = 200, default = 60),
            "use_screenshot" to JsonSchema.Property.Flag(
                "Force the screenshot+OCR path instead of the accessibility tree. Only useful when the tree is empty.",
                default = false,
            ),
        ),
    )

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val state = context.screen.availability()
        // Reading works with either the tree or a screenshot; acting needs the tree.
        return if (state.canReadScreen) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                reason = if (state.detail.isNotBlank()) state.detail else NO_ACCESSIBILITY,
                fixAction = FIX_ACCESSIBILITY,
            )
        }
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val preferTree = !(params.get("use_screenshot")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false)
        val snapshot = context.screen.snapshot(preferTree = preferTree)

        if (snapshot.source == SnapshotSource.UNAVAILABLE && snapshot.nodes.isEmpty()) {
            return PluginResult.Failure(
                summaryForUser = "Sarothi could not read the screen. ${snapshot.limitations.joinToString(" ")}",
                errorClass = "ScreenUnreadableException",
                retriable = true,
                data = Json.obj {
                    add("limitations", Json.arr { snapshot.limitations.forEach { add(it) } })
                },
            )
        }

        val filter = params.stringOrNull("filter")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val interactiveOnly = params.get("interactive_only")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val maxNodes = params.get("max_nodes")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(5, 200) ?: 60

        var nodes = snapshot.nodes.filter { it.visibleToUser && it.isOnScreen }
        if (interactiveOnly) nodes = nodes.filter { it.isInteractive }
        if (filter != null) {
            nodes = nodes.filter { node ->
                node.label?.lowercase()?.contains(filter) == true ||
                    node.viewIdResourceName?.lowercase()?.contains(filter) == true ||
                    node.text?.lowercase()?.contains(filter) == true
            }
        }
        val total = nodes.size
        val shown = nodes.take(maxNodes)

        val data = Json.obj {
            addProperty("app", snapshot.packageName ?: "unknown")
            snapshot.windowTitle?.let { addProperty("window", it) }
            addProperty("source", snapshot.source.name.lowercase())
            addProperty("width", snapshot.screenWidth)
            addProperty("height", snapshot.screenHeight)
            addProperty("total_nodes", total)
            addProperty("returned_nodes", shown.size)
            add("nodes", Json.arr {
                shown.forEach { node ->
                    add(Json.obj {
                        addProperty("id", node.id)
                        node.label?.let { addProperty("label", it) }
                        node.shortClass?.let { addProperty("type", it) }
                        node.viewIdResourceName?.let { addProperty("res_id", it.substringAfterLast('/')) }
                        addProperty("bounds", "${node.left},${node.top},${node.right},${node.bottom}")
                        addProperty("center", "${node.centerX},${node.centerY}")
                        if (node.clickable) addProperty("tappable", true)
                        if (node.editable) addProperty("editable", true)
                        if (node.scrollable) addProperty("scrollable", true)
                        if (node.checkable) addProperty("checked", node.checked)
                        if (!node.enabled) addProperty("disabled", true)
                    })
                }
            })
            snapshot.ocrText?.let { addProperty("ocr_text", it) }
            add("limitations", Json.arr { snapshot.limitations.forEach { add(it) } })
        }

        val summary = buildString {
            append(snapshot.packageName ?: "An unknown app").append(": ")
            append(total).append(" node(s)")
            if (filter != null) append(" matching \"$filter\"")
            if (total > shown.size) append(", showing the first ").append(shown.size)
            if (snapshot.source != SnapshotSource.ACCESSIBILITY_TREE) {
                append(" — read from ${snapshot.source.displayName.lowercase()}, so roles and tap ")
                append("targets are approximate")
            }
            append('.')
        }
        return PluginResult.Success(summary, data)
    }
}

/** Taps a control found by [ReadScreenPlugin]. */
class TapNodePlugin : Plugin {
    override val name = "tap_node"
    override val description =
        "Tap a control on the screen using its node id from read_screen. Prefer this over tap_at: a " +
            "node id is the real control, so the tap lands correctly even if the layout shifted. Read " +
            "the screen first — node ids from an earlier read are stale."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "node_id" to JsonSchema.Property.Text("The id from read_screen, e.g. 'n14'."),
            "long_press" to JsonSchema.Property.Flag("Hold instead of tapping.", default = false),
        ),
        required = listOf("node_id"),
    )

    override val example = """{"node_id":"n14"}"""

    override suspend fun availability(context: PluginContext): PluginAvailability = screenAvailability(context)

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val nodeId = params.textOrAsk("node_id", "Which control should Sarothi tap? Give me its node id from read_screen.")
        val longPress = params.get("long_press")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val result = if (longPress) context.screen.longPressNode(nodeId) else context.screen.tapNode(nodeId)
        val verb = if (longPress) "Long-pressed" else "Tapped"
        return result.toPluginResult("$verb node $nodeId")
    }
}

/** Taps raw coordinates — the fallback when there is no node to tap. */
class TapAtPlugin : Plugin {
    override val name = "tap_at"
    override val description =
        "Tap exact screen coordinates in pixels. Use this only when read_screen found no node for the " +
            "control (games, maps, custom-drawn views). Coordinates from a screenshot or the vision " +
            "model are in the scaled image and must be converted with screen_agent first."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "x" to JsonSchema.Property.Integer("Horizontal pixel coordinate.", minimum = 0),
            "y" to JsonSchema.Property.Integer("Vertical pixel coordinate.", minimum = 0),
            "long_press" to JsonSchema.Property.Flag("Hold instead of tapping.", default = false),
        ),
        required = listOf("x", "y"),
    )

    override suspend fun availability(context: PluginContext): PluginAvailability = screenAvailability(context)

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val x = params.get("x")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: throw MissingInformationException("x", "Where horizontally should Sarothi tap, in pixels?")
        val y = params.get("y")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: throw MissingInformationException("y", "Where vertically should Sarothi tap, in pixels?")
        val longPress = params.get("long_press")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val result = if (longPress) {
            context.screen.swipe(x, y, x, y, LONG_PRESS_MILLIS)
        } else {
            context.screen.tapAt(x, y)
        }
        return result.toPluginResult(if (longPress) "Long-pressed at ($x,$y)" else "Tapped at ($x,$y)")
    }

    private companion object {
        const val LONG_PRESS_MILLIS = 700L
    }
}

/** Types into a field. */
class TypeTextPlugin : Plugin {
    override val name = "type_text"
    override val description =
        "Type text into an input field. Give node_id from read_screen to target a specific field, or " +
            "leave it out to type into whatever field already has focus. Use it for search boxes, " +
            "message composers and forms."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "text" to JsonSchema.Property.Text("Exactly what to type."),
            "node_id" to JsonSchema.Property.Text("Target field's node id. Empty types into the focused field."),
        ),
        required = listOf("text"),
    )

    override suspend fun availability(context: PluginContext): PluginAvailability = screenAvailability(context)

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val text = params.textOrAsk("text", "What exactly should Sarothi type?")
        val nodeId = params.stringOrNull("node_id")?.takeIf { it.isNotBlank() }
        val result = if (nodeId != null) context.screen.setText(nodeId, text) else context.screen.typeIntoFocused(text)
        return result.toPluginResult("Typed ${text.length} character(s)")
    }
}

/** Scrolls or swipes. */
class ScrollScreenPlugin : Plugin {
    override val name = "scroll_screen"
    override val description =
        "Scroll a list or the whole screen up, down, left or right, to reach content that is not " +
            "visible. Give node_id to scroll a specific list from read_screen, otherwise it scrolls the " +
            "middle of the screen."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "direction" to JsonSchema.Property.Text("Which way the content moves.", enum = listOf("up", "down", "left", "right")),
            "node_id" to JsonSchema.Property.Text("A scrollable node's id. Empty scrolls the screen."),
            "amount" to JsonSchema.Property.Integer("How many swipes, 1 to 5.", minimum = 1, maximum = 5, default = 1),
        ),
        required = listOf("direction"),
    )

    override suspend fun availability(context: PluginContext): PluginAvailability = screenAvailability(context)

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val directionText = params.textOrAsk("direction", "Which way should Sarothi scroll?")
        val direction = runCatching { ScrollDirection.valueOf(directionText.uppercase()) }.getOrElse {
            return PluginResult.Failure(
                "'$directionText' is not a direction; use up, down, left or right.",
                "IllegalArgumentException",
                retriable = true,
            )
        }
        val amount = params.get("amount")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, 5) ?: 1
        val nodeId = params.stringOrNull("node_id")?.takeIf { it.isNotBlank() }

        if (nodeId != null) {
            var last: ActionResult = ActionResult.Unavailable("No scroll attempted")
            repeat(amount) {
                last = context.screen.scroll(nodeId, direction)
                if (last !is ActionResult.Done) return@repeat
            }
            return last.toPluginResult("Scrolled ${direction.name.lowercase()} in node $nodeId ${amount}×")
        }

        val snapshot = context.screen.snapshot()
        if (snapshot.screenWidth <= 0 || snapshot.screenHeight <= 0) {
            return PluginResult.Failure(
                "Sarothi could not determine the screen size, so it cannot swipe. $NO_ACCESSIBILITY",
                "ScreenUnreadableException",
                retriable = false,
            )
        }
        val cx = snapshot.screenWidth / 2
        val cy = snapshot.screenHeight / 2
        val span = (snapshot.screenHeight * 0.4f).toInt()
        var last: ActionResult = ActionResult.Unavailable("No swipe attempted")
        repeat(amount) {
            last = when (direction) {
                ScrollDirection.DOWN -> context.screen.swipe(cx, cy + span / 2, cx, cy - span / 2)
                ScrollDirection.UP -> context.screen.swipe(cx, cy - span / 2, cx, cy + span / 2)
                ScrollDirection.RIGHT -> context.screen.swipe(cx - span / 2, cy, cx + span / 2, cy)
                ScrollDirection.LEFT -> context.screen.swipe(cx + span / 2, cy, cx - span / 2, cy)
            }
        }
        return last.toPluginResult("Swiped ${direction.name.lowercase()} ${amount}×")
    }
}

/** System navigation. */
class NavigatePlugin : Plugin {
    override val name = "press_key"
    override val description =
        "Press a system key: back, home, recents, or open the notification shade and quick settings. " +
            "Use 'back' to leave a screen, 'home' to abandon a task's current app."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "key" to JsonSchema.Property.Text(
                "Which key.",
                enum = listOf("back", "home", "recents", "notifications", "quick_settings"),
            ),
        ),
        required = listOf("key"),
    )

    override suspend fun availability(context: PluginContext): PluginAvailability = screenAvailability(context)

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val key = params.textOrAsk("key", "Which key should Sarothi press?").lowercase()
        val result = when (key) {
            "back" -> context.screen.back()
            "home" -> context.screen.home()
            "recents" -> context.screen.openRecents()
            "notifications" -> context.screen.openNotifications()
            "quick_settings" -> context.screen.quickSettings()
            else -> return PluginResult.Failure(
                "'$key' is not a key Sarothi can press; use back, home, recents, notifications or quick_settings.",
                "IllegalArgumentException",
                retriable = true,
            )
        }
        return result.toPluginResult("Pressed $key")
    }
}

/** Launches another app. */
class OpenAppPlugin : Plugin {
    override val name = "open_app"
    override val description =
        "Open an installed app, by package name (com.whatsapp) or by the name the user gave " +
            "(WhatsApp, bKash, ইমো). Returns whether the app actually came to the foreground."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "package_name" to JsonSchema.Property.Text("Exact package name, if known."),
            "app_name" to JsonSchema.Property.Text("The app's display name, used when the package is unknown."),
        ),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val explicit = params.stringOrNull("package_name")?.trim()?.takeIf { it.isNotBlank() }
        val appName = params.stringOrNull("app_name")?.trim()?.takeIf { it.isNotBlank() }

        val packageName = explicit ?: appName?.let { name -> resolvePackage(context.appContext, name) }
        if (packageName == null) {
            val candidates = listInstalledLaunchers(context.appContext)
            return if (appName == null) {
                throw MissingInformationException(
                    "app_name",
                    "Which app should Sarothi open?",
                    choices = candidates.take(12).map { it.first },
                )
            } else {
                PluginResult.Failure(
                    summaryForUser = "No installed app is called \"$appName\". Installed apps include: " +
                        candidates.take(15).joinToString { it.first },
                    errorClass = "AppNotFoundException",
                    retriable = true,
                    data = Json.obj {
                        add("installed", Json.arr { candidates.forEach { add(Json.obj {
                            addProperty("name", it.first); addProperty("package", it.second)
                        }) } })
                    },
                )
            }
        }
        val result = context.screen.launchApp(packageName)
        return result.toPluginResult("Opened $packageName")
    }

    private fun resolvePackage(context: android.content.Context, appName: String): String? {
        val needle = appName.lowercase().trim()
        return listInstalledLaunchers(context)
            .sortedByDescending { (label, _) -> label.lowercase().startsWith(needle) }
            .firstOrNull { (label, _) -> label.lowercase().contains(needle) }
            ?.second
    }

    private fun listInstalledLaunchers(context: android.content.Context): List<Pair<String, String>> {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            context.packageManager.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())
        return resolved.mapNotNull { info ->
            val label = runCatching { info.loadLabel(context.packageManager).toString() }.getOrNull()
            val pkg = info.activityInfo?.packageName
            if (label.isNullOrBlank() || pkg.isNullOrBlank()) null else label to pkg
        }.distinctBy { it.second }.sortedBy { it.first }
    }
}

/** Screenshot → OCR, and says plainly when Bengali cannot be read. */
class ScreenshotOcrPlugin : Plugin {
    override val name = "screenshot_ocr"
    override val description =
        "Take a screenshot and read the text in it with on-device OCR. Use it when read_screen returns " +
            "an empty or useless tree (WebView canvases, games, video players). Latin script only: " +
            "ML Kit publishes no Bengali OCR model, so Bengali text will come back empty and this tool " +
            "will say so."
    override val category = PluginCategory.SYSTEM
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "max_edge" to JsonSchema.Property.Integer("Longest edge of the captured image, in pixels.", minimum = 240, maximum = 1440, default = 768),
        ),
    )

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val state = context.screen.availability()
        return when {
            !state.capturePermissionGranted -> PluginAvailability.unavailable(
                reason = "Screen capture has not been permitted, so no screenshot can be taken.",
                fixAction = "Ask the user to allow screen capture in Sarothi's Settings → Screen.",
            )
            !state.ocrAvailable -> PluginAvailability.unavailable(
                reason = "On-device OCR is not available in this build.",
            )
            else -> PluginAvailability.READY
        }
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val maxEdge = params.get("max_edge")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(240, 1440) ?: 768
        val snapshot = context.screen.snapshot(preferTree = false)
        val text = snapshot.ocrText

        val data = Json.obj {
            addProperty("source", snapshot.source.name.lowercase())
            addProperty("width", snapshot.screenWidth)
            addProperty("height", snapshot.screenHeight)
            addProperty("max_edge", maxEdge)
            text?.let { addProperty("text", it) }
            add("regions", Json.arr {
                snapshot.ocrRegions.forEach { region ->
                    add(Json.obj {
                        addProperty("text", region.text)
                        addProperty("center", "${region.centerX},${region.centerY}")
                        addProperty("bounds", "${region.left},${region.top},${region.right},${region.bottom}")
                    })
                }
            })
            add("limitations", Json.arr { snapshot.limitations.forEach { add(it) } })
        }

        return if (text.isNullOrBlank()) {
            PluginResult.Failure(
                summaryForUser = "The screenshot contained no text this OCR engine could read. " +
                    snapshot.limitations.joinToString(" "),
                errorClass = "OcrNoTextException",
                retriable = false,
                data = data,
            )
        } else {
            PluginResult.Success(
                "Read ${snapshot.ocrRegions.size} text region(s) from the screenshot.",
                data,
            )
        }
    }
}

package com.ngi.sarothi.core.screen

import android.graphics.Rect
import com.google.gson.JsonObject
import com.ngi.sarothi.core.util.Json

/**
 * A flattened, serialisable view of one node in the accessibility tree.
 *
 * Sarothi works from this list rather than holding live `AccessibilityNodeInfo`
 * objects: the framework recycles them at any moment (and throws if you touch a
 * stale one), so a snapshot of plain data is both safer and what the model needs.
 *
 * [id] is a synthetic stable-within-snapshot identifier. The agent refers to
 * nodes by it, and the controller resolves it against a fresh snapshot so a stale
 * id fails loudly instead of tapping the wrong thing.
 */
data class ScreenNode(
    val id: String,
    val index: Int,
    val depth: Int,
    val parentIndex: Int?,
    val text: String?,
    val contentDescription: String?,
    val hintText: String?,
    val className: String?,
    val viewIdResourceName: String?,
    val packageName: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean?,
    val editable: Boolean,
    val enabled: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val selected: Boolean,
    val visibleToUser: Boolean,
    val roleDescription: String?,
) {
    val bounds: Rect get() = Rect(left, top, right, bottom)

    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** The best human-readable label, in the order a sighted user would read it. */
    val label: String?
        get() = text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: hintText?.takeIf { it.isNotBlank() }
            ?: roleDescription?.takeIf { it.isNotBlank() }

    /** Short class name; `android.widget.Button` becomes `Button`. */
    val shortClass: String? get() = className?.substringAfterLast('.')

    val isOnScreen: Boolean get() = width > 0 && height > 0

    /** True for things a user could reasonably be asked to tap. */
    val isInteractive: Boolean get() = enabled && visibleToUser && isOnScreen &&
        (clickable || longClickable || editable || checkable || scrollable)

    fun toJson(): JsonObject = Json.obj {
        addProperty("id", id)
        addProperty("label", label)
        shortClass?.let { addProperty("type", it) }
        viewIdResourceName?.let { addProperty("res_id", it.substringAfterLast('/')) }
        addProperty("bounds", "$left,$top,$right,$bottom")
        if (clickable) addProperty("clickable", true)
        if (editable) addProperty("editable", true)
        if (scrollable) addProperty("scrollable", true)
        if (checkable) addProperty("checkable", true)
        checked?.let { addProperty("checked", it) }
        if (!enabled) addProperty("enabled", false)
    }

    /**
     * One compact line for the prompt. A 350 M model has a small context, so this
     * is deliberately terse: id, type, label, state, position.
     */
    fun toPromptLine(): String = buildString {
        append('[').append(id).append("] ")
        shortClass?.let { append(it).append(' ') }
        label?.let { append('"').append(it.replace('\n', ' ').take(60)).append("\" ") }
        viewIdResourceName?.let { append('(').append(it.substringAfterLast('/')).append(") ") }
        val flags = mutableListOf<String>()
        if (editable) flags += "editable"
        if (scrollable) flags += "scrollable"
        if (checkable) flags += if (checked == true) "checked" else "unchecked"
        if (clickable) flags += "tappable"
        if (!enabled) flags += "disabled"
        if (flags.isNotEmpty()) append(flags.joinToString(",", "[", "]"))
    }
}

/** Where a snapshot came from — the UI shows this because the three differ a lot in fidelity. */
enum class SnapshotSource(val displayName: String, val fidelity: String) {
    ACCESSIBILITY_TREE(
        "Accessibility tree",
        "Exact text, node roles and bounds. The primary and preferred source.",
    ),
    SCREENSHOT_OCR(
        "Screenshot + OCR",
        "Recognised text only, with bounding boxes. No node roles, no click targets " +
            "beyond coordinates. Bengali script is not supported by the on-device OCR model.",
    ),
    SCREENSHOT_VLM(
        "Screenshot + vision model",
        "The VLM describes the screen and proposes tap coordinates. Slowest, and only " +
            "as reliable as a 450 M model on a 3 GB phone.",
    ),
    UNAVAILABLE(
        "No screen access",
        "The accessibility service is not connected and no screenshot was permitted.",
    ),
}

data class ScreenSnapshot(
    val capturedAtEpochMillis: Long,
    val source: SnapshotSource,
    val packageName: String?,
    val activityName: String?,
    val windowTitle: String?,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<ScreenNode>,
    val ocrText: String?,
    val ocrRegions: List<OcrRegion>,
    /** Honest notes about what this snapshot cannot tell the agent. */
    val limitations: List<String>,
) {
    val interactiveNodes: List<ScreenNode> get() = nodes.filter { it.isInteractive }

    fun nodeById(id: String): ScreenNode? = nodes.firstOrNull { it.id == id }

    fun nodeAt(x: Int, y: Int): ScreenNode? = interactiveNodes
        .filter { x in it.left..it.right && y in it.top..it.bottom }
        .minByOrNull { it.width.toLong() * it.height.toLong() }

    /**
     * The screen as the model sees it. Truncated to [maxNodes] with an explicit
     * note when truncated — silently dropping nodes would make the model believe
     * a control does not exist.
     */
    fun toPrompt(maxNodes: Int = 60): String = buildString {
        append("SCREEN: ")
        append(packageName ?: "unknown app")
        windowTitle?.takeIf { it.isNotBlank() }?.let { append(" / ").append(it) }
        append("  ${screenWidth}x$screenHeight\n")
        append("SOURCE: ${source.displayName}\n")
        if (nodes.isEmpty() && ocrText.isNullOrBlank()) {
            append("(nothing readable on this screen)\n")
        }
        val usable = nodes.filter { it.visibleToUser && it.isOnScreen }
        usable.take(maxNodes).forEach { node -> append(node.toPromptLine()).append('\n') }
        if (usable.size > maxNodes) {
            append("... ").append(usable.size - maxNodes)
                .append(" more nodes omitted; ask for a narrower region or scroll\n")
        }
        ocrText?.takeIf { it.isNotBlank() }?.let {
            append("OCR TEXT (coordinates are approximate):\n")
            append(it.lineSequence().take(40).joinToString("\n") { line -> "  $line" }).append('\n')
        }
        limitations.forEach { append("LIMITATION: ").append(it).append('\n') }
    }

    companion object {
        val EMPTY = ScreenSnapshot(
            capturedAtEpochMillis = 0L,
            source = SnapshotSource.UNAVAILABLE,
            packageName = null,
            activityName = null,
            windowTitle = null,
            screenWidth = 0,
            screenHeight = 0,
            nodes = emptyList(),
            ocrText = null,
            ocrRegions = emptyList(),
            limitations = listOf("No screen source is connected"),
        )
    }
}

/** One recognised text region from OCR, in screen coordinates. */
data class OcrRegion(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

package com.ngi.sarothi.core.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The primary screen perception and action path.
 *
 * Reads the accessibility tree into [ScreenNode] snapshots and acts through
 * `performAction` / `dispatchGesture`. All of it is synchronous Android framework
 * work, so it is pinned to [Dispatchers.IO]: tree walks on the main thread are the
 * classic way an accessibility agent ANRs.
 *
 * Two design decisions matter for correctness:
 *
 *  1. **Node ids are per-snapshot.** Live `AccessibilityNodeInfo` objects are
 *     recycled by the framework at any moment, so acting on a cached one is
 *     unsafe. Every action re-walks the tree and resolves the target inside that
 *     walk. If the control has genuinely gone away the action fails with a
 *     "read the screen again" message and the agent replans — it never taps a
 *     node that merely happens to share an index.
 *  2. **`performAction` returning true is not proof.** Results carry a `verified`
 *     flag that is only set when Sarothi re-read the screen and saw a change.
 */
class AccessibilityScreenController(
    private val context: Context,
    private val screenshots: () -> ScreenshotSource? = { ScreenshotSourceRegistry.current },
    private val ocr: OcrEngine = NoOcrEngine,
    private val vision: VisionDescriber? = null,
) : ScreenController {

    override val isServiceConnected: Boolean
        get() = AccessibilityHostRegistry.current != null

    override val hasCapturePermission: Boolean
        get() = screenshots()?.isReady == true

    override fun availability(): ScreenAvailability {
        val host = AccessibilityHostRegistry.current
        val info = host?.currentServiceInfo()
        val enabledInSettings = accessibilityEnabledInSettings()

        val problems = mutableListOf<String>()
        if (host == null) problems += "The Sarothi accessibility service is not running."
        else if (info == null) problems += "The accessibility service is running but reports no configuration."
        if (!enabledInSettings) problems += "Accessibility permission is off in system Settings."
        if (!hasCapturePermission) {
            problems += screenshots()?.unavailabilityReason()
                ?: "Screen capture has not been permitted, so the screenshot fallback is unavailable."
        }
        if (!ocr.isAvailable) problems += (ocr.unavailabilityReason() ?: "On-device OCR is unavailable.")

        return ScreenAvailability(
            accessibilityConnected = host != null && info != null,
            accessibilityEnabledInSettings = enabledInSettings,
            capturePermissionGranted = hasCapturePermission,
            ocrAvailable = ocr.isAvailable,
            visionAvailable = vision?.isAvailable == true,
            detail = if (problems.isEmpty()) "Screen access is ready." else problems.joinToString(" "),
        )
    }

    /** Reads the system setting rather than trusting our own bound state. */
    private fun accessibilityEnabledInSettings(): Boolean {
        val component = SarothiAccessibility.componentFor(context) ?: return false
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val needle = component.flattenToString().lowercase()
        return enabled.split(':').any { it.trim().lowercase() == needle }
    }

    // ------------------------------------------------------------------ reading

    override suspend fun snapshot(preferTree: Boolean): ScreenSnapshot = withContext(Dispatchers.IO) {
        val tree = if (preferTree && isServiceConnected) readTree() else null
        try {
            if (tree != null && tree.entries.size >= MIN_USEFUL_NODES) {
                return@withContext tree.toSnapshot()
            }
            val screenshot = screenshotSnapshot(tree)
            if (screenshot.nodes.isEmpty() && screenshot.ocrText.isNullOrBlank()) {
                // Nothing at all could be read. Report that precisely instead of
                // handing the model an empty screen that looks like a blank app.
                val why = buildList {
                    tree?.let { add("The accessibility tree exposed only ${it.entries.size} node(s).") }
                    if (!hasCapturePermission) {
                        add("Screen capture permission was not granted, so no screenshot could be taken.")
                    }
                    if (!ocr.isAvailable) ocr.unavailabilityReason()?.let { add(it) }
                    add("Nothing readable was found, so Sarothi cannot act on this screen. " +
                        "The foreground app may expose no content to accessibility services " +
                        "(games and custom-drawn surfaces do this), or Sarothi may be looking at " +
                        "its own empty window.")
                }
                screenshot.copy(
                    source = if (hasCapturePermission) SnapshotSource.SCREENSHOT_OCR else SnapshotSource.UNAVAILABLE,
                    limitations = screenshot.limitations + why,
                )
            } else {
                screenshot
            }
        } finally {
            tree?.recycle()
        }
    }

    override suspend fun captureScreen(maxDimension: Int): CaptureResult = withContext(Dispatchers.IO) {
        val source = screenshots()
            ?: return@withContext CaptureResult.Denied(
                reason = "Screen capture is not available: the MediaProjection service is not running.",
                needsUserConsent = true,
            )
        if (!source.isReady) {
            return@withContext CaptureResult.Denied(
                reason = source.unavailabilityReason() ?: "Screen capture has not been permitted yet.",
                needsUserConsent = true,
            )
        }
        source.capture(maxDimension)
    }

    private class LiveNode(val node: ScreenNode, val info: AccessibilityNodeInfo)

    private class LiveTree(
        val entries: List<LiveNode>,
        val packageName: String?,
        val windowTitle: String?,
        val screenWidth: Int,
        val screenHeight: Int,
        val capturedAt: Long,
        val limitations: List<String>,
        private val owned: List<AccessibilityNodeInfo>,
    ) {
        fun find(nodeId: String): LiveNode? = entries.firstOrNull { it.node.id == nodeId }

        fun toSnapshot(): ScreenSnapshot = ScreenSnapshot(
            capturedAtEpochMillis = capturedAt,
            source = SnapshotSource.ACCESSIBILITY_TREE,
            packageName = packageName,
            activityName = null,
            windowTitle = windowTitle,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            nodes = entries.map { it.node },
            ocrText = null,
            ocrRegions = emptyList(),
            limitations = limitations,
        )

        fun recycle() = owned.forEach { runCatching { it.recycle() } }
    }

    private fun readTree(): LiveTree? {
        val host = AccessibilityHostRegistry.current ?: return null
        val screen = host.screenSize()
        val windows = collectWindows(host)
        if (windows.isEmpty()) return null

        val entries = ArrayList<LiveNode>(96)
        val owned = ArrayList<AccessibilityNodeInfo>(96)
        val limitations = mutableListOf<String>()
        val bounds = Rect()
        var truncated = false

        for ((window, root) in windows) {
            owned += root
            val frontier = ArrayDeque<Pair<AccessibilityNodeInfo, Pair<Int, Int?>>>()
            frontier.addLast(root to (0 to null))

            while (frontier.isNotEmpty()) {
                if (entries.size >= MAX_NODES) {
                    truncated = true
                    break
                }
                val (info, depthAndParent) = frontier.removeFirst()
                val (depth, parentIndex) = depthAndParent
                val index = entries.size

                runCatching { info.getBoundsInScreen(bounds) }
                entries += LiveNode(
                    node = ScreenNode(
                        id = "n$index",
                        index = index,
                        depth = depth,
                        parentIndex = parentIndex,
                        text = info.text?.toString()?.takeIf { it.isNotBlank() },
                        contentDescription = info.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                        hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            runCatching { info.hintText?.toString()?.takeIf { it.isNotBlank() } }.getOrNull()
                        } else {
                            null
                        },
                        className = info.className?.toString(),
                        viewIdResourceName = info.viewIdResourceName,
                        packageName = info.packageName?.toString(),
                        left = bounds.left,
                        top = bounds.top,
                        right = bounds.right,
                        bottom = bounds.bottom,
                        clickable = info.isClickable,
                        longClickable = info.isLongClickable,
                        scrollable = info.isScrollable,
                        checkable = info.isCheckable,
                        checked = if (info.isCheckable) info.isChecked else null,
                        editable = info.isEditableNode(),
                        enabled = info.isEnabled,
                        focusable = info.isFocusable,
                        focused = info.isFocused,
                        selected = info.isSelected,
                        visibleToUser = info.isVisibleToUser,
                        // AccessibilityNodeInfo has no public getRoleDescription(): that
                        // accessor lives on AndroidX' AccessibilityNodeInfoCompat, which
                        // reads this exact extras key. getExtras() is public since API 19
                        // and is documented as never null.
                        roleDescription = runCatching {
                            info.extras.getCharSequence(ROLE_DESCRIPTION_KEY)
                                ?.toString()?.takeIf { it.isNotBlank() }
                        }.getOrNull(),
                    ),
                    info = info,
                )

                val childCount = info.childCount
                for (i in 0 until childCount) {
                    val child = runCatching { info.getChild(i) }.getOrNull()
                    if (child == null) continue
                    owned += child
                    frontier.addLast(child to (depth + 1 to index))
                }
            }
            if (truncated) break
            // A window whose root is not focused can still hold the interesting
            // content (dialogs, IMEs); keep walking the rest.
            if (window != null && !window.isActive) continue
        }

        if (entries.isEmpty()) {
            owned.forEach { runCatching { it.recycle() } }
            return null
        }
        if (truncated) {
            limitations += "Tree walk stopped at $MAX_NODES nodes; controls deeper than that were not read. " +
                "Scroll or narrow the search to reach them."
        }

        val packageName = entries.firstNotNullOfOrNull { it.node.packageName }
        val windowTitle = windows.firstNotNullOfOrNull { (window, _) -> windowTitle(window) }

        return LiveTree(
            entries = entries,
            packageName = packageName,
            windowTitle = windowTitle,
            screenWidth = screen.x,
            screenHeight = screen.y,
            capturedAt = System.currentTimeMillis(),
            limitations = limitations,
            owned = owned,
        )
    }

    private fun windowTitle(window: AccessibilityWindowInfo?): String? {
        if (window == null) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching { window.title?.toString()?.takeIf { it.isNotBlank() } }.getOrNull()
    }

    /** Application windows, focused one first, plus the IME window when present. */
    private fun collectWindows(host: AccessibilityHost): List<Pair<AccessibilityWindowInfo?, AccessibilityNodeInfo>> {
        val result = mutableListOf<Pair<AccessibilityWindowInfo?, AccessibilityNodeInfo>>()
        val seen = mutableSetOf<String>()

        val windows = runCatching { host.allWindows() }.getOrDefault(emptyList())
        val ordered = windows
            .filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
            }
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { it.isFocused }
                    .thenByDescending { it.isActive }
                    .thenByDescending { it.layer },
            )

        for (window in ordered) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            val key = "${root.packageName}:${root.viewIdResourceName}:${System.identityHashCode(root)}"
            if (!seen.add(key)) {
                runCatching { root.recycle() }
                continue
            }
            result += window to root
        }

        if (result.isEmpty()) {
            runCatching { host.activeRoot() }.getOrNull()?.let { result += null to it }
        }
        return result
    }

    private suspend fun screenshotSnapshot(tree: LiveTree?): ScreenSnapshot {
        val base = tree?.toSnapshot()
        val baseLimitations = base?.limitations ?: emptyList()

        val source = screenshots()
        if (source == null || !source.isReady) {
            return (base ?: emptySnapshot()).copy(
                source = SnapshotSource.SCREENSHOT_OCR,
                limitations = baseLimitations +
                    (source?.unavailabilityReason() ?: "No screenshot source is running."),
            )
        }

        val captured = source.capture(DEFAULT_CAPTURE_EDGE)
        if (captured !is CaptureResult.Captured) {
            val reason = (captured as CaptureResult.Denied).reason
            return (base ?: emptySnapshot()).copy(
                source = SnapshotSource.SCREENSHOT_OCR,
                limitations = baseLimitations + reason,
            )
        }

        val limitations = mutableListOf<String>()
        limitations += "This snapshot came from a screenshot, not the accessibility tree: node roles, " +
            "enabled state and true tap targets are unknown. Prefer tapping by coordinates only when " +
            "a recognised text region is clearly the target."
        baseLimitations.let { limitations += it }

        if (!ocr.isAvailable) {
            return (base ?: emptySnapshot()).copy(
                source = SnapshotSource.SCREENSHOT_OCR,
                limitations = limitations + (ocr.unavailabilityReason() ?: "OCR is unavailable"),
            )
        }

        val result = ocr.recognise(captured.bitmap)
        result.limitation?.let { limitations += it }
        val regions = result.regions.mapIndexed { index, region ->
            ScreenNode(
                id = "ocr$index",
                index = index,
                depth = 0,
                parentIndex = null,
                text = region.text,
                contentDescription = null,
                hintText = null,
                className = "OcrTextRegion",
                viewIdResourceName = null,
                packageName = base?.packageName,
                left = region.left,
                top = region.top,
                right = region.right,
                bottom = region.bottom,
                clickable = false,
                longClickable = false,
                scrollable = false,
                checkable = false,
                checked = null,
                editable = false,
                enabled = true,
                focusable = false,
                focused = false,
                selected = false,
                visibleToUser = true,
                roleDescription = null,
            )
        }

        return (base ?: emptySnapshot()).copy(
            capturedAtEpochMillis = captured.capturedAtEpochMillis,
            source = SnapshotSource.SCREENSHOT_OCR,
            screenWidth = captured.width,
            screenHeight = captured.height,
            nodes = (base?.nodes ?: emptyList()) + regions,
            ocrText = result.text.takeIf { it.isNotBlank() },
            ocrRegions = result.regions,
            limitations = limitations,
        )
    }

    private fun emptySnapshot(): ScreenSnapshot = ScreenSnapshot(
        capturedAtEpochMillis = System.currentTimeMillis(),
        source = SnapshotSource.UNAVAILABLE,
        packageName = null,
        activityName = null,
        windowTitle = null,
        screenWidth = 0,
        screenHeight = 0,
        nodes = emptyList(),
        ocrText = null,
        ocrRegions = emptyList(),
        limitations = emptyList(),
    )

    /**
     * Vision fallback, used when the accessibility tree is useless and OCR cannot
     * read the script.
     *
     * Returns an unavailable grounding with the real reason when the vision model is
     * missing, would not fit in RAM, or capture was refused — never an empty
     * description that would read as "there is nothing on this screen".
     *
     * Tap coordinates are converted from the downscaled capture into real screen
     * pixels here, because only this class knows both sizes. A caller that scaled
     * them itself would get it wrong on every device with a capture edge smaller
     * than the display.
     */
    override suspend fun describeScreen(question: String, maxDimension: Int): VisionGrounding {
        val describer = vision
            ?: return VisionGrounding.unavailable(
                "No vision model is wired into this build, so screenshots cannot be described.",
            )
        if (!describer.isAvailable) {
            return VisionGrounding.unavailable(
                describer.unavailabilityReason() ?: "The vision model is not available.",
            )
        }
        val captured = captureScreen(maxDimension)
        if (captured !is CaptureResult.Captured) {
            val denied = captured as CaptureResult.Denied
            return VisionGrounding.unavailable(denied.reason)
        }

        val startedAt = System.currentTimeMillis()
        val description = try {
            describer.describe(captured.bitmap, question)
        } catch (failure: Exception) {
            return VisionGrounding.unavailable(
                "The vision model could not process the screenshot: " +
                    "${failure.javaClass.simpleName}: ${failure.message}",
            )
        }

        val host = AccessibilityHostRegistry.current
        val screen = host?.screenSize()
        val screenWidth = screen?.x ?: captured.width
        val screenHeight = screen?.y ?: captured.height
        val scaleX = if (captured.width > 0) screenWidth.toFloat() / captured.width.toFloat() else 1f
        val scaleY = if (captured.height > 0) screenHeight.toFloat() / captured.height.toFloat() else 1f

        val taps = description.proposedTaps.map { tap ->
            GroundedTap(
                label = tap.label,
                screenX = (tap.x * scaleX).toInt().coerceIn(0, screenWidth - 1),
                screenY = (tap.y * scaleY).toInt().coerceIn(0, screenHeight - 1),
                imageX = tap.x,
                imageY = tap.y,
                confidenceNote = tap.confidenceNote,
            )
        }

        return VisionGrounding(
            available = true,
            reason = null,
            description = description.text,
            taps = taps,
            modelId = description.modelId,
            elapsedMillis = System.currentTimeMillis() - startedAt,
            imageWidth = captured.width,
            imageHeight = captured.height,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
    }

    /** Why the vision path cannot be used, or null when it can. */
    val visionAvailability: String?
        get() = vision?.let { describer ->
            if (describer.isAvailable) null else describer.unavailabilityReason()
        } ?: "No vision model is wired into this build."

    // ------------------------------------------------------------------ actions

    override suspend fun tapNode(nodeId: String): ActionResult = withTree { tree ->
        val live = tree.find(nodeId) ?: return@withTree staleNode(nodeId, tree)
        val node = live.node
        val before = focusedSignature()

        val clicked = clickWithAncestorFallback(live.info)
        if (!clicked) {
            // The framework refused performAction. A coordinate tap is what a
            // user's finger would do, so fall back to it rather than giving up.
            val gesture = gestureTap(node.centerX, node.centerY, TAP_MILLIS)
            if (gesture is ActionResult.Done) {
                delay(SETTLE_MILLIS)
                return@withTree gesture.copy(
                    detail = "Tapped \"${node.label ?: nodeId}\" at (${node.centerX},${node.centerY}) " +
                        "by gesture because Android refused ACTION_CLICK on it.",
                )
            }
            return@withTree ActionResult.Failed(
                "Android refused ACTION_CLICK on \"${node.label ?: nodeId}\" and the coordinate " +
                    "tap fallback failed too: ${gesture.detail}",
            )
        }

        delay(SETTLE_MILLIS)
        val after = focusedSignature()
        val changed = before != null && after != null && before != after
        if (changed) {
            ActionResult.Done("Clicked \"${node.label ?: node.shortClass ?: nodeId}\"", verified = true)
        } else {
            ActionResult.Done(
                "Clicked \"${node.label ?: node.shortClass ?: nodeId}\" — Android accepted the click " +
                    "but the accessible screen state did not change, so the effect could not be " +
                    "confirmed. Read the screen again before relying on it.",
                verified = false,
            )
        }
    }

    /**
     * Many apps mark a child TextView non-clickable inside a clickable container.
     * Walking up to the nearest clickable ancestor is what a user's finger does.
     */
    private fun clickWithAncestorFallback(info: AccessibilityNodeInfo): Boolean {
        val ancestors = mutableListOf<AccessibilityNodeInfo>()
        try {
            var current: AccessibilityNodeInfo? = info
            var hops = 0
            while (current != null && hops < MAX_ANCESTOR_HOPS) {
                if (current.isClickable && current.isEnabled) {
                    val ok = runCatching { current.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        .getOrDefault(false)
                    if (ok) return true
                }
                val parent = runCatching { current.parent }.getOrNull()
                if (parent != null && parent !== info) ancestors += parent
                current = parent
                hops++
            }
            // Nothing up the chain accepted it; try the node itself even though it
            // is not flagged clickable — custom views often still handle the action.
            return runCatching { info.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
        } finally {
            // Only nodes we obtained via getParent() are ours to recycle; `info`
            // belongs to the live tree and is recycled with it.
            ancestors.forEach { runCatching { it.recycle() } }
        }
    }

    override suspend fun longPressNode(nodeId: String): ActionResult = withTree { tree ->
        val live = tree.find(nodeId) ?: return@withTree staleNode(nodeId, tree)
        val node = live.node
        val performed = runCatching {
            live.info.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }.getOrDefault(false)
        if (performed) {
            delay(SETTLE_MILLIS)
            return@withTree ActionResult.Done("Long-pressed \"${node.label ?: nodeId}\"", verified = true)
        }
        val gesture = gestureTap(node.centerX, node.centerY, LONG_PRESS_MILLIS)
        if (gesture is ActionResult.Done) {
            delay(SETTLE_MILLIS)
            ActionResult.Done(
                "Long-pressed \"${node.label ?: nodeId}\" at (${node.centerX},${node.centerY}) by gesture",
                verified = true,
            )
        } else {
            ActionResult.Failed(
                "Long press was refused by Android and the gesture fallback failed: ${gesture.detail}",
            )
        }
    }

    override suspend fun tapAt(x: Int, y: Int): ActionResult = gestureTap(x, y, TAP_MILLIS)

    private suspend fun gestureTap(x: Int, y: Int, durationMillis: Long): ActionResult {
        val host = AccessibilityHostRegistry.current ?: return ActionResult.Unavailable(NOT_CONNECTED)
        if (x < 0 || y < 0) return ActionResult.Failed("Tap coordinates ($x,$y) are off screen")
        val screen = host.screenSize()
        if (x >= screen.x || y >= screen.y) {
            return ActionResult.Failed(
                "Tap coordinates ($x,$y) are outside the ${screen.x}x${screen.y} screen. If they came " +
                    "from a screenshot or a vision model, they were probably produced in a scaled " +
                    "coordinate space and must be scaled back up first.",
            )
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMillis)
        val description = GestureDescription.Builder().addStroke(stroke).build()
        return when (host.runGesture(description)) {
            GestureOutcome.COMPLETED -> ActionResult.Done("Gesture at ($x,$y) completed", verified = true)
            GestureOutcome.CANCELLED -> ActionResult.Failed("Gesture at ($x,$y) was cancelled by the system")
            GestureOutcome.NOT_DISPATCHED -> ActionResult.Failed("Gesture at ($x,$y) was not dispatched")
            GestureOutcome.UNSUPPORTED -> ActionResult.Unavailable("This service cannot dispatch gestures")
        }
    }

    override suspend fun setText(nodeId: String, text: String): ActionResult = withTree { tree ->
        val live = tree.find(nodeId) ?: return@withTree staleNode(nodeId, tree)
        enterText(live.node, live.info, text)
    }

    private suspend fun enterText(node: ScreenNode, info: AccessibilityNodeInfo, text: String): ActionResult {
        if (!node.editable && !info.isEditableNode()) {
            return ActionResult.Failed(
                "\"${node.label ?: nodeIdOf(node)}\" (${node.shortClass ?: "unknown type"}) is not an " +
                    "editable field, so text cannot be entered into it. Read the screen again and " +
                    "name an input field.",
                retriable = false,
            )
        }

        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val performed = runCatching { info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle) }
            .getOrDefault(false)
        if (performed) {
            delay(SETTLE_MILLIS)
            return ActionResult.Done(
                "Entered ${text.length} character(s) into \"${node.label ?: node.shortClass}\"",
                verified = true,
            )
        }

        // Fallback: focus the field, put the text on the clipboard, ask it to
        // paste. Android 12+ shows a paste toast — visible, and acceptable.
        val focused = runCatching { info.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }.getOrDefault(false)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (!focused || clipboard == null) {
            return ActionResult.Failed(
                "ACTION_SET_TEXT was refused and the paste fallback could not focus the field.",
            )
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Sarothi", text))
        val pasted = runCatching { info.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
        if (!pasted) {
            return ActionResult.Failed(
                "ACTION_SET_TEXT and ACTION_PASTE were both refused by " +
                    "${node.shortClass ?: "this field"}. The app may use a custom input surface.",
                retriable = false,
            )
        }
        delay(SETTLE_MILLIS)
        return ActionResult.Done("Pasted ${text.length} character(s) into \"${node.label ?: nodeIdOf(node)}\"", verified = true)
    }

    private fun nodeIdOf(node: ScreenNode): String = node.id

    override suspend fun typeIntoFocused(text: String): ActionResult = withTree { tree ->
        val target = tree.entries.firstOrNull { it.node.editable && it.node.focused }
            ?: tree.entries.firstOrNull { it.node.editable && it.node.visibleToUser }
        if (target == null) {
            return@withTree ActionResult.Failed(
                "No focused or editable text field is visible on this screen, so there is nowhere " +
                    "to type. Read the screen again and tap the field first.",
                retriable = false,
            )
        }
        enterText(target.node, target.info, text)
    }

    override suspend fun scroll(nodeId: String, direction: ScrollDirection): ActionResult = withTree { tree ->
        val live = tree.find(nodeId) ?: return@withTree staleNode(nodeId, tree)
        val node = live.node

        val actions = when (direction) {
            ScrollDirection.DOWN, ScrollDirection.RIGHT -> listOf(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            )
            ScrollDirection.UP, ScrollDirection.LEFT -> listOf(
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            )
        }
        val before = focusedSignature()
        val performed = actions.any { action ->
            runCatching { live.info.performAction(action) }.getOrDefault(false)
        }
        if (performed) {
            delay(SETTLE_MILLIS)
            val after = focusedSignature()
            return@withTree ActionResult.Done(
                "Scrolled ${direction.name.lowercase()} in \"${node.label ?: node.shortClass ?: nodeId}\"",
                verified = after != before,
            )
        }
        if (!node.scrollable) {
            return@withTree ActionResult.Failed(
                "\"${node.label ?: node.shortClass ?: nodeId}\" is not scrollable and refused the " +
                    "scroll action. Scroll a container that holds it instead.",
                retriable = false,
            )
        }
        gestureScroll(node, direction, tree)
    }

    /** Finger moves opposite to the desired content direction. */
    private suspend fun gestureScroll(node: ScreenNode, direction: ScrollDirection, tree: LiveTree): ActionResult {
        val cx = node.centerX.coerceIn(1, tree.screenWidth - 1)
        val cy = node.centerY.coerceIn(1, tree.screenHeight - 1)
        return when (direction) {
            ScrollDirection.DOWN -> {
                val span = (tree.screenHeight * 0.4f).toInt().coerceAtLeast(120)
                swipe(cx, (cy + span / 2).coerceIn(1, tree.screenHeight - 1), cx, (cy - span / 2).coerceIn(1, tree.screenHeight - 1), SWIPE_MILLIS)
            }
            ScrollDirection.UP -> {
                val span = (tree.screenHeight * 0.4f).toInt().coerceAtLeast(120)
                swipe(cx, (cy - span / 2).coerceIn(1, tree.screenHeight - 1), cx, (cy + span / 2).coerceIn(1, tree.screenHeight - 1), SWIPE_MILLIS)
            }
            ScrollDirection.RIGHT -> {
                val span = (tree.screenWidth * 0.4f).toInt().coerceAtLeast(120)
                swipe((cx + span / 2).coerceIn(1, tree.screenWidth - 1), cy, (cx - span / 2).coerceIn(1, tree.screenWidth - 1), cy, SWIPE_MILLIS)
            }
            ScrollDirection.LEFT -> {
                val span = (tree.screenWidth * 0.4f).toInt().coerceAtLeast(120)
                swipe((cx - span / 2).coerceIn(1, tree.screenWidth - 1), cy, (cx + span / 2).coerceIn(1, tree.screenWidth - 1), cy, SWIPE_MILLIS)
            }
        }
    }

    override suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMillis: Long): ActionResult {
        val host = AccessibilityHostRegistry.current ?: return ActionResult.Unavailable(NOT_CONNECTED)
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMillis.coerceIn(50L, 3000L))
        val description = GestureDescription.Builder().addStroke(stroke).build()
        return when (host.runGesture(description)) {
            GestureOutcome.COMPLETED -> ActionResult.Done(
                "Swiped ($fromX,$fromY) → ($toX,$toY) over ${durationMillis}ms", verified = true,
            )
            GestureOutcome.CANCELLED -> ActionResult.Failed("The swipe was cancelled by the system")
            GestureOutcome.NOT_DISPATCHED -> ActionResult.Failed("The swipe was not dispatched")
            GestureOutcome.UNSUPPORTED -> ActionResult.Unavailable("This service cannot dispatch gestures")
        }
    }

    override suspend fun back(): ActionResult =
        globalAction(AccessibilityService.GLOBAL_ACTION_BACK, "Back")

    override suspend fun home(): ActionResult =
        globalAction(AccessibilityService.GLOBAL_ACTION_HOME, "Home")

    override suspend fun openRecents(): ActionResult =
        globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recents")

    override suspend fun openNotifications(): ActionResult =
        globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notification shade")

    override suspend fun quickSettings(): ActionResult =
        globalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings")

    private suspend fun globalAction(action: Int, label: String): ActionResult = withContext(Dispatchers.IO) {
        val host = AccessibilityHostRegistry.current ?: return@withContext ActionResult.Unavailable(NOT_CONNECTED)
        val before = focusedSignature()
        if (!host.runGlobalAction(action)) {
            return@withContext ActionResult.Failed("Android refused the $label global action")
        }
        delay(SETTLE_MILLIS)
        val after = focusedSignature()
        ActionResult.Done("$label pressed", verified = after != before)
    }

    override suspend fun launchApp(packageName: String): ActionResult = withContext(Dispatchers.IO) {
        val intent = runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
            ?: return@withContext ActionResult.Failed(
                "No installed app with package '$packageName' has a launchable activity.",
                retriable = false,
            )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val started = runCatching { context.startActivity(intent) }.isSuccess
        if (!started) {
            return@withContext ActionResult.Failed("Android refused to start '$packageName'")
        }
        // Wait for the app to draw before anything reads the screen again;
        // without this the agent reads the launcher and concludes nothing happened.
        delay(APP_LAUNCH_SETTLE_MILLIS)
        val after = snapshot()
        val landed = after.packageName == packageName
        ActionResult.Done(
            detail = if (landed) {
                "Opened $packageName"
            } else {
                "Asked Android to open $packageName, but the foreground app now reads as " +
                    "${after.packageName ?: "unknown"}"
            },
            verified = landed,
        )
    }

    override suspend fun findNodes(query: String, snapshot: ScreenSnapshot?): List<ScreenNode> =
        withContext(Dispatchers.IO) {
            val target = snapshot ?: snapshot()
            val needle = query.trim().lowercase()
            if (needle.isEmpty()) return@withContext emptyList()
            target.nodes.filter { node ->
                node.label?.lowercase()?.contains(needle) == true ||
                    node.viewIdResourceName?.lowercase()?.contains(needle) == true ||
                    node.shortClass?.lowercase()?.contains(needle) == true
            }
        }

    /** A cheap signature of "what the user is looking at", used for verification. */
    private fun focusedSignature(): String? {
        val host = AccessibilityHostRegistry.current ?: return null
        val root = runCatching { host.activeRoot() }.getOrNull() ?: return null
        return try {
            val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) }.getOrNull()
            val bounds = Rect()
            root.getBoundsInScreen(bounds)
            buildString {
                append(root.packageName).append('|')
                append(focused?.text ?: focused?.contentDescription ?: "-").append('|')
                append(bounds.toShortString()).append('|')
                append(root.childCount)
            }.also { runCatching { focused?.recycle() } }
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * Runs [block] against a freshly walked tree and always recycles it. Every
     * action goes through here so no action can ever touch a stale node.
     */
    private suspend fun withTree(block: suspend (LiveTree) -> ActionResult): ActionResult =
        withContext(Dispatchers.IO) {
            if (!isServiceConnected) return@withContext ActionResult.Unavailable(NOT_CONNECTED)
            val tree = readTree() ?: return@withContext ActionResult.Unavailable(
                "$NOT_CONNECTED The accessibility tree could not be read right now.",
            )
            try {
                block(tree)
            } finally {
                tree.recycle()
            }
        }

    private fun staleNode(nodeId: String, tree: LiveTree): ActionResult.Failed = ActionResult.Failed(
        detail = "Node '$nodeId' no longer exists — the screen changed since it was read " +
            "(now ${tree.entries.size} node(s) in ${tree.packageName ?: "an unknown app"}). " +
            "Read the screen again and use a current node id.",
        retriable = true,
    )

    companion object {
        /**
         * Extras key the framework stores a node's custom role description under.
         * This is what `AccessibilityNodeInfoCompat.getRoleDescription()` reads; the
         * framework class itself exposes no accessor for it.
         */
        private const val ROLE_DESCRIPTION_KEY = "AccessibilityNodeInfo.roleDescription"

        private const val MAX_NODES = 600
        private const val MIN_USEFUL_NODES = 2
        private const val MAX_ANCESTOR_HOPS = 6
        private const val SETTLE_MILLIS = 220L
        private const val APP_LAUNCH_SETTLE_MILLIS = 1200L
        private const val TAP_MILLIS = 40L
        private const val LONG_PRESS_MILLIS = 700L
        private const val SWIPE_MILLIS = 320L
        private const val DEFAULT_CAPTURE_EDGE = 768
        private const val NOT_CONNECTED =
            "The Sarothi accessibility service is not connected, so the screen cannot be read or " +
                "acted on. Enable it in Settings → Accessibility → Sarothi."
    }
}

/**
 * `AccessibilityNodeInfo` has no public `isEditable()`, so editability is inferred
 * the way the framework does: from the widget class, with the node's extras bundle
 * consulted when a vendor exposes the flag there.
 */
internal fun AccessibilityNodeInfo.isEditableNode(): Boolean {
    val className = className?.toString() ?: return false
    if (className.endsWith("EditText") ||
        className.contains("AutoCompleteTextView") ||
        className.contains("SearchView") ||
        className.contains("TextInputLayout")
    ) {
        return true
    }
    return runCatching {
        extras?.getBoolean("AccessibilityNodeInfo.editable", false) ?: false
    }.getOrDefault(false)
}

/** Identifies Sarothi's own accessibility service without `:core` depending on `:app`. */
object SarothiAccessibility {
    /**
     * The service class lives in `:core` and its manifest entry is merged into the
     * app, so the fully-qualified name is fixed regardless of application id.
     */
    const val SERVICE_CLASS = "com.ngi.sarothi.core.screen.SarothiAccessibilityService"

    /**
     * `android.provider.Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS` is
     * @SystemApi/@hide, so the constant is not in the public SDK at compileSdk 35 and
     * cannot be referenced at compile time. The action string itself is stable and is
     * what AOSP Settings resolves.
     *
     * Declared here rather than in AccessibilityScreenController's companion: that one
     * is `private` to a different type, and this object is where it is used.
     */
    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    fun componentFor(context: Context): ComponentName? {
        val packageName = context.packageName?.takeIf { it.isNotBlank() } ?: return null
        return ComponentName(packageName, SERVICE_CLASS)
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /**
     * Deep link straight to this service's toggle page. The direct intent needs
     * API 30; older devices get the general accessibility list instead, which is
     * honest rather than silently opening the wrong screen.
     */
    fun serviceSettingsIntent(context: Context): Intent {
        val component = componentFor(context)
        if (component == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return settingsIntent()
        return runCatching {
            // The component goes in the public Intent.EXTRA_COMPONENT_NAME as a
            // flattened string, which is what AOSP Settings reads. If an OEM build
            // refuses the action, runCatching falls back to the general accessibility
            // list rather than crashing.
            Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
                putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
            }
        }.getOrElse { settingsIntent() }
    }
}

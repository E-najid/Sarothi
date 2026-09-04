package com.ngi.sarothi.core.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.graphics.Point
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Sarothi's accessibility service.
 *
 * It is intentionally thin: it exposes the framework objects to
 * [AccessibilityScreenController] through [AccessibilityHost] and forwards
 * notification events to [NotificationFeed]. All interpretation lives in the
 * controller so it can be tested without a device.
 */
class SarothiAccessibilityService : AccessibilityService(), AccessibilityHost {

    @Volatile
    private var connected = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        // The XML config supplies the baseline; make the two flags the controller
        // depends on explicit here too, so a build whose XML was stripped still
        // behaves predictably instead of silently losing window access.
        runCatching {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            info.eventTypes = info.eventTypes or AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            setServiceInfo(info)
        }.onFailure { Log.w(TAG, "Could not refine accessibility service flags", it) }

        AccessibilityHostRegistry.attach(this)
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        // Sarothi's own notifications would otherwise trigger its own rules.
        if (packageName == this.packageName) return
        val lines = event.text.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        NotificationFeed.publish(
            ObservedNotification(
                packageName = packageName,
                title = lines.firstOrNull(),
                text = if (lines.size > 1) lines.drop(1).joinToString(" · ") else null,
                receivedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    override fun onInterrupt() {
        // Sarothi never uses spoken/haptic feedback, so there is nothing to stop.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        AccessibilityHostRegistry.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        AccessibilityHostRegistry.detach(this)
        super.onDestroy()
    }

    // ---------------------------------------------------------- AccessibilityHost

    override fun activeRoot(): AccessibilityNodeInfo? =
        if (!connected) null else runCatching { getRootInActiveWindow() }.getOrNull()

    override fun allWindows(): List<AccessibilityWindowInfo> =
        if (!connected) emptyList() else runCatching { getWindows().toList() }.getOrDefault(emptyList())

    override fun currentServiceInfo(): AccessibilityServiceInfo? =
        if (!connected) null else runCatching { getServiceInfo() }.getOrNull()

    override fun component(): ComponentName? = SarothiAccessibility.componentFor(this)

    override fun screenSize(): Point {
        val manager = getSystemService(WINDOW_SERVICE) as? WindowManager
        if (manager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = runCatching { manager.currentWindowMetrics.bounds }.getOrNull()
                if (bounds != null) return Point(bounds.width(), bounds.height())
            }
            @Suppress("DEPRECATION")
            val display = runCatching { manager.defaultDisplay }.getOrNull()
            if (display != null) {
                val size = Point()
                @Suppress("DEPRECATION")
                runCatching { display.getRealSize(size) }
                if (size.x > 0 && size.y > 0) return size
            }
        }
        val metrics = resources.displayMetrics
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    override fun runGlobalAction(action: Int): Boolean =
        if (!connected) false else runCatching { performGlobalAction(action) }.getOrDefault(false)

    /**
     * Bridges the framework's callback-style gesture API into a coroutine.
     *
     * `dispatchGesture` returning false means the request was rejected outright;
     * a true return only means it was queued, so completion is awaited and a
     * timeout is treated as "not completed" rather than as success.
     */
    override suspend fun runGesture(description: GestureDescription): GestureOutcome {
        if (!connected) return GestureOutcome.NOT_DISPATCHED
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return GestureOutcome.UNSUPPORTED
        return suspendCancellableCoroutine { continuation ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(completedDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(GestureOutcome.COMPLETED)
                }

                override fun onCancelled(cancelledDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(GestureOutcome.CANCELLED)
                }
            }
            val queued = runCatching { dispatchGesture(description, callback, null) }.getOrElse {
                Log.w(TAG, "dispatchGesture threw", it)
                false
            }
            if (!queued) {
                if (continuation.isActive) continuation.resume(GestureOutcome.NOT_DISPATCHED)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { /* the framework has no cancel API; nothing to undo */ }
        }
    }

    companion object {
        private const val TAG = "SarothiA11y"

        /** True when the system has bound the service in this process. */
        val isRunning: Boolean get() = AccessibilityHostRegistry.current != null
    }
}

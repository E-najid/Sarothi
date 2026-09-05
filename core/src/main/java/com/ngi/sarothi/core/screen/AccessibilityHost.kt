package com.ngi.sarothi.core.screen

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.graphics.Point
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.GestureDescription
import android.view.accessibility.AccessibilityWindowInfo

/** Result of a system gesture dispatch. */
enum class GestureOutcome { COMPLETED, CANCELLED, NOT_DISPATCHED, UNSUPPORTED }

/**
 * The slice of `AccessibilityService` that the perception layer needs.
 *
 * Method names deliberately differ from the framework's (`allWindows` rather than
 * `windows`, `runGlobalAction` rather than `performGlobalAction`): several
 * `AccessibilityService` members are `final`, and an implementing subclass must
 * not look like it is overriding them.
 *
 * The service lives in `:core` so the class name in the merged manifest is fixed;
 * everything that interprets the tree and decides what to do is behind this
 * interface and can be tested with a fake host.
 */
interface AccessibilityHost {
    fun activeRoot(): AccessibilityNodeInfo?
    fun allWindows(): List<AccessibilityWindowInfo>
    fun currentServiceInfo(): AccessibilityServiceInfo?
    fun component(): ComponentName?
    fun screenSize(): Point
    fun runGlobalAction(action: Int): Boolean
    suspend fun runGesture(description: GestureDescription): GestureOutcome
}

/**
 * Where the app's service publishes itself.
 *
 * A static holder is the standard shape for accessibility services: the system
 * creates the instance, so it cannot be constructor-injected. Detaching on
 * `onUnbind`/`onServiceDisconnected` is mandatory, otherwise core would keep
 * acting through a dead service.
 */
object AccessibilityHostRegistry {
    @Volatile
    private var host: AccessibilityHost? = null

    fun attach(host: AccessibilityHost) {
        this.host = host
    }

    fun detach(host: AccessibilityHost) {
        if (this.host === host) this.host = null
    }

    val current: AccessibilityHost? get() = host
}

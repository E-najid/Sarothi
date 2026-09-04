package com.ngi.sarothi.core.screen

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ngi.sarothi.core.error.NativeRuntimeUnavailableException
import com.ngi.sarothi.core.runtime.NativeBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sarothi's central promise, checked on a real device: **when something is not available,
 * it says so instead of looking like it worked.**
 *
 * A JVM test cannot check this. `AccessibilityScreenController` reads the system
 * accessibility setting, the MediaProjection registry and the live service binding, and
 * `NativeBridge` calls `System.loadLibrary`. On a freshly installed build none of that is
 * present, which makes an emulator the perfect place to prove the degradation is honest:
 * every capability must report itself missing, with a reason a user can act on, and no
 * action may come back as `Done`.
 */
@RunWith(AndroidJUnit4::class)
class HonestDegradationInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    private fun controller(): AccessibilityScreenController =
        AccessibilityScreenController(context)

    @Test
    fun a_fresh_install_reports_screen_access_as_unavailable_and_says_why() {
        val controller = controller()
        // The premise of every test below. If a device really has the service running,
        // skipping is the honest outcome — asserting "not connected" would be a lie.
        assumeFalse(controller.isServiceConnected)

        val availability = controller.availability()

        assertFalse("No service is bound, so nothing can act on the screen", availability.accessibilityConnected)
        assertFalse("Accessibility is off in system Settings on a fresh install", availability.accessibilityEnabledInSettings)
        assertFalse("No MediaProjection consent exists yet", availability.capturePermissionGranted)
        assertFalse("canAct must follow accessibilityConnected", availability.canAct)
        assertFalse("canReadScreen must be false when neither path is open", availability.canReadScreen)

        // The reason must be actionable text, not an empty string or a bare "unavailable".
        assertTrue("availability() gave no explanation", availability.detail.isNotBlank())
        assertTrue(
            "The explanation does not mention the accessibility service: ${availability.detail}",
            availability.detail.contains("accessibility service"),
        )
        assertTrue(
            "The explanation does not mention screen capture: ${availability.detail}",
            availability.detail.contains("Screen capture") || availability.detail.contains("screen capture"),
        )
    }

    @Test
    fun every_action_that_needs_the_service_reports_unavailable_instead_of_success() = runBlocking {
        val controller = controller()
        assumeFalse(controller.isServiceConnected)

        // The full surface the agent can act through. If any one of these returned Done
        // with no service bound, the agent would report a completed step that never
        // happened — the exact failure the project refuses to allow.
        val actions: List<Pair<String, suspend () -> ActionResult>> = listOf(
            "tapNode" to { controller.tapNode("n0") },
            "longPressNode" to { controller.longPressNode("n0") },
            "tapAt" to { controller.tapAt(10, 10) },
            "setText" to { controller.setText("n0", "hello") },
            "typeIntoFocused" to { controller.typeIntoFocused("hello") },
            "scroll" to { controller.scroll("n0", ScrollDirection.DOWN) },
            "swipe" to { controller.swipe(10, 100, 10, 20) },
            "back" to { controller.back() },
            "home" to { controller.home() },
            "openRecents" to { controller.openRecents() },
            "openNotifications" to { controller.openNotifications() },
            "quickSettings" to { controller.quickSettings() },
        )

        val outcomes = actions.map { (name, action) -> name to action() }

        val claimedSuccess = outcomes.filter { (_, result) -> result is ActionResult.Done }
        assertTrue(
            "These reported success with no accessibility service connected: " +
                claimedSuccess.joinToString { (name, result) -> "$name -> ${result.detail}" },
            claimedSuccess.isEmpty(),
        )

        outcomes.forEach { (name, result) ->
            assertTrue(
                "$name returned ${result::class.simpleName} (\"${result.detail}\"); with no service " +
                    "bound it must be Unavailable",
                result is ActionResult.Unavailable,
            )
            assertTrue("$name gave an empty explanation", result.detail.isNotBlank())
            assertTrue(
                "$name did not tell the user how to fix it: \"${result.detail}\"",
                result.detail.contains("Settings"),
            )
        }
    }

    @Test
    fun a_snapshot_with_nothing_readable_says_so_explicitly() = runBlocking {
        val controller = controller()
        assumeFalse(controller.isServiceConnected)
        assumeFalse(controller.hasCapturePermission)

        val snapshot = controller.snapshot(preferTree = true)

        assertEquals(SnapshotSource.UNAVAILABLE, snapshot.source)
        assertTrue("A snapshot claimed to contain nodes it could not have read", snapshot.nodes.isEmpty())
        assertTrue("No explanation was attached to an unreadable screen", snapshot.limitations.isNotEmpty())
        assertTrue(
            "The explanation does not say Sarothi cannot act: ${snapshot.limitations}",
            snapshot.limitations.any { it.contains("cannot act") },
        )
        assertTrue(
            "The explanation does not mention the missing capture permission: ${snapshot.limitations}",
            snapshot.limitations.any { it.contains("capture permission") },
        )

        // Reading it with the tree explicitly skipped must degrade the same way.
        val screenshotOnly = controller.snapshot(preferTree = false)
        assertEquals(SnapshotSource.UNAVAILABLE, screenshotOnly.source)
        assertTrue(screenshotOnly.nodes.isEmpty())
    }

    @Test
    fun capture_without_consent_is_denied_and_names_what_the_user_must_do() = runBlocking {
        val controller = controller()
        assumeFalse(controller.hasCapturePermission)

        val captured = controller.captureScreen(maxDimension = 512)

        assertTrue(
            "captureScreen returned ${captured::class.simpleName} with no MediaProjection consent",
            captured is CaptureResult.Denied,
        )
        val denied = captured as CaptureResult.Denied
        assertTrue("needsUserConsent must be set so the UI can ask", denied.needsUserConsent)
        assertTrue("The denial gave no reason", denied.reason.isNotBlank())
    }

    @Test
    fun describing_a_screen_without_a_vision_model_reports_unavailable() = runBlocking {
        // vision = null is exactly what a build without the downloaded VLM looks like.
        val controller = AccessibilityScreenController(context, vision = null)

        val grounding = controller.describeScreen("What is on the screen?")

        assertFalse("A build with no vision model claimed it could describe the screen", grounding.available)
        assertTrue("No reason was given for the missing vision model", grounding.reason?.isNotBlank() == true)
        assertEquals(null, grounding.description)
        assertTrue("Invented taps with no vision model", grounding.taps.isEmpty())
        assertEquals(null, grounding.modelId)
        assertEquals(0L, grounding.elapsedMillis)
    }

    @Test
    fun finding_nodes_on_an_unreadable_screen_returns_nothing_rather_than_guessing() = runBlocking {
        val controller = controller()
        assumeFalse(controller.isServiceConnected)

        val found = controller.findNodes("settings")

        assertTrue(
            "findNodes returned ${found.size} node(s) from a screen it could not read",
            found.isEmpty(),
        )
        assertTrue(controller.findNodes("   ").isEmpty())
    }

    @Test
    fun launching_an_installed_app_really_works_without_the_accessibility_service() = runBlocking {
        // The positive control for everything above: `launchApp` goes through the
        // PackageManager, not the accessibility service, so it must succeed on a device
        // where every other action honestly reports itself unavailable. Without this,
        // "everything returns Unavailable" could pass against a build that does nothing.
        val controller = controller()
        assumeFalse(controller.isServiceConnected)

        val result = controller.launchApp("com.android.settings")

        assertTrue(
            "Launching an installed app returned ${result::class.simpleName}: ${result.detail}",
            result is ActionResult.Done,
        )
        assertTrue(
            "The result does not name the package it was asked to open: ${result.detail}",
            result.detail.contains("com.android.settings"),
        )
    }

    @Test
    fun launching_a_package_that_is_not_installed_fails_with_a_real_reason() = runBlocking {
        val controller = controller()

        val result = controller.launchApp("com.ngi.sarothi.this.package.does.not.exist")

        assertTrue(
            "Launching a nonexistent package returned ${result::class.simpleName}",
            result is ActionResult.Failed,
        )
        val failed = result as ActionResult.Failed
        assertFalse("Retrying cannot install a missing app", failed.retriable)
        assertTrue(
            "The failure does not name the package: ${failed.detail}",
            failed.detail.contains("com.ngi.sarothi.this.package.does.not.exist"),
        )
    }

    @Test
    fun the_native_runtime_states_exactly_one_of_loaded_or_a_reason() {
        val loaded = NativeBridge.isLoaded
        val failure = NativeBridge.loadFailure

        // Never both, never neither: "not loaded and no explanation" is how a build ends
        // up telling the user something unhelpful like "model failed".
        assertNotEquals(
            "NativeBridge reported loaded=$loaded together with failure=\"$failure\"",
            loaded,
            failure != null,
        )

        if (loaded) {
            // If the CI build did bundle libsarothi_native.so, using it must not throw.
            NativeBridge.requireLoaded("instrumentation")
        } else {
            assertTrue("The load failure carries no detail", failure!!.isNotBlank())
            try {
                NativeBridge.requireLoaded("instrumentation")
                throw AssertionError("requireLoaded() did not throw although the runtime is not loaded")
            } catch (expected: NativeRuntimeUnavailableException) {
                assertTrue(
                    "The exception does not name the component: ${expected.message}",
                    expected.message?.contains("instrumentation") == true,
                )
                assertTrue(
                    "The exception does not name the library that is missing: ${expected.message}",
                    expected.message?.contains("sarothi_native") == true,
                )
            }

            // lastErrorOr must surface the real load failure, not the caller's fallback.
            val reported = NativeBridge.lastErrorOr("generic fallback")
            assertNotEquals("generic fallback", reported)
            assertTrue(reported.isNotBlank())
        }
    }
}

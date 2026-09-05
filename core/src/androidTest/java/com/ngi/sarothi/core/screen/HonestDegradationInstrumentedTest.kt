package com.ngi.sarothi.core.screen

import android.content.Context
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ngi.sarothi.core.runtime.NativeBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What Sarothi's screen layer says about itself when it genuinely cannot see or touch
 * anything: no accessibility service bound, no MediaProjection consent, no OCR engine, no
 * vision model.
 *
 * This is the property a JVM test cannot reach and the one that matters most. An
 * orchestrator told "the screen has nothing on it" will happily plan a tap on a button
 * that was never read; one told "the accessibility service is not connected, enable it in
 * Settings → Accessibility → Sarothi" stops and tells the user. Every assertion below is
 * therefore about the *content of the refusal*, not about whether the refusal happened --
 * the emulator boots with neither registry attached, so refusal is the guaranteed starting
 * state.
 *
 * Tests that only make sense with nothing granted are guarded by [assumeFalse]: on a phone
 * where a person has enabled the service they report as skipped rather than failing,
 * because a working Sarothi is not a broken test.
 */
@RunWith(AndroidJUnit4::class)
class HonestDegradationInstrumentedTest {

    private lateinit var context: Context
    private lateinit var controller: AccessibilityScreenController

    /** Whether the accessibility service has published itself as the host. */
    private val serviceBound: Boolean
        get() = AccessibilityHostRegistry.current != null

    /** Whether a MediaProjection-backed capture source says it is ready to hand out frames. */
    private val captureReady: Boolean
        get() = ScreenshotSourceRegistry.current?.isReady == true

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Built with the defaults the controller declares: the screenshot registry (empty
        // until the capture service attaches), NoOcrEngine, and no vision model. That is
        // precisely the state this suite is about -- a controller with nothing behind it.
        controller = AccessibilityScreenController(context)
    }

    @Test
    fun availability_reports_exactly_what_this_device_grants() {
        val availability = controller.availability()
        val host = AccessibilityHostRegistry.current

        // The controller derives these from the registries. If it ever cached or guessed
        // instead, the UI would offer a run the agent then refuses.
        assertEquals(
            "isServiceConnected does not match the host registry",
            host != null,
            controller.isServiceConnected,
        )
        assertEquals(
            "hasCapturePermission does not match the capture registry",
            captureReady,
            controller.hasCapturePermission,
        )
        assertEquals(
            "availability() is connected only when the service also reports a configuration",
            host != null && host.currentServiceInfo() != null,
            availability.accessibilityConnected,
        )
        assertEquals(
            "availability() disagrees with the controller about capture consent",
            controller.hasCapturePermission,
            availability.capturePermissionGranted,
        )
        assertEquals(
            "availability() does not report what system Settings says about the service",
            serviceEnabledInSystemSettings(),
            availability.accessibilityEnabledInSettings,
        )
        assertEquals(
            "canAct must mean exactly 'the service is bound and configured'",
            availability.accessibilityConnected,
            availability.canAct,
        )
        assertEquals(
            "canReadScreen must mean exactly 'tree or capture'",
            availability.accessibilityConnected || availability.capturePermissionGranted,
            availability.canReadScreen,
        )
        assertTrue(
            "availability() explained nothing: $availability",
            availability.detail.isNotBlank(),
        )
        // With neither source present, the detail has to name both gaps -- that sentence is
        // what the Setup screen and the agent's refusal are built from.
        if (!availability.accessibilityConnected && !availability.capturePermissionGranted) {
            assertTrue(
                "nothing is granted but the explanation omits the service: '${availability.detail}'",
                "accessibility" in availability.detail.lowercase(),
            )
            assertTrue(
                "nothing is granted but the explanation omits capture: '${availability.detail}'",
                "capture" in availability.detail.lowercase() || "screenshot" in availability.detail.lowercase(),
            )
        }
    }

    @Test
    fun a_snapshot_with_nothing_readable_says_so_explicitly() = runBlocking {
        assumeFalse("a capture source is ready on this device, so a real snapshot is possible", captureReady)

        val snapshot = controller.snapshot()

        assertEquals(
            "with no service and no capture consent the source must be UNAVAILABLE, not an " +
                "empty-looking ${snapshot.source}",
            SnapshotSource.UNAVAILABLE,
            snapshot.source,
        )
        assertTrue(
            "an UNAVAILABLE snapshot that lists no limitation leaves the agent with nothing " +
                "to tell the user",
            snapshot.limitations.isNotEmpty(),
        )
        assertTrue(
            "an UNAVAILABLE snapshot invented ${snapshot.nodes.size} node(s): ${snapshot.nodes}",
            snapshot.nodes.isEmpty(),
        )
        assertNull(
            "an UNAVAILABLE snapshot produced OCR text: '${snapshot.ocrText}'",
            snapshot.ocrText,
        )
        assertTrue(
            "the explanation must name the missing consent, so the agent asks for the right " +
                "thing: ${snapshot.limitations}",
            snapshot.limitations.any { "capture" in it.lowercase() || "screenshot" in it.lowercase() },
        )
    }

    @Test
    fun capture_screen_refuses_without_consent_and_names_consent_as_the_missing_thing() = runBlocking {
        assumeFalse("a capture source is ready on this device", captureReady)

        when (val capture = controller.captureScreen(maxDimension = 512)) {
            is CaptureResult.Captured -> fail(
                "captureScreen produced a ${capture.width}x${capture.height} bitmap with no " +
                    "consent granted -- a screenshot the user never agreed to",
            )

            is CaptureResult.Denied -> {
                assertTrue("capture was refused without saying why", capture.reason.isNotBlank())
                assertTrue(
                    "the refusal must flag that user consent is what is missing, so the UI " +
                    "shows the consent action instead of a generic error: ${capture.reason}",
                    capture.needsUserConsent,
                )
            }
        }
    }

    @Test
    fun node_search_with_nothing_to_search_returns_nothing_rather_than_guessing() = runBlocking {
        assumeFalse("the accessibility service is bound", serviceBound)
        assumeFalse("a capture source is ready, so there would be something to search", captureReady)

        val found = controller.findNodes("settings")

        assertTrue(
            "findNodes returned ${found.size} node(s) with no tree and no screenshot: $found",
            found.isEmpty(),
        )
    }

    @Test
    fun every_physical_action_is_unavailable_without_the_service_and_says_how_to_fix_it() = runBlocking {
        assumeFalse("the accessibility service is bound", serviceBound)

        val actions = listOf<Pair<String, suspend () -> ActionResult>>(
            "tapAt" to { controller.tapAt(120, 240) },
            "swipe" to { controller.swipe(120, 900, 120, 300) },
            "back" to { controller.back() },
            "home" to { controller.home() },
            "openRecents" to { controller.openRecents() },
            "openNotifications" to { controller.openNotifications() },
            "quickSettings" to { controller.quickSettings() },
            "tapNode" to { controller.tapNode("instrumented.node.that.does.not.exist") },
            "longPressNode" to { controller.longPressNode("instrumented.node.that.does.not.exist") },
            "setText" to { controller.setText("instrumented.node.that.does.not.exist", "text") },
            "typeIntoFocused" to { controller.typeIntoFocused("text") },
            "scroll" to { controller.scroll("instrumented.node.that.does.not.exist", ScrollDirection.DOWN) },
        )

        val outcomes = actions.map { (name, action) -> name to action() }

        outcomes.forEach { (name, result) ->
            assertTrue(
                "$name returned ${result::class.simpleName} ('${result.detail}') with no service " +
                    "bound. ActionResult.Unavailable is the documented answer for 'cannot be " +
                    "attempted at all in this state'; Done would be a lie and Failed would " +
                    "invite the agent to retry something no retry can fix.",
                result is ActionResult.Unavailable,
            )
            assertTrue(
                "$name refused in fewer than 20 characters, which is not a reason a person can " +
                    "act on: '${result.detail}'",
                result.detail.length >= 20,
            )
            assertTrue(
                "$name's refusal does not name the thing to turn on: '${result.detail}'",
                "accessibility" in result.detail.lowercase(),
            )
        }
    }

    @Test
    fun launching_a_package_with_no_launchable_activity_fails_without_offering_a_retry() = runBlocking {
        val target = context.packageName
        assumeTrue(
            "$target has a launchable activity on this device, so the refusal path cannot be " +
                "exercised here",
            runCatching { context.packageManager.getLaunchIntentForPackage(target) }.getOrNull() == null,
        )

        val result = controller.launchApp(target)

        assertTrue(
            "launchApp answered ${result::class.simpleName} for a package with no launchable " +
                "activity: '${result.detail}'",
            result is ActionResult.Failed,
        )
        val failed = result as ActionResult.Failed
        assertFalse(
            "no retry can make a package launchable, so offering one sends the agent round the " +
                "same dead end: '${failed.detail}'",
            failed.retriable,
        )
        assertTrue(
            "the failure must name the package it could not launch: '${failed.detail}'",
            target in failed.detail,
        )
    }

    @Test
    fun the_vision_fallback_reports_what_is_missing_instead_of_an_empty_answer() = runBlocking {
        val grounding = controller.describeScreen("the search box at the top of the screen")

        assertFalse(
            "describeScreen claimed to be available with no vision model wired in: $grounding",
            grounding.available,
        )
        assertTrue(
            "describeScreen was unavailable without giving a reason",
            !grounding.reason.isNullOrBlank(),
        )
        assertTrue(
            "an unavailable grounding must propose no taps, or the agent taps coordinates that " +
                "came from nothing: ${grounding.taps}",
            grounding.taps.isEmpty(),
        )
        assertNull(
            "an unavailable grounding must not carry a description: '${grounding.description}'",
            grounding.description,
        )
    }

    @Test
    fun the_ui_and_the_agent_are_told_the_same_thing() = runBlocking {
        assumeFalse("the accessibility service is bound", serviceBound)
        assumeFalse("a capture source is ready", captureReady)

        // The Setup screen reads availability(); the agent reads snapshot() and findNodes().
        // Three views of one device state. If they can disagree, the UI promises a run the
        // agent then refuses -- which reads to a person as the app being broken.
        val availability = controller.availability()
        val snapshot = controller.snapshot()
        val found = controller.findNodes("settings")

        assertFalse(
            "availability says the screen can be read with neither source present: $availability",
            availability.canReadScreen,
        )
        assertEquals(
            "availability says nothing can be read but the snapshot claims source ${snapshot.source}",
            SnapshotSource.UNAVAILABLE,
            snapshot.source,
        )
        assertTrue(
            "availability says nothing can be read but findNodes returned ${found.size} node(s)",
            found.isEmpty(),
        )
        assertFalse(
            "availability says nothing can be acted on but canAct is true: $availability",
            availability.canAct,
        )
    }

    @Test
    fun a_screen_that_cannot_be_read_produces_no_verified_action_anywhere() = runBlocking {
        assumeFalse("the accessibility service is bound", serviceBound)

        // `verified = true` is Sarothi's claim that it re-read the screen or the node
        // afterwards and saw the expected change. That claim requires something to read.
        // This is the suite's conclusion in one assertion: with nothing readable, no action
        // anywhere may report itself verified.
        val results = listOf(
            controller.tapAt(120, 240),
            controller.swipe(120, 900, 120, 300),
            controller.back(),
            controller.home(),
            controller.typeIntoFocused("text"),
            controller.tapNode("instrumented.node.that.does.not.exist"),
        )

        results.forEach { result ->
            assertFalse(
                "an action claimed verification with no screen to verify against: " +
                    "${result::class.simpleName} '${result.detail}'",
                (result as? ActionResult.Done)?.verified ?: false,
            )
        }
        assertTrue(
            "every action should have refused, but got: " +
                results.joinToString { it::class.simpleName ?: "?" },
            results.none { it is ActionResult.Done },
        )
    }

    @Test
    fun the_native_runtime_states_exactly_one_of_loaded_or_why_not() {
        // CI compiles no JNI, so an emulator holds no libsarothi_native.so. What must never
        // happen is a build that reports neither: `isLoaded` false with no reason leaves
        // Settings -> Models with nothing to show and the agent with nothing to say.
        if (NativeBridge.isLoaded) {
            assertNull(
                "the native runtime is loaded but still reports a failure: '${NativeBridge.loadFailure}'",
                NativeBridge.loadFailure,
            )
        } else {
            val reason = NativeBridge.loadFailure
            assertTrue("the native runtime is unavailable without saying why", !reason.isNullOrBlank())
            assertTrue(
                "the reason does not name the library that is missing: '$reason'",
                "sarothi_native" in (reason ?: ""),
            )
            val refused = runCatching { NativeBridge.requireLoaded("instrumented probe") }
            assertTrue("requireLoaded() let a caller through with no runtime present", refused.isFailure)
            assertTrue(
                "the refusal does not name the component that asked, so the user cannot tell " +
                    "which feature is missing its runtime: '${refused.exceptionOrNull()?.message}'",
                "instrumented probe" in (refused.exceptionOrNull()?.message ?: ""),
            )
        }
    }

    @Test
    fun launching_an_installed_app_works_without_the_service_and_claims_no_more_than_it_saw() = runBlocking {
        val settings = "com.android.settings"
        assumeTrue(
            "$settings has no launchable activity on this device, so the positive control " +
                "cannot be exercised here",
            runCatching { context.packageManager.getLaunchIntentForPackage(settings) }.getOrNull() != null,
        )
        assumeFalse("the accessibility service is bound, so confirmation would be possible", serviceBound)
        assumeFalse("a capture source is ready, so confirmation would be possible", captureReady)

        val result = controller.launchApp(settings)

        // The positive control for this whole suite: launchApp goes through
        // Context.startActivity rather than through the accessibility service, so it must
        // succeed with nothing bound. Without it, "every capability reports Unavailable"
        // would also pass against a controller that does nothing at all.
        assertTrue(
            "launchApp refused an installed, launchable app: ${result::class.simpleName} " +
                "'${result.detail}'",
            result is ActionResult.Done,
        )
        val done = result as ActionResult.Done
        assertFalse(
            "launchApp claimed to have verified a foreground change it had no way to observe",
            done.verified,
        )
        assertTrue(
            "the detail does not admit what it could not confirm, so the agent would report " +
            "an unverified launch as a success: '${done.detail}'",
            "unknown" in done.detail.lowercase(),
        )
    }

    /**
     * Reads the system setting directly, the way a person checking Settings would, so that
     * [ScreenAvailability.accessibilityEnabledInSettings] is compared against the device
     * rather than against the code that produced it.
     */
    private fun serviceEnabledInSystemSettings(): Boolean {
        val component = SarothiAccessibility.componentFor(context) ?: return false
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val needle = component.flattenToString().lowercase()
        return enabled.split(':').any { it.trim().lowercase() == needle }
    }
}

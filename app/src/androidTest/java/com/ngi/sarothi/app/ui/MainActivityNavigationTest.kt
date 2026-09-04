package com.ngi.sarothi.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTextStartingWith
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole UI, walked on a device.
 *
 * Sarothi is one Activity with four tabs and seven screens one tap below "More". Every one
 * of them is reachable from a cold start on a fresh install with no vault, no models and no
 * permissions — and that is exactly the state an emulator is in, so this is the realistic
 * case, not a convenient one. What is being proved:
 *
 *  - launching builds the [com.ngi.sarothi.app.di.AppGraph] and renders instead of crashing;
 *  - all eleven screens compose without throwing;
 *  - a screen with nothing to show says so, and the input that cannot work is disabled
 *    rather than looking ready.
 *
 * There are no test tags in this UI, so the anchors below are the strings the user reads.
 * That is deliberate: if a label changes, the screen a test meant to reach may not be the
 * screen it reaches any more, and a failing test is the right answer.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** Sub-screen titles in the order `More` lists them (the declaration order of `Sub`). */
    private val subScreens = listOf(
        "Persona" to "What Sarothi calls itself",
        "Task history" to "Reload",
        "Audit log" to "Nothing logged yet.",
        "Access" to "Special access",
        "Schedules and rules" to "Notification rules",
        "Connectors" to "Webhooks",
        "Settings" to "Download models over mobile data",
    )

    @Test
    fun a_cold_start_on_a_fresh_install_lands_on_the_task_tab_and_says_why_it_cannot_run() {
        // The bottom bar and the top bar both read "Task" on launch.
        assertTrue(
            "The four tabs are not all on screen",
            listOf("Task", "Vault", "Models", "More").all { label ->
                compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
            },
        )

        compose.onNodeWithText("What should Sarothi do?").assertExists()
        compose.onNodeWithText("No task has run yet.", substring = true).assertExists()

        // No vault is configured on a fresh install, so a task cannot run. The screen has
        // to say that and refuse the input — an enabled box that goes nowhere is the exact
        // "looks like it works" failure this project forbids.
        compose.onNodeWithText("Unlock the vault first (Vault tab).").assertExists()
        compose.onNodeWithText("Run").assertExists()

        // The control is on screen but cannot be used: a disabled text field exposes no
        // SetText action at all, which is what `performTextInput` would have to call.
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun the_vault_tab_offers_a_way_to_choose_a_folder() {
        compose.onNodeWithContentDescription("Vault").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Choose the vault folder").assertExists()
        // The vault screen explains what the passphrase protects; that copy is part of the
        // promise, not decoration.
        compose.onNodeWithText("Sarothi cannot recover it", substring = true).assertExists()
    }

    @Test
    fun the_models_tab_reports_the_real_memory_tier_and_lists_what_can_be_downloaded() {
        compose.onNodeWithContentDescription("Models").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("On-device runtimes").assertExists()

        // RamPolicy reads ActivityManager.getMemoryInfo() on this device; the tier it
        // computes is shown verbatim. An emulator has real memory, so this must resolve to
        // one of the four tiers rather than being blank.
        val tier = compose.onNode(hasTextStartingWith("Memory tier:")).fetchSemanticsNode()
        val shown = tier.config.getOrNull(SemanticsProperties.Text)?.joinToString("") ?: ""
        assertTrue(
            "The memory tier read-out names no tier: '$shown'",
            listOf("VERY_CONSTRAINED", "CONSTRAINED", "COMFORTABLE", "AMPLE")
                .any { shown.contains(it) },
        )

        // Nothing has been downloaded, so the catalogue must offer its models.
        assertTrue(
            "No model is offered for download on a fresh install",
            compose.onAllNodesWithText("Download").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun the_more_tab_lists_every_destination_exactly_once() {
        compose.onNodeWithContentDescription("More").performClick()
        compose.waitForIdle()

        val opens = compose.onAllNodesWithText("Open").fetchSemanticsNodes()
        assertEquals(
            "More should list one entry per sub-screen",
            subScreens.size,
            opens.size,
        )
        subScreens.forEach { (title, _) ->
            compose.onNodeWithText(title).assertExists()
        }
    }

    @Test
    fun every_sub_screen_opens_renders_and_comes_back() {
        compose.onNodeWithContentDescription("More").performClick()
        compose.waitForIdle()

        subScreens.forEachIndexed { index, (title, anchor) ->
            // `More` is a scrolling Column, so the entry may be below the fold.
            compose.onAllNodesWithText("Open")[index].performScrollTo()
            compose.onAllNodesWithText("Open")[index].performClick()
            compose.waitForIdle()

            // The top bar now carries the sub-screen's title, and the list of entries is
            // gone with the screen it belonged to.
            assertTrue(
                "Opening entry $index did not put '$title' in the top bar",
                compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty(),
            )
            assertEquals(
                "'$title' still shows the More list behind it",
                0,
                compose.onAllNodesWithText("Open").fetchSemanticsNodes().size,
            )
            // The bottom bar is hidden while a sub-screen is open: leaving it up would
            // suggest the tabs are still peers of what is in front of the user.
            compose.onNodeWithContentDescription("Back").assertExists()

            // And the screen itself rendered something real, not a blank pane.
            assertTrue(
                "'$title' rendered none of its own content (looked for '$anchor')",
                compose.onAllNodesWithText(anchor, substring = true).fetchSemanticsNodes().isNotEmpty(),
            )

            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()

            assertEquals(
                "Back from '$title' did not return to the More list",
                subScreens.size,
                compose.onAllNodesWithText("Open").fetchSemanticsNodes().size,
            )
        }
    }

    @Test
    fun the_access_screen_lists_the_special_access_the_guard_reports() {
        compose.onNodeWithContentDescription("More").performClick()
        compose.waitForIdle()
        val accessIndex = subScreens.indexOfFirst { it.first == "Access" }
        compose.onAllNodesWithText("Open")[accessIndex].performScrollTo()
        compose.onAllNodesWithText("Open")[accessIndex].performClick()
        compose.waitForIdle()

        // The accessibility entry is the capability the whole screen agent depends on, and
        // it is not granted on a fresh install, so the screen has to offer the settings
        // route rather than reporting everything as ready.
        compose.onNodeWithText("Accessibility (screen reading & control)").assertExists()
        compose.onNodeWithText("Ignore battery optimisation").assertExists()
        compose.onNodeWithText("Display over other apps").assertExists()

        // Regression guard: notification access used to be listed here even though no
        // manifest declares a NotificationListenerService, so the button led to a settings
        // list that did not contain Sarothi.
        assertEquals(
            "The Access screen offers a notification-listener switch Sarothi cannot be given",
            0,
            compose.onAllNodesWithText("Notification access").fetchSemanticsNodes().size,
        )
    }
}

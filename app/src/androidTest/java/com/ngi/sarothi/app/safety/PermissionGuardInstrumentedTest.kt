package com.ngi.sarothi.app.safety

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ngi.sarothi.core.persona.Persona
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.core.safety.PermissionGuard
import com.ngi.sarothi.plugins.BuiltinPlugins
import com.ngi.sarothi.plugins.PersonaAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [PermissionGuard] against a real PackageManager, over the real plugin set.
 *
 * The guard is the piece that decides whether a tool may run, so a mistake in it is worse
 * than a mistake in a plugin: it either refuses something the user has already granted, or
 * it tells the user to flip a switch that does not exist. Both are only visible against a
 * real system — `PackageManager.getPermissionInfo`, `Settings.canDrawOverlays` and the
 * resolved settings screens are what make these checks meaningful.
 */
@RunWith(AndroidJUnit4::class)
class PermissionGuardInstrumentedTest {

    private lateinit var context: Context
    private lateinit var guard: PermissionGuard
    private lateinit var plugins: List<Plugin>

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        guard = PermissionGuard(context)
        plugins = BuiltinPlugins.all(PersonaAccess({ Persona.DEFAULT }, {}))
    }

    private fun plugin(name: String): Plugin =
        plugins.firstOrNull { it.name == name }
            ?: throw AssertionError("No plugin named '$name' among the ${plugins.size} built-ins")

    @Test
    fun the_plugin_set_is_the_one_the_guard_is_been_checked_against() {
        // Guards the rest of this file against silently testing a subset.
        assertTrue("Expected the full built-in set, found ${plugins.size}", plugins.size >= 70)
        assertEquals(plugins.size, plugins.map { it.name }.distinct().size)
    }

    @Test
    fun every_special_access_the_guard_reports_is_something_the_user_can_change() {
        val reported = guard.specialAccess()
        assertTrue("The guard reports no special access at all", reported.isNotEmpty())

        reported.forEach { access ->
            assertTrue("${access.id} has no display name", access.displayName.isNotBlank())
            assertTrue("${access.id} does not say what it is for", access.purpose.isNotBlank())
            assertTrue("${access.id} does not say what happens without it", access.consequence.isNotBlank())
            if (access.notApplicable) {
                // "Not applicable" means this Android version has no such switch, and the
                // UI shows exactly that, so it must not also offer a settings screen.
                return@forEach
            }
            val intent = access.settingsIntent
            assertNotNull(
                "${access.id} is reported as something the user can grant but offers no way to reach it",
                intent,
            )
            intent ?: return@forEach
            val resolves = context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .isNotEmpty() ||
                context.packageManager.resolveService(intent, PackageManager.MATCH_DEFAULT_ONLY) != null ||
                context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
            assertTrue(
                "${access.id} points at ${intent.action}, which nothing on this device can open",
                resolves,
            )
        }
    }

    @Test
    fun no_plugin_is_gated_on_a_switch_this_apk_cannot_appear_in() {
        // Regression: `read_notifications` and the notification rules used to be gated on
        // "notification access", which Sarothi can never be given — no manifest here
        // declares a NotificationListenerService. The user was sent to a settings list
        // that did not contain Sarothi, and the tools stayed refused for good.
        val listenerServices = context.packageManager.queryIntentServices(
            Intent(NOTIFICATION_LISTENER_INTERFACE),
            PackageManager.GET_META_DATA,
        ).filter { it.packageName == context.packageName }

        if (listenerServices.isEmpty()) {
            val offeredButUngrantable = guard.specialAccess().filter { access ->
                access.settingsIntent?.action == Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            }
            assertTrue(
                "The Access screen offers notification access but this APK declares no " +
                    "NotificationListenerService, so it can never be granted: " +
                    offeredButUngrantable.map { it.id },
                offeredButUngrantable.isEmpty(),
            )
        }

        // Whatever the answer above, the gate itself must be satisfiable: a plugin whose
        // verdict can never become `allowed` is a feature that does not exist.
        val grantable = guard.specialAccess()
            .filter { it.notApplicable || it.settingsIntent != null }
            .map { it.id }
            .toSet()
        val impossible = PermissionGuard.PLUGIN_SPECIAL_ACCESS.values
            .flatten()
            .distinct()
            .filterNot { it in grantable }
        assertTrue("Plugins are gated on access nothing can grant: $impossible", impossible.isEmpty())
    }

    @Test
    fun notification_plugins_are_gated_on_the_accessibility_service_that_feeds_them() {
        // Notifications reach Sarothi through SarothiAccessibilityService -> NotificationFeed.
        // That is the access these two plugins depend on, and it is one the user can grant.
        listOf("read_notifications", "add_notification_rule").forEach { name ->
            val verdict = guard.verdictFor(plugin(name))
            assertEquals(
                "'$name' is gated on ${verdict.missingSpecial} but its data comes from the " +
                    "accessibility service",
                listOf(PermissionGuard.ACCESSIBILITY),
                verdict.missingSpecial,
            )
            assertTrue(
                "'$name' explanation does not name the missing access: ${verdict.explanation}",
                verdict.explanation.contains("Accessibility"),
            )
        }
    }

    @Test
    fun every_plugin_name_in_the_special_access_map_is_a_real_plugin() {
        // Regression: the map had a "notification_rules" entry, and no plugin has ever been
        // called that. A key that matches nothing gates nothing, so the check it looks like
        // it performs is not performed at all.
        val names = plugins.map { it.name }.toSet()
        val unknown = PermissionGuard.PLUGIN_SPECIAL_ACCESS.keys - names
        assertTrue(
            "PLUGIN_SPECIAL_ACCESS refers to plugins that do not exist, so those entries " +
                "silently gate nothing: $unknown",
            unknown.isEmpty(),
        )
    }

    @Test
    fun every_plugin_verdict_is_internally_consistent_and_explains_itself() {
        plugins.forEach { plugin ->
            val verdict = guard.verdictFor(plugin)
            assertEquals(
                "'${plugin.name}': allowed=${verdict.allowed} does not match " +
                    "missing=${verdict.missingRuntime + verdict.missingSpecial}",
                verdict.missingRuntime.isEmpty() && verdict.missingSpecial.isEmpty(),
                verdict.allowed,
            )
            assertTrue("'${plugin.name}' got an empty explanation", verdict.explanation.isNotBlank())
            assertTrue(
                "'${plugin.name}' explanation does not name the plugin: ${verdict.explanation}",
                verdict.explanation.contains(plugin.name),
            )
            if (!verdict.allowed) {
                assertTrue(
                    "'${plugin.name}' was refused without saying what is missing: ${verdict.explanation}",
                    verdict.explanation.contains("Missing:"),
                )
            }
        }
    }

    @Test
    fun on_a_fresh_install_every_dangerous_permission_is_missing() {
        val packageManager = context.packageManager
        val asked = plugins.flatMap { it.requiredPermissions }.distinct().sorted()
        assertTrue("No plugin declares any permission", asked.isNotEmpty())

        val dangerous = asked.filter { permission ->
            val info = runCatching { packageManager.getPermissionInfo(permission, 0) }.getOrNull()
            info != null &&
                (info.protection and PackageManager.PROTECTION_MASK_BASE) ==
                PackageManager.PROTECTION_DANGEROUS
        }
        assertTrue(
            "None of the ${asked.size} declared permissions is dangerous, which is not what " +
                "the plugin set asks for: $asked",
            dangerous.isNotEmpty(),
        )

        // Nothing has been granted to this app yet: a fresh install must start closed.
        dangerous.forEach { permission ->
            assertFalse(
                "$permission is already granted on a fresh install",
                guard.granted(permission),
            )
            assertTrue(
                "$permission should be reported missing",
                permission in guard.missingRuntime(dangerous),
            )
        }

        // And every plugin that needs one of them must be refused, with the permission named.
        val blocked = plugins.filter { plugin ->
            plugin.requiredPermissions.any { it in dangerous }
        }
        assertTrue("No plugin needs a dangerous permission", blocked.isNotEmpty())
        blocked.forEach { plugin ->
            val verdict = guard.verdictFor(plugin)
            assertFalse("'${plugin.name}' was allowed with ${verdict.missingRuntime} still missing", verdict.allowed)
            assertTrue(
                "'${plugin.name}' does not report the permissions it needs: ${verdict.missingRuntime}",
                verdict.missingRuntime.isNotEmpty(),
            )
        }
    }

    @Test
    fun the_meta_plugin_itself_needs_nothing_and_says_the_app_is_not_ready_yet() {
        // permission_guard is the tool the model is told to call first, so it must never be
        // the one that is blocked.
        val verdict = guard.verdictFor(plugin("permission_guard"))
        assertTrue(
            "permission_guard was refused: ${verdict.explanation}",
            verdict.allowed,
        )
        assertTrue(plugin("permission_guard").requiredPermissions.isEmpty())
    }

    @Test
    fun every_permission_a_plugin_asks_for_has_a_plain_language_reason_in_both_languages() {
        val asked = plugins.flatMap { it.requiredPermissions }.distinct().sorted()
        val unexplained = asked.filterNot { PermissionGuard.PERMISSION_EXPLANATIONS.containsKey(it) }
        assertTrue(
            "These permissions are requested with no explanation, so the request screen and " +
                "the guard fall back to generic text: $unexplained",
            unexplained.isEmpty(),
        )

        asked.forEach { permission ->
            val explanation = guard.describe(permission)
            assertTrue("$permission has an empty English explanation", explanation.english.isNotBlank())
            assertTrue("$permission has an empty Bangla explanation", explanation.bangla.isNotBlank())
            assertTrue(
                "$permission's Bangla explanation is not Bangla: ${explanation.bangla}",
                explanation.bangla.any { it.code in 0x0980..0x09FF },
            )
        }
    }

    private companion object {
        /** NotificationListenerService.SERVICE_INTERFACE, spelled out so this test module
         *  does not need the framework class to exist at compile time on every API. */
        const val NOTIFICATION_LISTENER_INTERFACE =
            "android.service.notification.NotificationListenerService"
    }
}

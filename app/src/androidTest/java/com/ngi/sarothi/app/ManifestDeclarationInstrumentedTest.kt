package com.ngi.sarothi.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.XmlResourceParser
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ngi.sarothi.core.persona.Persona
import com.ngi.sarothi.core.safety.PermissionGuard
import com.ngi.sarothi.core.screen.SarothiAccessibility
import com.ngi.sarothi.plugins.BuiltinPlugins
import com.ngi.sarothi.plugins.PersonaAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the installed APK actually declares, read back through the platform.
 *
 * A manifest is the one part of Sarothi that unit tests cannot check and that fails in the
 * least forgiving way: a service that is not declared simply never binds, an activity name
 * that is one segment wrong makes a Settings deep link dead, and a foreground service with
 * no type is killed on Android 14. None of that is a compile error. These tests ask the
 * PackageManager what got installed and compare it with what the code needs.
 */
@RunWith(AndroidJUnit4::class)
class ManifestDeclarationInstrumentedTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        packageManager = context.packageManager
    }

    private fun packageInfo(flags: Int) = packageManager.getPackageInfo(context.packageName, flags)

    private fun services(): List<ServiceInfo> =
        packageInfo(PackageManager.GET_SERVICES).services?.toList() ?: emptyList()

    private fun service(className: String): ServiceInfo =
        services().firstOrNull { it.name == className }
            ?: throw AssertionError(
                "$className is not declared in the installed APK. Declared services: " +
                    services().map { it.name },
            )

    @Test
    fun the_accessibility_service_is_declared_the_way_the_system_requires() {
        val info = service(ACCESSIBILITY_SERVICE_CLASS)

        // Without this exact permission the system will not bind the service, and Sarothi
        // has no way to see or touch a screen.
        assertEquals(
            "The accessibility service must be protected by BIND_ACCESSIBILITY_SERVICE",
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            info.permission,
        )
        assertTrue("The accessibility service is not exported, so the system cannot bind it", info.exported)

        // This is the query the Settings app itself runs to build the accessibility list.
        // If Sarothi does not appear here, the user cannot turn it on at all.
        val listed = packageManager.queryIntentServices(
            Intent(AccessibilityService.SERVICE_INTERFACE),
            PackageManager.GET_META_DATA,
        ).filter { it.packageName == context.packageName }
        assertTrue(
            "Settings would not list Sarothi: no service answers ${AccessibilityService.SERVICE_INTERFACE}",
            listed.any { it.name == ACCESSIBILITY_SERVICE_CLASS },
        )

        val configResource = info.metaData?.getInt("android.accessibilityservice") ?: 0
        assertTrue(
            "The service declares no android.accessibilityservice configuration resource",
            configResource != 0,
        )
        assertEquals("xml", context.resources.getResourceTypeName(configResource))
    }

    @Test
    fun the_accessibility_configuration_declares_every_capability_the_code_uses() {
        val config = accessibilityConfig()

        // AccessibilityScreenController reads other windows, so the flag has to be set or
        // dialogs and keyboards are invisible to the agent.
        assertTrue(
            "flagRetrieveInteractiveWindows is missing; dialogs and IMEs would be unreadable " +
                "(accessibilityFlags=${config.flags})",
            config.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0,
        )
        assertTrue(
            "flagReportViewIds is missing; the agent could not name controls by resource id",
            config.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0,
        )
        assertTrue(
            "canRetrieveWindowContent is false; the tree would always come back empty",
            config.canRetrieveWindowContent,
        )
        assertTrue(
            "canPerformGestures is false; dispatchGesture (tapAt, swipe) would always fail",
            config.canPerformGestures,
        )
        // NotificationFeed is fed only by these events, and notification-triggered rules
        // read nothing else.
        assertTrue(
            "typeNotificationStateChanged is not requested; notification rules would never fire " +
                "(accessibilityEventTypes=${config.eventTypes})",
            config.eventTypes and AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED != 0,
        )
    }

    @Test
    fun the_settings_activity_the_service_advertises_is_a_real_exported_activity() {
        // Regression: this used to say "com.ngi.sarothi.ui.MainActivity", one package
        // segment short of the real class, so the gear button in Settings -> Accessibility
        // pointed at an activity that does not exist.
        val declared = accessibilityConfig().settingsActivity
        assertTrue(
            "android:settingsActivity is empty, so Settings shows no way into the app",
            declared.isNotBlank(),
        )

        val component = ComponentName(context.packageName, declared)
        val activity = runCatching { packageManager.getActivityInfo(component, 0) }.getOrElse {
            throw AssertionError(
                "android:settingsActivity names '$declared', which is not an activity in " +
                    "${context.packageName}: ${it.javaClass.simpleName}: ${it.message}",
            )
        }
        assertTrue("The advertised settings activity '$declared' is not exported", activity.exported)
    }

    @Test
    fun exactly_one_launcher_activity_is_the_way_in() {
        val launchable = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        ).filter { it.activityInfo.packageName == context.packageName }

        assertEquals(
            "Sarothi should have exactly one launcher entry point, found: " +
                launchable.map { it.activityInfo.name },
            1,
            launchable.size,
        )
        assertEquals("com.ngi.sarothi.app.ui.MainActivity", launchable.single().activityInfo.name)
        assertTrue(launchable.single().activityInfo.exported)
    }

    @Test
    fun every_foreground_service_declares_a_type_the_platform_can_enforce() {
        // On Android 14 a startForeground() call for a service whose manifest entry has no
        // type throws, which would take down model downloads and scheduled tasks at the
        // worst possible moment.
        val expected = buildMap {
            put(
                "com.ngi.sarothi.core.screen.ScreenCaptureService",
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            put(
                "com.ngi.sarothi.core.model.ModelDownloadService",
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            put(
                "com.ngi.sarothi.core.smart.GeofenceWatcherService",
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            // FOREGROUND_SERVICE_TYPE_SPECIAL_USE exists from API 34, and so does the
            // platform's insistence on a type at all.
            if (Build.VERSION.SDK_INT >= 34) {
                put(
                    "com.ngi.sarothi.core.schedule.ScheduleService",
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            }
        }

        expected.forEach { (className, type) ->
            val info = service(className)
            assertEquals(
                "$className declares foregroundServiceType=${info.foregroundServiceType} but needs $type",
                type,
                info.foregroundServiceType,
            )
            assertFalse("$className must not be exported", info.exported)
        }
    }

    @Test
    fun the_boot_receiver_is_registered_and_the_local_one_is_not_exported() {
        val receivers = packageInfo(PackageManager.GET_RECEIVERS).receivers?.toList() ?: emptyList()
        val boot = receivers.firstOrNull { it.name == "com.ngi.sarothi.core.schedule.BootReceiver" }
        assertNotNull(
            "BootReceiver is not declared, so scheduled tasks would never be re-armed after a " +
                "reboot. Declared receivers: ${receivers.map { it.name }}",
            boot,
        )
        assertTrue("BootReceiver must be exported to receive BOOT_COMPLETED", boot!!.exported)

        val byAction = packageManager.queryIntentReceivers(
            Intent(Intent.ACTION_BOOT_COMPLETED),
            0,
        ).filter { it.activityInfo.packageName == context.packageName }
        assertTrue(
            "Nothing in this APK is registered for BOOT_COMPLETED",
            byAction.isNotEmpty(),
        )

        val local = receivers.firstOrNull { it.name == "com.ngi.sarothi.core.schedule.ScheduleReceiver" }
        assertNotNull("ScheduleReceiver is not declared", local)
        assertFalse("ScheduleReceiver is internal and must not be exported", local!!.exported)
    }

    @Test
    fun backup_is_off_so_nothing_leaves_the_device_without_being_asked() {
        // The manifest promises this in a comment; this checks the promise survived merging.
        val flags = packageInfo(0).applicationInfo?.flags ?: 0
        assertEquals(
            "allowBackup is on, so Android may copy Sarothi's local state to a Google account",
            0,
            flags and ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
    }

    @Test
    fun every_permission_the_plugins_need_is_declared_in_this_apk() {
        // requestPermissions() for something the manifest does not declare is silently
        // refused forever, so the plugin would look permanently broken with no explanation.
        val declared = packageInfo(PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet() ?: emptySet()
        assertTrue("The APK declares no permissions at all", declared.isNotEmpty())

        val wanted = BuiltinPlugins.all(PersonaAccess({ Persona.DEFAULT }, {}))
            .flatMap { it.requiredPermissions }
            .distinct()
            .sorted()
        assertTrue("No plugin declares a permission, which is not what the set looks like", wanted.isNotEmpty())

        val missing = wanted.filterNot { it in declared }
        assertTrue(
            "These permissions are required by plugins but absent from the manifest, so they " +
                "can never be granted: $missing",
            missing.isEmpty(),
        )

        // The guard explains every permission it can ask for; an explanation for something
        // the APK does not declare would be shown for a request that cannot succeed.
        val unexplained = wanted.filterNot { PermissionGuard.PERMISSION_EXPLANATIONS.containsKey(it) }
        assertTrue("No plain-language reason exists for: $unexplained", unexplained.isEmpty())
    }

    @Test
    fun the_declared_queries_really_make_other_apps_visible() {
        // Package visibility on API 30+ is opt-in. Without the <queries> element this list
        // comes back nearly empty and every "open app" or "share" step fails at runtime.
        val launchable = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        )
        val otherApps = launchable.filter { it.activityInfo.packageName != context.packageName }
        assertTrue(
            "No other launchable app is visible to Sarothi; the <queries> declaration is not " +
                "working (saw ${launchable.size} entr(ies) in total)",
            otherApps.isNotEmpty(),
        )

        // The mailto and dial queries back the SMS/call/email plugins' "is there an app for
        // this" checks.
        val dial = packageManager.queryIntentActivities(Intent(Intent.ACTION_DIAL), 0)
        assertTrue("ACTION_DIAL resolves to nothing, though the manifest declares the query", dial.isNotEmpty())
    }

    @Test
    fun the_deep_links_the_guard_offers_resolve_on_this_device() {
        // SarothiAccessibility.serviceSettingsIntent builds the accessibility deep link from
        // a string literal, because Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS is
        // @SystemApi and not in the public SDK. Check the literal it produces names a
        // service this APK actually declares.
        // Each SpecialAccess hands the UI an intent it launches directly. An intent no
        // activity answers would take the user to a crash or a blank screen.
        PermissionGuard(context).specialAccess()
            .filterNot { it.notApplicable }
            .forEach { access ->
                val intent = access.settingsIntent
                assertNotNull("${access.id} offers no settings screen", intent)
                intent ?: return@forEach
                val resolves = packageManager
                    .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    .isNotEmpty() ||
                    packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
                assertTrue(
                    "${access.id} launches ${intent.action}, which nothing on this device answers",
                    resolves,
                )
            }

        val deepLink = SarothiAccessibility.serviceSettingsIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The action constant is @SystemApi, so it is compared as the literal string
            // Sarothi itself uses.
            assertEquals("android.settings.ACCESSIBILITY_DETAILS_SETTINGS", deepLink.action)

            val flattened = deepLink.getStringExtra(Intent.EXTRA_COMPONENT_NAME)
            assertEquals(
                "The deep link must name the service this APK declares",
                ComponentName(context.packageName, ACCESSIBILITY_SERVICE_CLASS).flattenToString(),
                flattened,
            )
            val named = flattened?.let { ComponentName.unflattenFromString(it) }
            assertNotNull("The component extra is not a flattened ComponentName: $flattened", named)
            assertNotNull(
                "The deep link names a service this APK does not declare",
                services().firstOrNull { it.name == named!!.className },
            )
        } else {
            // Before API 30 there is no details page to deep-link to. Sarothi opens the
            // general accessibility list instead of pretending to reach the right page.
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, deepLink.action)
            assertNull(
                "The general accessibility list takes no component extra",
                deepLink.getStringExtra(Intent.EXTRA_COMPONENT_NAME),
            )
        }
    }

    // ------------------------------------------------------------ config parsing

    private data class ServiceConfig(
        val flags: Int,
        val eventTypes: Int,
        val canRetrieveWindowContent: Boolean,
        val canPerformGestures: Boolean,
        val settingsActivity: String,
    )

    private fun accessibilityConfig(): ServiceConfig {
        val info = service(ACCESSIBILITY_SERVICE_CLASS)
        val resourceId = info.metaData?.getInt("android.accessibilityservice") ?: 0
        assertTrue("No accessibility configuration resource is declared", resourceId != 0)

        val parser = context.resources.getXml(resourceId)
        try {
            var event = parser.eventType
            while (event != XmlResourceParser.END_DOCUMENT) {
                if (event == XmlResourceParser.START_TAG && parser.name == "accessibility-service") {
                    return ServiceConfig(
                        // Compiled flag attributes are ints at runtime; reading them as
                        // strings would give the numeric form instead of the flag names.
                        flags = parser.getAttributeIntValue(ANDROID_NS, "accessibilityFlags", 0),
                        eventTypes = parser.getAttributeIntValue(ANDROID_NS, "accessibilityEventTypes", 0),
                        canRetrieveWindowContent = parser.getAttributeBooleanValue(
                            ANDROID_NS,
                            "canRetrieveWindowContent",
                            false,
                        ),
                        canPerformGestures = parser.getAttributeBooleanValue(ANDROID_NS, "canPerformGestures", false),
                        settingsActivity = parser.getAttributeValue(ANDROID_NS, "settingsActivity") ?: "",
                    )
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        throw AssertionError("The accessibility configuration resource has no <accessibility-service> element")
    }

    private companion object {
        const val ACCESSIBILITY_SERVICE_CLASS =
            "com.ngi.sarothi.core.screen.SarothiAccessibilityService"
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}

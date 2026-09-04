package com.ngi.sarothi.core.safety

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.core.screen.SarothiAccessibility

/**
 * A capability that needs something other than a runtime permission: an
 * accessibility service toggle, a special-access screen, or an app-op.
 *
 * These are the permissions that actually decide whether Sarothi works, and none
 * of them can be requested with `requestPermissions()` — the user has to walk to
 * a settings screen. Reporting them explicitly is what lets the UI show "screen
 * reading is off" instead of failing mid-task.
 */
data class SpecialAccess(
    val id: String,
    val displayName: String,
    val granted: Boolean,
    /** Why Sarothi wants it, in plain language. Shown next to the toggle. */
    val purpose: String,
    /** What breaks without it. Never a guess: each is a real capability. */
    val consequence: String,
    val settingsIntent: Intent?,
    /** True when the OS version in use does not have this concept at all. */
    val notApplicable: Boolean = false,
)

data class PermissionVerdict(
    val allowed: Boolean,
    val missingRuntime: List<String>,
    val missingSpecial: List<String>,
    val explanation: String,
)

/**
 * Decides whether a plugin may run, before it runs.
 *
 * Built first among the safety pieces because everything else depends on it: a
 * plugin that is missing a permission must produce a clear refusal with a route to
 * the settings screen, not a `SecurityException` from inside an SDK three frames
 * down. The `permission_guard` plugin exposes this same object to the model, so
 * the agent can check its own capabilities while planning instead of discovering
 * a wall at step four.
 */
class PermissionGuard(private val context: Context) {

    /** Runtime permissions the plugin declares that are not currently granted. */
    fun missingRuntime(permissions: List<String>): List<String> = permissions.filterNot { granted(it) }

    /**
     * Permissions that post-date minSdk 26, and the API level each arrives at.
     *
     * Below that level the platform has no such permission at all, so
     * `checkSelfPermission` answers DENIED -- not "the user refused", but "this device
     * has no concept of the question". There is nothing to enforce and nothing to ask
     * for:
     *
     *  - FOREGROUND_SERVICE (28) is a normal permission; before 28 any app could start
     *    a foreground service without declaring one.
     *  - USE_BIOMETRIC (28) is normal too; USE_FINGERPRINT was the equivalent before it.
     *  - ACCESS_BACKGROUND_LOCATION (29) matters most here. Before 29,
     *    ACCESS_FINE_LOCATION already covered background use, which is exactly why there
     *    is no separate grant. Treating it as missing made `geofence_reminder`
     *    permanently unavailable on every Android 8 and 9 phone -- the older, low-RAM
     *    devices this app is built for.
     *  - POST_NOTIFICATIONS (33): before 33 notifications needed no runtime permission.
     *
     * InlinedApi is suppressed on the table because naming permissions newer than minSdk
     * *is* the table's purpose; it maps each to an Int and calls no API.
     */
    @SuppressLint("InlinedApi")
    private val permissionMinApi = mapOf(
        Manifest.permission.FOREGROUND_SERVICE to 28,
        Manifest.permission.USE_BIOMETRIC to 28,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION to 29,
        Manifest.permission.POST_NOTIFICATIONS to 33,
    )

    private fun appliesOnThisDevice(permission: String): Boolean =
        Build.VERSION.SDK_INT >= (permissionMinApi[permission] ?: 0)

    /**
     * True when the platform will let this app use [permission].
     *
     * A permission this device has no concept of counts as granted, so every caller --
     * the plugin pipeline, the permission screen, a plugin's own pre-flight check --
     * gets the same answer instead of each having to know which permissions are new.
     */
    fun granted(permission: String): Boolean =
        !appliesOnThisDevice(permission) ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Verdict for a plugin, combining runtime permissions and special access. */
    fun verdictFor(plugin: Plugin): PermissionVerdict {
        val missingPermissions = missingRuntime(plugin.requiredPermissions)
        val missingSpecial = specialAccess().filter { access ->
            !access.notApplicable && !access.granted && access.id in specialAccessFor(plugin.name)
        }
        val allowed = missingPermissions.isEmpty() && missingSpecial.isEmpty()
        return PermissionVerdict(
            allowed = allowed,
            missingRuntime = missingPermissions,
            missingSpecial = missingSpecial.map { it.id },
            explanation = when {
                allowed -> "'${plugin.name}' has everything it needs."
                else -> buildString {
                    append("'${plugin.name}' cannot run yet. Missing: ")
                    append((missingPermissions.map { describe(it).english } + missingSpecial.map { it.displayName }).joinToString("; "))
                    append('.')
                }
            },
        )
    }

    /** Which special-access ids a given plugin depends on. */
    fun specialAccessFor(pluginName: String): Set<String> = PLUGIN_SPECIAL_ACCESS[pluginName] ?: emptySet()

    /** Every special-access state Sarothi cares about, whether granted or not. */
    fun specialAccess(): List<SpecialAccess> = buildList {
        add(
            SpecialAccess(
                id = ACCESSIBILITY,
                displayName = "Accessibility (screen reading & control)",
                granted = accessibilityEnabled(),
                purpose = "Lets Sarothi read what is on your screen and tap, type and scroll for you.",
                consequence = "Without it Sarothi cannot see or operate any app. This is the single " +
                    "capability the screen agent depends on.",
                settingsIntent = SarothiAccessibility.serviceSettingsIntent(context),
            ),
        )
        add(
            SpecialAccess(
                id = NOTIFICATION_LISTENER,
                displayName = "Notification access",
                granted = notificationListenerEnabled(),
                purpose = "Lets notification-triggered rules read incoming notifications with their " +
                    "full text, and lets Sarothi dismiss or reply to one.",
                consequence = "Without it, notification rules fall back to the accessibility " +
                    "service's notification events, which carry less detail and can be missed.",
                settingsIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            ),
        )
        add(
            SpecialAccess(
                id = BATTERY_OPTIMISATION,
                displayName = "Ignore battery optimisation",
                granted = ignoringBatteryOptimisations(),
                purpose = "Keeps scheduled tasks and the resident model alive when the screen is off.",
                consequence = "Without it Android may freeze Sarothi in the background and scheduled " +
                    "tasks will fire late or not at all.",
                settingsIntent = batteryIntent(),
            )
        )
        add(
            SpecialAccess(
                id = DRAW_OVER,
                displayName = "Display over other apps",
                granted = canDrawOverlays(),
                purpose = "Lets Sarothi show its live task checklist and confirmation prompts on top " +
                    "of the app it is operating.",
                consequence = "Without it confirmations appear only in the notification shade, so a " +
                    "task will pause unnoticed while Sarothi waits for you.",
                settingsIntent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            ),
        )
        add(
            SpecialAccess(
                id = ALL_FILES,
                displayName = "All files access",
                granted = managesExternalStorage(),
                purpose = "Lets the vault live anywhere on storage, including a path an SD card " +
                    "provider exposes outside the SAF tree.",
                consequence = "Not required if you pick the vault folder through the storage picker, " +
                    "which is the recommended and more private option.",
                settingsIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"))
                } else {
                    null
                },
                notApplicable = Build.VERSION.SDK_INT < Build.VERSION_CODES.R,
            ),
        )
        add(
            SpecialAccess(
                id = USAGE_ACCESS,
                displayName = "Usage access",
                granted = usageAccessGranted(),
                purpose = "Lets app-usage plugins report which apps you use and for how long.",
                consequence = "Without it the app_usage plugin reports itself unavailable rather " +
                    "than returning invented numbers.",
                settingsIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            ),
        )
        add(
            SpecialAccess(
                id = WRITE_SETTINGS,
                displayName = "Modify system settings",
                granted = canWriteSettings(),
                purpose = "Lets Sarothi change screen brightness and the screen-off timeout.",
                consequence = "Without it brightness and timeout plugins report themselves " +
                    "unavailable; other system plugins are unaffected.",
                settingsIntent = Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ),
            ),
        )
    }

    // ------------------------------------------------------------- access checks

    fun accessibilityEnabled(): Boolean {
        val component = SarothiAccessibility.componentFor(context) ?: return false
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val needle = component.flattenToString().lowercase()
        return enabled.split(':').any { it.trim().lowercase() == needle }
    }

    fun notificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        return enabled.split(':').any { entry ->
            val component = runCatching {
                android.content.ComponentName.unflattenFromString(entry.trim())
            }.getOrNull()
            component?.packageName == context.packageName
        }
    }

    fun ignoringBatteryOptimisations(): Boolean {
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { manager.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }

    fun canDrawOverlays(): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    fun managesExternalStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            // Before API 30 there is no such switch; WRITE_EXTERNAL_STORAGE is the
            // equivalent and is a normal runtime permission.
            granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    fun usageAccessGranted(): Boolean {
        val manager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                manager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }.getOrDefault(AppOpsManager.MODE_ERRORED)
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                manager.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }.getOrDefault(AppOpsManager.MODE_ERRORED)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun canWriteSettings(): Boolean =
        runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    /**
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is API 23 and minSdk is 26, so this
     * always resolves and the null it used to return on older Android was unreachable.
     * The nullable type stays: callers still have to cope with the intent not resolving,
     * which is a different question from the API level.
     *
     * BatteryLife is suppressed because the usage is the restricted-but-permitted kind
     * rather than the kind the check exists to catch. Sarothi runs scheduled tasks and a
     * geofence watcher that Android's doze mode otherwise suspends, so without this a
     * reminder silently fails to fire on a phone left idle -- the exact failure the app
     * exists to avoid. It is never requested on the user's behalf: the intent is handed
     * to the caller to present, ignoringBatteryOptimisations() reports the current state
     * so nothing pretends it was granted, and the explanation table carries a bilingual
     * reason the user reads before deciding.
     */
    @android.annotation.SuppressLint("BatteryLife")
    private fun batteryIntent(): Intent? = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )

    /** The settings screen that controls one runtime permission, best effort. */
    fun settingsIntentFor(permission: String): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).also { intent ->
        // Some OEM builds honour a permission-extra and open the right group.
        intent.putExtra("permission", permission)
    }

    data class Explanation(val english: String, val bangla: String)

    /** Plain-language reason for each permission Sarothi asks for. */
    fun describe(permission: String): Explanation = PERMISSION_EXPLANATIONS[permission]
        ?: Explanation(
            english = "Sarothi declares $permission; see Android's documentation for what it allows.",
            bangla = "সারথি $permission অনুমতি ব্যবহার করে; বিস্তারিত Android-এর ডকুমেন্টেশনে আছে।",
        )

    companion object {
        const val ACCESSIBILITY = "accessibility"
        const val NOTIFICATION_LISTENER = "notification_listener"
        const val BATTERY_OPTIMISATION = "battery_optimisation"
        const val DRAW_OVER = "draw_over_apps"
        const val ALL_FILES = "all_files_access"
        const val USAGE_ACCESS = "usage_access"
        const val WRITE_SETTINGS = "write_settings"

        /**
         * Plugins that depend on a special access rather than a runtime permission.
         * Kept as data so the guard and the UI agree, and so adding a plugin means
         * adding one line here rather than scattering checks.
         */
        val PLUGIN_SPECIAL_ACCESS: Map<String, Set<String>> = mapOf(
            "read_screen" to setOf(ACCESSIBILITY),
            "tap_node" to setOf(ACCESSIBILITY),
            "tap_at" to setOf(ACCESSIBILITY),
            "type_text" to setOf(ACCESSIBILITY),
            "scroll_screen" to setOf(ACCESSIBILITY),
            "swipe_gesture" to setOf(ACCESSIBILITY),
            "press_back" to setOf(ACCESSIBILITY),
            "press_home" to setOf(ACCESSIBILITY),
            "open_recents" to setOf(ACCESSIBILITY),
            "open_app" to setOf(ACCESSIBILITY),
            "screenshot_ocr" to setOf(ACCESSIBILITY),
            "screen_agent" to setOf(ACCESSIBILITY),
            "notification_rules" to setOf(NOTIFICATION_LISTENER),
            "read_notifications" to setOf(NOTIFICATION_LISTENER),
            "app_usage" to setOf(USAGE_ACCESS),
            "set_brightness" to setOf(WRITE_SETTINGS),
            "set_screen_timeout" to setOf(WRITE_SETTINGS),
            "schedule_task" to setOf(BATTERY_OPTIMISATION),
        )

        /**
         * Why each permission is wanted, in both languages, so the guard and the request
         * screen give the same reason.
         *
         * Three keys name a permission newer than minSdk 26 -- POST_NOTIFICATIONS (33),
         * FOREGROUND_SERVICE (28), USE_BIOMETRIC (28). These are string constants inlined
         * into the map at compile time; nothing here calls the API they belong to, and on
         * an older device the key is simply never looked up. Lint's InlinedApi is warning
         * about a value in this case, not a call.
         */
        val PERMISSION_EXPLANATIONS: Map<String, Explanation> = mapOf(
            Manifest.permission.SEND_SMS to Explanation(
                "Send SMS messages as you. Sarothi always shows the number and the full text and " +
                    "waits for your confirmation before sending.",
                "আপনার হয়ে এসএমএস পাঠানো। পাঠানোর আগে সারথি নম্বর ও পুরো লেখা দেখিয়ে আপনার অনুমতি নেয়।",
            ),
            Manifest.permission.READ_SMS to Explanation(
                "Read incoming SMS, used to pick up one-time passwords so Sarothi can fill them in " +
                    "for you. Messages never leave the device.",
                "আগত এসএমএস পড়া — ওটিপি তুলে নেওয়ার জন্য। বার্তা কখনো ফোন ছেড়ে যায় না।",
            ),
            Manifest.permission.RECEIVE_SMS to Explanation(
                "Be told when an SMS arrives, so OTP steps can continue without you opening the app.",
                "এসএমএস এলে জানা — ওটিপি ধাপ যেন আপনার হাতে না আটকে থাকে।",
            ),
            Manifest.permission.CALL_PHONE to Explanation(
                "Place a phone call. Sarothi shows the number and always waits for confirmation.",
                "ফোন করা। সারথি নম্বর দেখিয়ে সবসময় অনুমতি নেয়।",
            ),
            Manifest.permission.READ_CONTACTS to Explanation(
                "Look up a contact by name so 'call Rina' does not need you to spell out a number.",
                "নাম ধরে কন্ট্যাক্ট খোঁজা — 'রিনাকে কল দাও' বললেই যেন হয়।",
            ),
            Manifest.permission.READ_CALENDAR to Explanation(
                "Read your calendars so Sarothi can check for clashes before adding an event.",
                "ক্যালেন্ডার পড়া — নতুন ইভেন্ট যোগ করার আগে সময় মিলিয়ে নেওয়া।",
            ),
            Manifest.permission.WRITE_CALENDAR to Explanation(
                "Create and update calendar events on your behalf.",
                "আপনার হয়ে ক্যালেন্ডারে ইভেন্ট তৈরি ও পরিবর্তন করা।",
            ),
            Manifest.permission.RECORD_AUDIO to Explanation(
                "Record your voice so on-device whisper.cpp can transcribe it. Audio is never uploaded.",
                "আপনার গলা রেকর্ড করা — ফোনের ভেতরেই whisper.cpp লিখে নেয়, কোথাও পাঠানো হয় না।",
            ),
            Manifest.permission.CAMERA to Explanation(
                "Take a photo when a task needs one, for example scanning a document you point at.",
                "কাজে ছবি দরকার হলে তোলা — যেমন কোনো কাগজ স্ক্যান করা।",
            ),
            Manifest.permission.ACCESS_FINE_LOCATION to Explanation(
                "Know where you are for weather, nearby places and location reminders.",
                "আবহাওয়া, কাছের জায়গা ও অবস্থান-ভিত্তিক রিমাইন্ডারের জন্য আপনার অবস্থান জানা।",
            ),
            Manifest.permission.ACCESS_COARSE_LOCATION to Explanation(
                "Know your approximate area for weather and local news.",
                "আবহাওয়া ও স্থানীয় খবরের জন্য আপনার এলাকা মোটামুটিভাবে জানা।",
            ),
            Manifest.permission.POST_NOTIFICATIONS to Explanation(
                "Show task progress, download progress and confirmations as notifications.",
                "কাজের অগ্রগতি, ডাউনলোড ও অনুমতির প্রশ্ন নোটিফিকেশনে দেখানো।",
            ),
            Manifest.permission.FOREGROUND_SERVICE to Explanation(
                "Keep model downloads, screen capture and scheduled tasks running while the app is " +
                    "in the background.",
                "অ্যাপ পেছনে থাকলেও ডাউনলোড, স্ক্রিন ক্যাপচার ও নির্ধারিত কাজ চালু রাখা।",
            ),
            Manifest.permission.INTERNET to Explanation(
                "Download models and reach the handful of public APIs Sarothi uses (search, weather, " +
                    "news, translation). Model inference itself never touches the network.",
                "মডেল ডাউনলোড আর কয়েকটি পাবলিক এপিআই (সার্চ, আবহাওয়া, খবর, অনুবাদ)। মডেল চালানোর " +
                    "সময় ইন্টারনেট লাগে না।",
            ),
            Manifest.permission.ACCESS_NETWORK_STATE to Explanation(
                "Check whether you are on Wi-Fi so large model downloads do not use mobile data.",
                "ওয়াই-ফাই আছে কি না দেখা — যাতে মডেল ডাউনলোডে মোবাইল ডেটা খরচ না হয়।",
            ),
            Manifest.permission.WAKE_LOCK to Explanation(
                "Finish a download or a scheduled task even when the screen is off.",
                "স্ক্রিন বন্ধ থাকলেও ডাউনলোড বা নির্ধারিত কাজ শেষ করা।",
            ),
            Manifest.permission.VIBRATE to Explanation(
                "A short vibration when a task needs your confirmation.",
                "অনুমতির প্রশ্ন এলে ছোট্ট একটা ভাইব্রেশন।",
            ),
            Manifest.permission.READ_EXTERNAL_STORAGE to Explanation(
                "Read the vault folder you chose, on older Android versions.",
                "পুরোনো Android ভার্সনে আপনার বাছা ভল্ট ফোল্ডার পড়া।",
            ),
            Manifest.permission.WRITE_EXTERNAL_STORAGE to Explanation(
                "Write the vault folder you chose, on older Android versions.",
                "পুরোনো Android ভার্সনে আপনার বাছা ভল্ট ফোল্ডারে লেখা।",
            ),
            Manifest.permission.USE_BIOMETRIC to Explanation(
                "Unlock the vault with your fingerprint or face as a convenience. The encryption key " +
                    "still comes from your passphrase; biometrics only unwrap it.",
                "সুবিধার জন্য আঙুলের ছাপ বা মুখ দিয়ে ভল্ট খোলা। আসল চাবি আপনার পাসওয়ার্ড থেকেই " +
                    "তৈরি; বায়োমেট্রিক শুধু সেটা খুলে দেয়।",
            ),
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS to Explanation(
                "Ask Android to stop freezing Sarothi, so scheduled tasks run on time.",
                "Android যেন সারথিকে জমে যেতে না দেয় — নির্ধারিত কাজ ঠিক সময়ে চলার জন্য।",
            ),
            Manifest.permission.SYSTEM_ALERT_WINDOW to Explanation(
                "Draw the task checklist and confirmation dialog over other apps.",
                "অন্য অ্যাপের উপরে কাজের তালিকা ও অনুমতির ডায়ালগ দেখানো।",
            ),
        )
    }
}

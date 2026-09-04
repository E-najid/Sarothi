package com.ngi.sarothi.plugins

import com.ngi.sarothi.core.persona.Persona
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.plugins.communication.FindContactPlugin
import com.ngi.sarothi.plugins.communication.MakeCallPlugin
import com.ngi.sarothi.plugins.communication.ReadNotificationsPlugin
import com.ngi.sarothi.plugins.communication.SendEmailPlugin
import com.ngi.sarothi.plugins.communication.SendSmsPlugin
import com.ngi.sarothi.plugins.communication.WhatsAppPlugin
import com.ngi.sarothi.plugins.connectors.GithubPlugin
import com.ngi.sarothi.plugins.connectors.TelegramBotPlugin
import com.ngi.sarothi.plugins.connectors.WebhookPlugin
import com.ngi.sarothi.plugins.information.DateTimePlugin
import com.ngi.sarothi.plugins.information.FetchWebPagePlugin
import com.ngi.sarothi.plugins.information.NewsPlugin
import com.ngi.sarothi.plugins.information.TranslatePlugin
import com.ngi.sarothi.plugins.information.WeatherPlugin
import com.ngi.sarothi.plugins.information.WebSearchPlugin
import com.ngi.sarothi.plugins.information.WikipediaPlugin
import com.ngi.sarothi.plugins.meta.AskUserPlugin
import com.ngi.sarothi.plugins.meta.ModelStatusPlugin
import com.ngi.sarothi.plugins.meta.PermissionGuardPlugin
import com.ngi.sarothi.plugins.meta.PersonaPlugin
import com.ngi.sarothi.plugins.meta.PluginListPlugin
import com.ngi.sarothi.plugins.meta.SafetyStatusPlugin
import com.ngi.sarothi.plugins.meta.TaskHistoryPlugin
import com.ngi.sarothi.plugins.meta.UndoLastPlugin
import com.ngi.sarothi.plugins.meta.VaultStatusPlugin
import com.ngi.sarothi.plugins.productivity.AddNotificationRulePlugin
import com.ngi.sarothi.plugins.productivity.AddTodoPlugin
import com.ngi.sarothi.plugins.productivity.CalendarAddPlugin
import com.ngi.sarothi.plugins.productivity.CalendarDeletePlugin
import com.ngi.sarothi.plugins.productivity.CalendarListPlugin
import com.ngi.sarothi.plugins.productivity.CancelSchedulePlugin
import com.ngi.sarothi.plugins.productivity.CalculatorPlugin
import com.ngi.sarothi.plugins.productivity.CompleteTodoPlugin
import com.ngi.sarothi.plugins.productivity.ConvertUnitsPlugin
import com.ngi.sarothi.plugins.productivity.DeleteNotificationRulePlugin
import com.ngi.sarothi.plugins.productivity.ListNotificationRulesPlugin
import com.ngi.sarothi.plugins.productivity.ListSchedulesPlugin
import com.ngi.sarothi.plugins.productivity.ListTodosPlugin
import com.ngi.sarothi.plugins.productivity.MemoryForgetPlugin
import com.ngi.sarothi.plugins.productivity.MemorySavePlugin
import com.ngi.sarothi.plugins.productivity.MemorySearchPlugin
import com.ngi.sarothi.plugins.productivity.ReadUserFactsPlugin
import com.ngi.sarothi.plugins.productivity.RunScheduleNowPlugin
import com.ngi.sarothi.plugins.productivity.SaveNotePlugin
import com.ngi.sarothi.plugins.productivity.SaveUserFactPlugin
import com.ngi.sarothi.plugins.productivity.ScheduleTaskPlugin
import com.ngi.sarothi.plugins.productivity.SearchNotesPlugin
import com.ngi.sarothi.plugins.productivity.SetAlarmPlugin
import com.ngi.sarothi.plugins.productivity.StartTimerPlugin
import com.ngi.sarothi.plugins.screen.NavigatePlugin
import com.ngi.sarothi.plugins.screen.OpenAppPlugin
import com.ngi.sarothi.plugins.screen.ReadScreenPlugin
import com.ngi.sarothi.plugins.screen.ScreenAgentPlugin
import com.ngi.sarothi.plugins.screen.ScreenshotOcrPlugin
import com.ngi.sarothi.plugins.screen.ScrollScreenPlugin
import com.ngi.sarothi.plugins.screen.SummarizeScreenPlugin
import com.ngi.sarothi.plugins.screen.TapAtPlugin
import com.ngi.sarothi.plugins.screen.TapNodePlugin
import com.ngi.sarothi.plugins.screen.TypeTextPlugin
import com.ngi.sarothi.plugins.shopping.OpenPaymentAppPlugin
import com.ngi.sarothi.plugins.shopping.ShoppingSearchPlugin
import com.ngi.sarothi.plugins.shopping.TrackOrderPlugin
import com.ngi.sarothi.plugins.shopping.UpiPaymentPlugin
import com.ngi.sarothi.plugins.smart.GeofenceReminderPlugin
import com.ngi.sarothi.plugins.smart.HomeAssistantPlugin
import com.ngi.sarothi.plugins.system.AppUsagePlugin
import com.ngi.sarothi.plugins.system.BatteryStatusPlugin
import com.ngi.sarothi.plugins.system.BrightnessPlugin
import com.ngi.sarothi.plugins.system.DeviceInfoPlugin
import com.ngi.sarothi.plugins.system.MemoryStatusPlugin
import com.ngi.sarothi.plugins.system.OpenSettingsPlugin
import com.ngi.sarothi.plugins.system.StorageStatusPlugin
import com.ngi.sarothi.plugins.system.WifiStatusPlugin
import com.ngi.sarothi.plugins.voice.ListenPlugin
import com.ngi.sarothi.plugins.voice.SpeakPlugin
import com.ngi.sarothi.plugins.voice.StopSpeakingPlugin
import com.ngi.sarothi.plugins.voice.VoiceNotePlugin
import com.ngi.sarothi.plugins.voice.VoiceVoicesPlugin

/**
 * The built-in plugin set, in the order they are registered.
 *
 * [PermissionGuardPlugin] is first on purpose: [com.ngi.sarothi.core.plugin.PluginManager]
 * routes every other plugin's permission questions through it, so it has to exist
 * before anything else can be described to the model.
 *
 * Each entry is constructed here rather than discovered by reflection because
 * reflection on Android costs startup time and hides the set from the compiler.
 * The list is the contract: what is in it is what Sarothi can do.
 */
/**
 * How the persona plugin reads and writes the persona.
 *
 * Passed in rather than reached for globally: the persona lives in the vault, and
 * the plugin set is built by the app's dependency graph, which already holds it.
 */
class PersonaAccess(
    val read: () -> Persona,
    val write: suspend (Persona) -> Unit,
)

object BuiltinPlugins {

    /**
     * Builds the full set.
     *
     * Called once per app-graph construction. [persona] supplies the only plugin
     * that needs state it cannot get from [com.ngi.sarothi.core.plugin.PluginContext].
     */
    fun all(persona: PersonaAccess): List<Plugin> = listOf(
        // --- meta: Sarothi talking about Sarothi. Registered first. --------
        PermissionGuardPlugin(),
        AskUserPlugin(),
        SafetyStatusPlugin(),
        PluginListPlugin(),
        TaskHistoryPlugin(),
        UndoLastPlugin(),
        ModelStatusPlugin(),
        PersonaPlugin(persona.read, persona.write),
        VaultStatusPlugin(),

        // --- screen: perception and action ---------------------------------
        ReadScreenPlugin(),
        SummarizeScreenPlugin(),
        ScreenAgentPlugin(),
        ScreenshotOcrPlugin(),
        TapNodePlugin(),
        TapAtPlugin(),
        TypeTextPlugin(),
        ScrollScreenPlugin(),
        // NavigatePlugin is the `press_key` tool: back, home, recents, enter.
        NavigatePlugin(),
        OpenAppPlugin(),

        // --- communication --------------------------------------------------
        SendSmsPlugin(),
        MakeCallPlugin(),
        SendEmailPlugin(),
        WhatsAppPlugin(),
        FindContactPlugin(),
        ReadNotificationsPlugin(),

        // --- productivity ---------------------------------------------------
        SaveNotePlugin(),
        SearchNotesPlugin(),
        AddTodoPlugin(),
        ListTodosPlugin(),
        CompleteTodoPlugin(),
        CalendarAddPlugin(),
        CalendarListPlugin(),
        CalendarDeletePlugin(),
        SetAlarmPlugin(),
        StartTimerPlugin(),
        CalculatorPlugin(),
        ConvertUnitsPlugin(),
        MemorySavePlugin(),
        MemorySearchPlugin(),
        MemoryForgetPlugin(),
        SaveUserFactPlugin(),
        ReadUserFactsPlugin(),
        ScheduleTaskPlugin(),
        ListSchedulesPlugin(),
        CancelSchedulePlugin(),
        RunScheduleNowPlugin(),
        AddNotificationRulePlugin(),
        ListNotificationRulesPlugin(),
        DeleteNotificationRulePlugin(),

        // --- information ----------------------------------------------------
        WebSearchPlugin(),
        FetchWebPagePlugin(),
        WeatherPlugin(),
        NewsPlugin(),
        TranslatePlugin(),
        WikipediaPlugin(),
        DateTimePlugin(),

        // --- shopping and money ---------------------------------------------
        UpiPaymentPlugin(),
        OpenPaymentAppPlugin(),
        ShoppingSearchPlugin(),
        TrackOrderPlugin(),

        // --- system ----------------------------------------------------------
        BatteryStatusPlugin(),
        StorageStatusPlugin(),
        MemoryStatusPlugin(),
        DeviceInfoPlugin(),
        WifiStatusPlugin(),
        BrightnessPlugin(),
        AppUsagePlugin(),
        OpenSettingsPlugin(),

        // --- voice -----------------------------------------------------------
        SpeakPlugin(),
        StopSpeakingPlugin(),
        ListenPlugin(),
        VoiceNotePlugin(),
        VoiceVoicesPlugin(),

        // --- connectors ------------------------------------------------------
        GithubPlugin(),
        TelegramBotPlugin(),
        WebhookPlugin(),

        // --- smart home and location -----------------------------------------
        HomeAssistantPlugin(),
        GeofenceReminderPlugin(),
    )

    /** Every plugin name, for the model's tool list and for tests. */
    fun names(persona: PersonaAccess): List<String> = all(persona).map { it.name }.sorted()

    /**
     * The plugins that need no construction arguments, for tests and for the
     * settings screen's static catalogue.
     */
    val STATELESS: List<Plugin> by lazy { all(PersonaAccess({ Persona.DEFAULT }, {})) }
}

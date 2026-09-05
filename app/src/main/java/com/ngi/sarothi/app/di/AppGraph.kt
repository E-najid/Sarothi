package com.ngi.sarothi.app.di

import android.content.Context
import com.ngi.sarothi.app.notify.AndroidNotifier
import com.ngi.sarothi.core.agent.SarothiAgent
import com.ngi.sarothi.core.crypto.BiometricKeyVault
import com.ngi.sarothi.core.crypto.MasterKeyManager
import com.ngi.sarothi.core.crypto.SecretStore
import com.ngi.sarothi.core.data.DataStores
import com.ngi.sarothi.core.data.VaultConversationStore
import com.ngi.sarothi.core.data.VaultMemoryStore
import com.ngi.sarothi.core.data.VaultNotesStore
import com.ngi.sarothi.core.data.VaultTaskHistoryStore
import com.ngi.sarothi.core.data.VaultTodoStore
import com.ngi.sarothi.core.data.VaultUserFactsStore
import com.ngi.sarothi.core.model.ModelDownloadRegistry
import com.ngi.sarothi.core.model.ModelDownloader
import com.ngi.sarothi.core.net.HttpClient
import com.ngi.sarothi.core.net.NetworkPolicy
import com.ngi.sarothi.core.plugin.PluginContext
import com.ngi.sarothi.core.plugin.PluginConfigStore
import com.ngi.sarothi.core.plugin.PluginContextFactory
import com.ngi.sarothi.core.plugin.PluginEnablement
import com.ngi.sarothi.core.plugin.PluginManager
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.plugin.VaultPluginConfigStore
import com.ngi.sarothi.core.runtime.EspeakPhonemizer
import com.ngi.sarothi.core.runtime.LlamaRuntime
import com.ngi.sarothi.core.runtime.LlamaVisionDescriber
import com.ngi.sarothi.core.runtime.ModelSessionManager
import com.ngi.sarothi.core.runtime.PiperRuntime
import com.ngi.sarothi.core.runtime.RamPolicy
import com.ngi.sarothi.core.runtime.WhisperRuntime
import com.ngi.sarothi.core.safety.InteractiveSafetyGate
import com.ngi.sarothi.core.safety.PermissionGuard
import com.ngi.sarothi.core.safety.UndoRegistry
import com.ngi.sarothi.core.safety.VaultAuditLogger
import com.ngi.sarothi.core.schedule.NotificationRuleEngine
import com.ngi.sarothi.core.schedule.TaskScheduler
import com.ngi.sarothi.core.screen.AccessibilityScreenController
import com.ngi.sarothi.core.screen.MlKitOcrEngine
import com.ngi.sarothi.core.screen.ScreenshotSourceRegistry
import com.ngi.sarothi.core.smart.GeofenceRegistry
import com.ngi.sarothi.core.smart.GeofenceStore
import com.ngi.sarothi.core.storage.VaultManager
import com.ngi.sarothi.core.voice.AndroidVoiceController
import com.ngi.sarothi.plugins.BuiltinPlugins
import com.ngi.sarothi.plugins.PersonaAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Builds the whole object graph once, in the Application, and holds it for the life of
 * the process.
 *
 * There is no DI framework here on purpose. The graph is a few dozen objects with a
 * known order, and a 3 GB phone should not pay for reflective codegen or a service
 * locator to resolve it. What the explicit ordering does cost is care: two cycles exist
 * (undo needs the plugin manager, the plugin manager needs undo; the plugin set needs
 * the persona, the persona repository is read through the plugin context) and both are
 * broken with a late-bound reference rather than by duplicating an object.
 *
 * Everything reachable from here is also what makes the capability boundary real:
 * a plugin gets a [PluginContext], and this file is the only place that decides what
 * goes into one.
 */
class AppGraph(context: Context) {

    val appContext: Context = context.applicationContext

    /**
     * One scope for the process. `SupervisorJob` so a failure in one download or one
     * scheduled task cannot cancel model loading or the agent alongside it.
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- secrets and the vault ------------------------------------------------
    // SecretStore is Keystore-backed and stays on the device; the vault is the user's
    // SD-card folder and is encrypted with a key derived from their passphrase.
    val secrets = SecretStore(appContext)
    val masterKeys = MasterKeyManager(secrets)
    val vault = VaultManager(appContext, secrets, masterKeys)

    /**
     * Biometric unlock, which is a convenience layer and never a second way in: the key
     * it unwraps is the same one the passphrase derives, and it is wrapped by a Keystore
     * key that cannot leave the device.
     */
    val biometrics = BiometricKeyVault(appContext, secrets)

    // --- device capability ----------------------------------------------------
    val ramPolicy = RamPolicy(appContext)
    val network = NetworkPolicy(appContext)
    val http = HttpClient()

    // --- on-device runtimes ---------------------------------------------------
    val llama = LlamaRuntime(appContext)
    val whisper = WhisperRuntime(appContext)
    val phonemizer = EspeakPhonemizer(appContext)
    val piper = PiperRuntime(appContext, phonemizer)
    val ocr = MlKitOcrEngine(appContext)
    val models = ModelSessionManager(vault, ramPolicy, llama, whisper, scope)
    val textModel = LlamaTextModelClient(models, llama)

    // --- persona --------------------------------------------------------------
    val persona = PersonaRepository(vault)

    // --- safety ---------------------------------------------------------------
    val guard = PermissionGuard(appContext)
    val audit = VaultAuditLogger(vault)
    val safety = InteractiveSafetyGate(vault)
    val notifier = AndroidNotifier(appContext)

    /**
     * Undo routes back into the plugin that acted, which is the seam [UndoRegistry]
     * documents: the registry tracks what can be reversed and knows nothing about how.
     * `pluginManager` is assigned below; the lambda only runs after construction, when
     * a user has pressed Undo on something that already happened.
     */
    val undo = UndoRegistry(
        invoker = { pluginName, undoToken ->
            val plugin = pluginManager.get(pluginName)
            if (plugin == null) {
                PluginResult.Failure(
                    summaryForUser = "'$pluginName' is no longer registered, so this action " +
                        "cannot be taken back.",
                    errorClass = "PluginNotRegistered",
                )
            } else {
                plugin.undo(undoToken)
            }
        },
    )

    // --- data -----------------------------------------------------------------
    val stores = DataStores(
        memories = VaultMemoryStore(vault),
        notes = VaultNotesStore(vault),
        todos = VaultTodoStore(vault),
        userFacts = VaultUserFactsStore(vault),
        conversations = VaultConversationStore(vault),
        taskHistory = VaultTaskHistoryStore(vault),
    )
    val geofences = GeofenceStore(vault)
    val scheduler = TaskScheduler(appContext, vault)

    // --- perception and action ------------------------------------------------
    /**
     * The screen agent downscales to this before the VLM sees it. 512 px on the long
     * edge is what the 450 M vision model was trained around, and it keeps the bitmap
     * that MediaProjection hands over inside the budget a 3 GB phone has left after the
     * orchestrator is resident.
     */
    val vision = LlamaVisionDescriber(
        models = models,
        llama = llama,
        captureWidth = 512,
        captureHeight = 512,
    )

    val screen = AccessibilityScreenController(
        context = appContext,
        screenshots = { ScreenshotSourceRegistry.current },
        ocr = ocr,
        vision = vision,
    )

    val voice = AndroidVoiceController(appContext, vault, models, whisper, piper, phonemizer)

    // --- plugins --------------------------------------------------------------
    val downloader = ModelDownloader(http, network, vault)

    private val enablement = PluginEnablement(vault)
    /**
     * Per-plugin settings from the vault's `plugins_config/<name>.json`. Exposed because
     * the connectors screen reads and writes it directly: a Home Assistant URL is the
     * user's to type, not something the agent should have to be asked to set.
     */
    val configStore: PluginConfigStore = VaultPluginConfigStore(vault)

    /**
     * Decides what a plugin may touch. The registry is passed in per call rather than
     * captured, so a plugin delegating to another one goes through the same enablement,
     * permission, audit and confirmation pipeline as the agent's own call -- there is no
     * private side door between plugins.
     */
    private val contextFactory = PluginContextFactory { task, config, registry ->
        PluginContext(
            appContext = appContext,
            vault = vault,
            screen = screen,
            models = models,
            textModel = textModel,
            http = http,
            network = network,
            secrets = secrets,
            safety = safety,
            guard = guard,
            audit = audit,
            notifier = notifier,
            undo = undo,
            stores = stores,
            voice = voice,
            scheduler = scheduler,
            task = task,
            plugins = registry,
            config = config,
        )
    }

    /**
     * `lateinit` because [undo] above closes over it. Assigned in [start], which the
     * Application calls once; reading it before that is a programming error and throws
     * rather than returning a half-built manager.
     */
    lateinit var pluginManager: PluginManager
        private set

    // --- agent ----------------------------------------------------------------
    lateinit var agent: SarothiAgent
        private set

    lateinit var rules: NotificationRuleEngine
        private set

    /**
     * Finishes wiring the objects that depend on each other, and publishes the seams
     * that Android-started components (receivers, foreground services, the
     * accessibility host) reach through because they cannot be constructor-injected.
     */
    fun start() {
        persona.refresh()

        val personaAccess = PersonaAccess(
            read = { persona.persona.value },
            write = { next -> persona.save(next) },
        )

        val plugins = BuiltinPlugins.all(personaAccess)
        pluginManager = PluginManager(
            plugins = plugins,
            enablement = enablement,
            permissionGuard = guard,
            audit = audit,
            safety = safety,
            undo = undo,
            contextFactory = contextFactory,
            configStore = configStore,
        )

        agent = SarothiAgent(
            appContext = appContext,
            plugins = pluginManager,
            models = models,
            llama = llama,
            stores = stores,
            screen = screen,
            voice = voice,
            safety = safety,
            audit = audit,
            notifier = notifier,
            ramPolicy = ramPolicy,
            scope = scope,
            personaProvider = { persona.persona.value },
        )

        // The two services Android starts on its own -- the model downloader and the
        // geofence watcher -- cannot be constructor-injected, and both check their
        // registry before doing anything. Publishing them here is what turns those
        // features on; without it each service starts, finds nothing, and stops with an
        // honest "cannot proceed" notification.
        ModelDownloadRegistry.attach(downloader, notifier)
        // Published with the graph so the download service -- which Android may start
        // into a process of its own -- enforces the same choice the Settings screen
        // shows, rather than defaulting to something the user changed.
        ModelDownloadRegistry.setAllowMobileData(allowMobileData)
        GeofenceRegistry.attach(geofences, notifier)

        rules = NotificationRuleEngine(
            scope = scope,
            schedulerProvider = { scheduler },
            notifier = notifier,
        )
    }

    /**
     * Whether a model download may use mobile data. Off by default: these files are
     * 60-220 MB and spending someone's data allowance without asking is not a default
     * worth having. Device-local rather than in the vault, because it describes this
     * phone's data plan and should not follow the SD card to another one.
     */
    var allowMobileData: Boolean = secrets.getBoolean(KEY_ALLOW_MOBILE_DATA, false)
        set(value) {
            field = value
            secrets.putBoolean(KEY_ALLOW_MOBILE_DATA, value)
            ModelDownloadRegistry.setAllowMobileData(value)
        }

    fun shutdown() {
        GeofenceRegistry.detach(geofences)
        ModelDownloadRegistry.detach(downloader)
        models.releaseAll()
        scope.cancel()
    }

    private companion object {
        const val KEY_ALLOW_MOBILE_DATA = "downloads.allowMobileData"
    }
}

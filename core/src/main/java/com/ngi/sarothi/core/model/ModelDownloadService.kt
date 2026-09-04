package com.ngi.sarothi.core.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.ngi.sarothi.core.R
import com.ngi.sarothi.core.capability.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Publishes the downloader to a service Android starts on its own.
 *
 * [ModelDownloadService] can be started by the system after a reboot, or restarted
 * with `START_STICKY`, and a service Android constructs cannot be injected. The
 * app's graph publishes here when it is built; the service checks first and tells
 * the user it cannot proceed rather than failing silently or pretending to
 * download.
 */
object ModelDownloadRegistry {
    @Volatile
    private var downloader: ModelDownloader? = null

    @Volatile
    private var notifierInstance: Notifier? = null

    @Volatile
    private var allowMobileData: Boolean = false

    fun attach(downloader: ModelDownloader, notifier: Notifier) {
        this.downloader = downloader
        this.notifierInstance = notifier
    }

    fun detach(downloader: ModelDownloader) {
        if (this.downloader === downloader) {
            this.downloader = null
            this.notifierInstance = null
        }
    }

    /** The user's "download over mobile data" setting, published with the graph. */
    fun setAllowMobileData(allowed: Boolean) {
        allowMobileData = allowed
    }

    val current: ModelDownloader? get() = downloader
    val notifier: Notifier? get() = notifierInstance
    val mayUseMobileData: Boolean get() = allowMobileData
}

/**
 * Keeps model downloads alive in the foreground.
 *
 * Foreground-typed `dataSync` because a 400 MB model over a phone connection takes
 * long enough that Android would otherwise stop it part way, and a half-written
 * GGUF is not resumable from an unknown offset. The notification carries real
 * progress — bytes, percentage and which mirror is being tried — and Pause and
 * Cancel actions, because a download the user cannot stop is a download they will
 * force-quit the app over.
 *
 * Pausing does not delete anything: [ModelDownloader] resumes from the bytes
 * already on disk with an HTTP `Range`, so a paused download continues where it
 * stopped, against the next source if the first one failed.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var work: Job? = null

    @Volatile
    private var queue: MutableList<String> = mutableListOf()

    @Volatile
    private var currentModel: CatalogModel? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                paused.set(true)
                currentModel?.let { notifyPaused(it) }
                return START_STICKY
            }
            ACTION_RESUME -> {
                paused.set(false)
                work?.cancel()
                work = scope.launch { runQueue() }
                return START_STICKY
            }
            ACTION_CANCEL -> {
                cancelled.set(true)
                paused.set(false)
                queue.clear()
                work?.cancel()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DOWNLOAD -> {
                val ids = intent.getStringArrayExtra(EXTRA_MODEL_IDS)?.toList() ?: emptyList()
                if (ids.isEmpty()) {
                    Log.w(TAG, "Started with ACTION_DOWNLOAD but no model ids")
                    stopSelf()
                    return START_NOT_STICKY
                }
                queue = ids.toMutableList()
                paused.set(false)
                cancelled.set(false)
                startForegroundNow()
                work?.cancel()
                work = scope.launch { runQueue() }
            }
            else -> {
                Log.w(TAG, "Started with unknown action ${intent?.action}")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        work?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runQueue() {
        val downloader = ModelDownloadRegistry.current
        if (downloader == null) {
            Log.w(TAG, "No downloader registered; cannot download")
            notifyFailure(
                "Sarothi cannot download models right now",
                "Open Sarothi and unlock your vault, then start the download again. The service was " +
                    "started before Sarothi's own components were ready.",
            )
            stopForegroundCompat()
            stopSelf()
            return
        }

        while (queue.isNotEmpty() && !cancelled.get()) {
            val id = queue.removeAt(0)
            val model = ModelCatalog.ALL.firstOrNull { it.id == id }
            if (model == null) {
                Log.w(TAG, "Unknown model id '$id' in the queue")
                notifyFailure(
                    "Unknown model \"$id\"",
                    "Sarothi's catalogue has no model with that id, so nothing was downloaded.",
                )
                continue
            }
            currentModel = model
            notifyProgress(model, 0L, model.sizeBytes, 0, model.sources.size, "starting")

            val outcome = downloader.download(
                model = model,
                allowMobileData = ModelDownloadRegistry.mayUseMobileData,
                onProgress = { progress -> notifyProgress(
                    model = progress.model,
                    written = progress.bytesWritten,
                    total = progress.totalBytes,
                    sourceIndex = progress.sourceIndex,
                    sourceCount = progress.sourceCount,
                    sourceLabel = progress.sourceLabel,
                ) },
                shouldContinue = { !cancelled.get() && !paused.get() },
            )

            when (outcome) {
                is DownloadOutcome.Success -> {
                    notifySuccess(model, outcome.downloadedNow)
                    Log.i(TAG, "${model.id} ready (downloaded now: ${outcome.downloadedNow})")
                }
                is DownloadOutcome.BlockedByNetworkPolicy -> {
                    notifyFailure("Download waiting for a network", outcome.reason)
                    // Put it back and stop: the user has to change network or the
                    // mobile-data setting. Retrying in a loop would only re-ask.
                    queue.add(0, model.id)
                    stopForegroundCompat()
                    stopSelf()
                    return
                }
                is DownloadOutcome.Cancelled -> {
                    if (cancelled.get()) {
                        notifyFailure(
                            "Download cancelled",
                            "${model.fileName}: ${outcome.bytesWritten} bytes were downloaded and left " +
                                "on disk, so it will resume from there.",
                        )
                    } else {
                        notifyPaused(model)
                        queue.add(0, model.id)
                        return
                    }
                }
                is DownloadOutcome.ChecksumRejected -> {
                    notifyFailure(
                        "\"${model.fileName}\" failed its checksum",
                        "Expected ${outcome.expected.take(12)}… but the file hashes to " +
                            "${outcome.actual.take(12)}…. The corrupt file was deleted. This is usually " +
                            "a truncated download or a mirror serving a different build.",
                    )
                }
                is DownloadOutcome.Failed -> {
                    notifyFailure(
                        "\"${model.fileName}\" could not be downloaded",
                        outcome.manualInstructions,
                    )
                    Log.w(TAG, "${model.id} failed: ${outcome.attempts.joinToString { "${it.source.label}: ${it.outcome}" }}")
                }
            }
            currentModel = null
        }

        if (!cancelled.get()) {
            stopForegroundCompat()
            stopSelf()
        }
    }

    // --- notifications -------------------------------------------------------

    private fun notifyProgress(
        model: CatalogModel,
        written: Long,
        total: Long,
        sourceIndex: Int,
        sourceCount: Int,
        sourceLabel: String,
    ) {
        val manager = notificationManager() ?: return
        val percent = if (total > 0) (written * 100 / total).toInt().coerceIn(0, 100) else 0
        val text = buildString {
            append(formatBytes(written)).append(" of ").append(formatBytes(total))
            append(" (").append(percent).append("%)")
            if (sourceCount > 1) {
                append(" · source ").append(sourceIndex + 1).append('/').append(sourceCount)
                if (sourceLabel.isNotBlank()) append(" · ").append(sourceLabel)
            }
        }
        val notification = baseBuilder()
            .setContentTitle("Downloading ${model.fileName}")
            .setContentText(text)
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .addAction(pauseAction())
            .addAction(cancelAction())
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun notifyPaused(model: CatalogModel) {
        val manager = notificationManager() ?: return
        val notification = baseBuilder()
            .setContentTitle("Download paused: ${model.fileName}")
            .setContentText("The bytes already downloaded are kept, so it resumes where it stopped.")
            .setOngoing(true)
            .addAction(resumeAction())
            .addAction(cancelAction())
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun notifySuccess(model: CatalogModel, downloadedNow: Boolean) {
        val manager = notificationManager() ?: return
        val notification = baseBuilder()
            .setContentTitle(
                if (downloadedNow) "Downloaded ${model.fileName}" else "${model.fileName} is ready",
            )
            .setContentText(
                if (downloadedNow) {
                    "${formatBytes(model.sizeBytes)}, checksum verified."
                } else {
                    "It was already on disk and its checksum verified, so nothing was downloaded."
                },
            )
            .setOngoing(false)
            .build()
        runCatching { manager.notify(RESULT_NOTIFICATION_ID, notification) }
        ModelDownloadRegistry.notifier?.info(
            "model-${model.id}",
            if (downloadedNow) "Downloaded ${model.fileName}" else "${model.fileName} is ready",
            "${model.displayName} (${formatBytes(model.sizeBytes)}), checksum verified.",
        )
    }

    private fun notifyFailure(title: String, detail: String) {
        val manager = notificationManager() ?: return
        val notification = baseBuilder()
            .setContentTitle(title)
            .setContentText(detail.take(300))
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setOngoing(false)
            .build()
        runCatching { manager.notify(RESULT_NOTIFICATION_ID, notification) }
        ModelDownloadRegistry.notifier?.warning("model-download", title, detail)
    }

    private fun baseBuilder(): Notification.Builder {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sarothi_status)
            .setOnlyAlertOnce(true)
        contentIntent?.let { builder.setContentIntent(it) }
        return builder
    }

    private fun pauseAction(): Notification.Action = action("Pause", ACTION_PAUSE, 1)
    private fun resumeAction(): Notification.Action = action("Resume", ACTION_RESUME, 2)
    private fun cancelAction(): Notification.Action = action("Cancel", ACTION_CANCEL, 3)

    private fun action(title: String, action: String, requestCode: Int): Notification.Action {
        val pending = PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ModelDownloadService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, title, pending).build()
    }

    private fun startForegroundNow() {
        val notification = baseBuilder()
            .setContentTitle("Preparing download")
            .setContentText("Checking the network and the files already on disk.")
            .setProgress(100, 0, true)
            .setOngoing(true)
            .addAction(pauseAction())
            .addAction(cancelAction())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        // STOP_FOREGROUND_REMOVE is API 24 and minSdk is 26, so the deprecated
        // stopForeground(true) branch this guarded could never have run.
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun createChannel() {
        // NotificationChannel is API 26 and minSdk is 26, so there is no older path to
        // guard against.
        val manager = notificationManager() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress for model downloads, with pause and cancel."
            setShowBadge(false)
        }
        runCatching { manager.createNotificationChannel(channel) }
    }

    companion object {
        private const val TAG = "SarothiModelDl"
        const val ACTION_DOWNLOAD = "com.ngi.sarothi.core.model.action.DOWNLOAD"
        const val ACTION_PAUSE = "com.ngi.sarothi.core.model.action.PAUSE"
        const val ACTION_RESUME = "com.ngi.sarothi.core.model.action.RESUME"
        const val ACTION_CANCEL = "com.ngi.sarothi.core.model.action.CANCEL"
        const val EXTRA_MODEL_IDS = "com.ngi.sarothi.core.model.extra.MODEL_IDS"
        private const val CHANNEL_ID = "sarothi_model_download"
        private const val NOTIFICATION_ID = 4100
        private const val RESULT_NOTIFICATION_ID = 4101

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_073_741_824L -> "%.2f GiB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> "%.1f MiB".format(bytes / 1_048_576.0)
            bytes >= 1024L -> "%.0f KiB".format(bytes / 1024.0)
            else -> "$bytes B"
        }

        /** Queues [modelIds] for download and brings the service to the foreground. */
        fun start(context: Context, modelIds: List<String>) {
            val intent = Intent(context, ModelDownloadService::class.java)
                .setAction(ACTION_DOWNLOAD)
                .putExtra(EXTRA_MODEL_IDS, modelIds.toTypedArray())
            runCatching {
                // startForegroundService is API 26 and minSdk is 26; startService would
                // throw IllegalStateException for a foreground service on every device
                // this app can install on, so the alternative was dead.
                context.startForegroundService(intent)
            }.onFailure { failure ->
                Log.e(TAG, "Could not start the download service", failure)
            }
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun cancel(context: Context) = send(context, ACTION_CANCEL)

        private fun send(context: Context, action: String) {
            runCatching {
                context.startService(Intent(context, ModelDownloadService::class.java).setAction(action))
            }.onFailure { failure -> Log.w(TAG, "Could not send $action", failure) }
        }
    }
}

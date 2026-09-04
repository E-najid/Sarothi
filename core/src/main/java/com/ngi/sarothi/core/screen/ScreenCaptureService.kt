package com.ngi.sarothi.core.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.ngi.sarothi.core.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the MediaProjection capture session behind the screenshot fallback.
 *
 * Started only after the user has explicitly consented through the system
 * dialog; the resulting `Intent` is handed to [start] verbatim because Android
 * does not let an app mint a projection token for itself.
 *
 * The service stays foreground-typed `mediaProjection` for as long as the token
 * is held, which is what keeps the status-bar indicator honest: while it is up,
 * Sarothi *can* take a screenshot, and the user can see that.
 */
class ScreenCaptureService : Service(), ScreenshotSource {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var displayWidth = 0
    private var displayHeight = 0
    private var displayDensity = 0

    @Volatile
    private var failureReason: String? = null

    /** The single in-flight capture request, if any. */
    private val pendingFrame = AtomicReference<CompletableDeferred<Bitmap?>?>()

    /** Set when the projection has been started at least once in this process. */
    private var mainHandler: Handler? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by the system or the user")
            failureReason = "Screen capture permission was revoked."
            tearDown()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ScreenshotSourceRegistry.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNow()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (data == null) {
                    failureReason = "The screen-capture consent result was empty; capture cannot start."
                    stopSelf()
                    return START_NOT_STICKY
                }
                beginProjection(resultCode, data)
            }
            ACTION_STOP -> {
                tearDown()
                stopSelf()
            }
            else -> {
                // Started without a consent token: put up the notification so the
                // user can see something is running, but do not claim to capture.
                startForegroundNow()
                failureReason = "Screen capture has not been permitted yet."
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            failureReason = "This device does not expose a MediaProjection manager."
            return
        }
        val created = runCatching { manager.getMediaProjection(resultCode, data) }.getOrElse {
            Log.w(TAG, "getMediaProjection failed", it)
            failureReason = "Android refused the screen-capture token: ${it.message}"
            return
        }
        if (created == null) {
            failureReason = "Android returned no screen-capture token for the granted permission."
            return
        }
        // Registering the callback before creating the virtual display is required
        // from API 34; doing it always keeps one code path.
        created.registerCallback(projectionCallback, obtainMainHandler())
        projection = created
        failureReason = null
        startVirtualDisplay(created)
    }

    private fun startVirtualDisplay(created: MediaProjection) {
        val metrics = readDisplayMetrics()
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels
        displayDensity = metrics.densityDpi
        if (displayWidth <= 0 || displayHeight <= 0) {
            failureReason = "Could not determine the display size, so no capture surface can be created."
            return
        }

        val thread = HandlerThread("sarothi-capture").also { it.start() }
        workerThread = thread
        workerHandler = Handler(thread.looper)

        val reader = ImageReader.newInstance(
            displayWidth,
            displayHeight,
            PixelFormat.RGBA_8888,
            MAX_IMAGES,
        )
        reader.setOnImageAvailableListener({ available -> onImageAvailable(available) }, workerHandler)
        imageReader = reader

        val display = runCatching {
            created.createVirtualDisplay(
                "sarothi-screen",
                displayWidth,
                displayHeight,
                displayDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                workerHandler,
            )
        }.getOrElse {
            Log.w(TAG, "createVirtualDisplay failed", it)
            failureReason = "Android refused to mirror the display: ${it.message}"
            return
        }
        if (display == null) {
            failureReason = "Android returned no virtual display for screen capture."
            return
        }
        virtualDisplay = display
    }

    private fun readDisplayMetrics(): DisplayMetrics {
        val manager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = runCatching { manager.currentWindowMetrics.bounds }.getOrNull()
            if (bounds != null) {
                val metrics = DisplayMetrics()
                metrics.widthPixels = bounds.width()
                metrics.heightPixels = bounds.height()
                metrics.densityDpi = resources.configuration.densityDpi
                return metrics
            }
        }
        return resources.displayMetrics
    }

    /** Converts the newest frame to a [Bitmap] and hands it to whoever is waiting. */
    private fun onImageAvailable(reader: ImageReader) {
        val waiter = pendingFrame.get()
        if (waiter == null) {
            // Nobody asked for a frame: drain it so the reader does not stall.
            reader.acquireLatestImage()?.close()
            return
        }
        val image = runCatching { reader.acquireLatestImage() }.getOrNull()
        if (image == null) {
            waiter.complete(null)
            return
        }
        try {
            val bitmap = image.toBitmap()
            if (!waiter.complete(bitmap)) {
                // The caller already timed out and gave up; do not leak the frame.
                bitmap.recycle()
            }
        } catch (failure: Exception) {
            Log.w(TAG, "Frame conversion failed", failure)
            waiter.complete(null)
        } finally {
            image.close()
        }
    }

    /**
     * Copies an RGBA_8888 [android.media.Image] into an ARGB_8888 bitmap.
     *
     * The row stride is almost always wider than `width * pixelStride` (GPU
     * alignment), so the padding has to be cropped away; skipping that step
     * produces the classic diagonally-sheared screenshot.
     */
    private fun android.media.Image.toBitmap(): Bitmap {
        val plane = planes.firstOrNull()
            ?: throw IllegalStateException("Capture frame has no pixel planes")
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0) throw IllegalStateException("Capture frame has pixelStride $pixelStride")
        val rowPadding = rowStride - pixelStride * width
        val stridePixels = width + (rowPadding / pixelStride).coerceAtLeast(0)

        val raw = Bitmap.createBitmap(stridePixels, height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)
        return if (stridePixels == width) {
            raw
        } else {
            Bitmap.createBitmap(raw, 0, 0, width, height).also { if (it !== raw) raw.recycle() }
        }
    }

    // ---------------------------------------------------------- ScreenshotSource

    override val isReady: Boolean
        get() = projection != null && virtualDisplay != null && imageReader != null

    override fun unavailabilityReason(): String? = when {
        isReady -> null
        failureReason != null -> failureReason
        else -> "Screen capture has not been permitted yet. Tap 'Allow screen access' in Sarothi and " +
            "accept Android's prompt."
    }

    override suspend fun capture(maxDimension: Int): CaptureResult {
        if (!isReady) {
            return CaptureResult.Denied(unavailabilityReason() ?: "Screen capture is unavailable.", true)
        }
        val waiter = CompletableDeferred<Bitmap?>()
        if (!pendingFrame.compareAndSet(null, waiter)) {
            return CaptureResult.Denied("Another screenshot is already in flight.", false)
        }
        return try {
            val bitmap = withTimeoutOrNull(CAPTURE_TIMEOUT_MILLIS) { waiter.await() }
            if (bitmap == null) waiter.cancel()
            if (bitmap == null) {
                return CaptureResult.Denied(
                    "No screen frame arrived within ${CAPTURE_TIMEOUT_MILLIS}ms. The display may be " +
                        "off, secure (DRM/banking apps blank their mirror), or the projection was revoked.",
                    needsUserConsent = false,
                )
            }
            val sourceWidth = bitmap.width
            val scaled = withContext(Dispatchers.Default) { downscale(bitmap, maxDimension) }
            CaptureResult.Captured(
                bitmap = scaled,
                width = scaled.width,
                height = scaled.height,
                capturedAtEpochMillis = System.currentTimeMillis(),
                scaledFrom = sourceWidth,
            )
        } finally {
            pendingFrame.compareAndSet(waiter, null)
        }
    }

    private fun downscale(bitmap: Bitmap, maxDimension: Int): Bitmap {
        if (maxDimension <= 0) return bitmap
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest.toFloat()
        val targetWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    /**
     * Coordinates captured from a downscaled bitmap, mapped back to real screen
     * pixels. The vision model and OCR both work on the scaled image, so every
     * tap they propose has to go through here before `dispatchGesture`.
     */
    fun scaleToScreen(captured: CaptureResult.Captured, x: Int, y: Int): Pair<Int, Int> {
        val metrics = readDisplayMetrics()
        if (captured.width <= 0 || captured.height <= 0) return x to y
        val sx = metrics.widthPixels.toFloat() / captured.width.toFloat()
        val sy = metrics.heightPixels.toFloat() / captured.height.toFloat()
        return (x * sx).toInt().coerceIn(0, metrics.widthPixels - 1) to
            (y * sy).toInt().coerceIn(0, metrics.heightPixels - 1)
    }

    private fun tearDown() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        runCatching { workerThread?.quitSafely() }
        virtualDisplay = null
        imageReader = null
        projection = null
        workerThread = null
        workerHandler = null
        pendingFrame.getAndSet(null)?.let { waiter -> waiter.complete(null) }
    }

    override fun onDestroy() {
        ScreenshotSourceRegistry.detach(this)
        tearDown()
        super.onDestroy()
    }

    private fun obtainMainHandler(): Handler = mainHandler ?: Handler(mainLooper).also { mainHandler = it }

    private fun createChannel() {
        // NotificationChannel is API 26 and minSdk is 26.
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sarothi_capture_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.sarothi_capture_channel_desc)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // The channel-based constructor is API 26 and minSdk is 26.
        val builder = Notification.Builder(this, CHANNEL_ID)
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Sarothi can read the screen as an image")
            .setContentText(
                if (isReady) "Screen capture is active. Tap Stop to end it."
                else "Waiting for capture to become available.",
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    "Stop capture",
                    stopIntent,
                ).build(),
            )
            .build()
    }

    companion object {
        private const val TAG = "SarothiCapture"
        private const val CHANNEL_ID = "sarothi_capture"
        private const val NOTIFICATION_ID = 0x5A10
        private const val MAX_IMAGES = 2
        private const val CAPTURE_TIMEOUT_MILLIS = 2500L

        const val ACTION_START = "com.ngi.sarothi.core.screen.action.START_CAPTURE"
        const val ACTION_STOP = "com.ngi.sarothi.core.screen.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "com.ngi.sarothi.core.screen.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.ngi.sarothi.core.screen.extra.RESULT_DATA"

        /** The system intent that produces the consent token. */
        fun consentIntent(context: Context): Intent {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            return manager.createScreenCaptureIntent()
        }

        /** Hands the consent token to the service. Call from `onActivityResult`. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            // startForegroundService is API 26 and minSdk is 26.
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }

        val isCapturing: Boolean get() = ScreenshotSourceRegistry.current?.isReady == true
    }
}

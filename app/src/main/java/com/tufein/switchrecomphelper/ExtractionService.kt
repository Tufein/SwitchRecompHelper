package com.tufein.switchrecomphelper

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the extraction is doing, for the notification. Written by [MainViewModel]. */
data class ExtractionStatus(val running: Boolean = false, val progress: Int = 0, val text: String = "")

/**
 * The link between the extraction in [MainViewModel] and [ExtractionService]. The ViewModel publishes its state
 * here; the service shows it in the notification and calls [cancelHandler] when the user taps Cancel there.
 */
object ExtractionBridge {
    val status = MutableStateFlow(ExtractionStatus())

    @Volatile var cancelHandler: (() -> Unit)? = null

    fun cancel() {
        cancelHandler?.invoke()
    }
}

/**
 * Keeps an extraction going while the app is in the background or the screen is off. It is a foreground service
 * (data sync, with a notification) and holds a partial wake lock, so the CPU and the process keep running. The
 * extraction itself stays in [MainViewModel]; the service stops by itself when [ExtractionBridge.status] says the
 * extraction has ended.
 */
class ExtractionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ExtractionBridge.cancel()
            return START_NOT_STICKY
        }
        val status = ExtractionBridge.status.value
        if (!status.running) {
            // Started after the extraction already ended: there is nothing to keep alive.
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel(this)
        // Must happen right after startForegroundService(), or Android stops the app with an error.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(status),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        acquireWakeLock()
        if (statusJob == null) {
            statusJob = scope.launch { ExtractionBridge.status.collect(::onStatus) }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15+ limits how long a data-sync foreground service may run. Stop the extraction cleanly at that
     * point instead of letting the system kill the app; the output written so far stays.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ExtractionBridge.cancel()
        stopSelf()
    }

    private fun onStatus(status: ExtractionStatus) {
        if (!status.running) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(status))
        }
    }

    private fun notification(status: ExtractionStatus): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ExtractionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cartridge)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(status.text.ifEmpty { getString(R.string.notif_starting) })
            .setProgress(1000, status.progress, status.progress == 0)
            .setContentIntent(open)
            .addAction(0, getString(R.string.btn_cancel), cancel)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SwitchRecompHelper:extraction").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.tufein.switchrecomphelper.CANCEL_EXTRACTION"
        private const val CHANNEL_ID = "extraction"
        private const val NOTIFICATION_ID = 1

        /** Starts the service. Call only while the app is visible, and set [ExtractionBridge.status] to running first. */
        fun start(context: Context) {
            val intent = Intent(context, ExtractionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}

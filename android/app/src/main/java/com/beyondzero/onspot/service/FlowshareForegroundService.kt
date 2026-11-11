package com.beyondzero.onspot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.beyondzero.onspot.MainActivity
import com.beyondzero.onspot.R
import com.beyondzero.onspot.data.FlowshareCore
import com.beyondzero.onspot.domain.model.TransferRecord
import com.beyondzero.onspot.domain.model.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps Flowshare discoverable and able to send/receive after the app is closed or swiped away,
 * the way the phone's own Quick Share / Nearby Share does. Shows a low-priority "Flowshare is on"
 * notification while idle, and a live progress notification while a file is sending or receiving.
 *
 * This service does not itself decide *when* discovery starts (that is still triggered from
 * [com.beyondzero.onspot.presentation.home.MainScreen] when the app opens, same as
 * before) — it only keeps [FlowshareCore] alive and reflects its state in a notification.
 */
class FlowshareForegroundService : Service() {
    private lateinit var core: FlowshareCore
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        core = FlowshareCore.get(applicationContext)
        createNotificationChannel()
        startForegroundCompat(idleNotification())
        observeTransfers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            core.shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun observeTransfers() {
        serviceScope.launch {
            core.recentTransfers.collectLatest { list ->
                updateNotification(list.lastOrNull { it.state == TransferState.TRANSFERRING })
            }
        }
    }

    private fun updateNotification(active: TransferRecord?) {
        val notification = if (active != null) progressNotification(active) else idleNotification()
        (getSystemService(NotificationManager::class.java))?.notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun stopActionIntent(): PendingIntent {
        val intent = Intent(this, FlowshareForegroundService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            // A plain white silhouette, not the full-colour launcher mark: Android renders the
            // status-bar icon as a solid white cutout of the alpha channel regardless, so a
            // colourful icon here would just show up as a blank white blob.
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Turn off", stopActionIntent())

    private fun idleNotification(): Notification =
        baseBuilder()
            .setContentTitle("OnSpot is on")
            .setContentText("Visible to nearby devices · ${core.settingsManager.deviceName}")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun progressNotification(t: TransferRecord): Notification {
        val verb = if (t.isIncoming) "Receiving" else "Sending"
        val percent = (t.progress * 100).toInt().coerceIn(0, 100)
        val peer = t.deviceName.ifEmpty { t.deviceId }
        return baseBuilder()
            .setContentTitle("$verb ${t.fileName}")
            .setContentText("$percent% · ${if (t.isIncoming) "from" else "to"} $peer")
            .setProgress(100, percent, false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return // channels (and this class) are API 26+
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "OnSpot background sharing", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps OnSpot discoverable and shows transfer progress"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "flowshare_background"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.beyondzero.onspot.action.STOP"
    }
}

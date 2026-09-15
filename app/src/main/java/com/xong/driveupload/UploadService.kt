package com.xong.driveupload

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
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Fallback for Android 13 and lower. Android 14+ uses UploadJobService (UIDT). */
class UploadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var stateStore: UploadStateStore
    private var uploadJob: Job? = null
    @Volatile private var cancelRequested = false

    override fun onCreate() {
        super.onCreate()
        stateStore = UploadStateStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelRequested = true
            ACTION_START, null -> startUploadIfNeeded()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun startUploadIfNeeded() {
        if (uploadJob?.isActive == true) return
        val snapshot = stateStore.load()
        if (snapshot.sourceUri.isBlank() || snapshot.accountEmail.isBlank() || snapshot.totalBytes < 0L) {
            stopSelf()
            return
        }
        cancelRequested = false
        startAsForeground(snapshot)
        val engine = UploadEngine(
            this,
            cancelCheck = { cancelRequested },
            onStateChanged = { state ->
                broadcastState()
                if (state.status == UploadStatus.RUNNING) updateNotification(state)
            }
        )
        uploadJob = scope.launch {
            try {
                val completed = engine.run()
                notifyFinal(getString(R.string.upload_complete_notification), completed.fileName)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            } catch (_: CancellationException) {
                engine.markCanceled()
                broadcastState()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Throwable) {
                val failed = engine.markError(e)
                notifyFinal(getString(R.string.upload_failed_notification), failed.fileName)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun broadcastState() {
        sendBroadcast(Intent(UploadStateStore.ACTION_UPLOAD_STATE_CHANGED).setPackage(packageName))
    }

    private fun startAsForeground(snapshot: UploadSnapshot) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildProgressNotification(snapshot), type)
    }

    private fun updateNotification(snapshot: UploadSnapshot) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildProgressNotification(snapshot))
    }

    private fun buildProgressNotification(snapshot: UploadSnapshot): Notification {
        val percent = if (snapshot.totalBytes > 0L) ((snapshot.uploadedBytes * 100L) / snapshot.totalBytes).toInt().coerceIn(0, 100) else 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_upload)
            .setContentTitle(getString(R.string.upload_running_notification))
            .setContentText("${snapshot.fileName} · $percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainPendingIntent())
            .build()
    }

    private fun notifyFinal(title: String, fileName: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_upload)
            .setContentTitle(title)
            .setContentText(fileName)
            .setAutoCancel(true)
            .setContentIntent(mainPendingIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1, notification)
    }

    private fun mainPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.upload_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.upload_channel_description)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.xong.driveupload.action.START_UPLOAD"
        const val ACTION_CANCEL = "com.xong.driveupload.action.CANCEL_UPLOAD"
        private const val CHANNEL_ID = "drive_upload_progress"
        private const val NOTIFICATION_ID = 4101
    }
}

package com.xong.driveupload

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android 14+ user-initiated data transfer job. This is the platform API intended for
 * long, user-started uploads and avoids Android 15's six-hour dataSync FGS budget.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UploadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var stateStore: UploadStateStore
    private var runningJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        stateStore = UploadStateStore(this)
        createNotificationChannel()
    }

    @SuppressLint("MissingPermission")
    override fun onStartJob(params: JobParameters): Boolean {
        val snapshot = stateStore.load()
        if (snapshot.sourceUri.isBlank() || snapshot.accountEmail.isBlank() || snapshot.totalBytes < 0L) {
            return false
        }

        setNotification(
            params,
            NOTIFICATION_ID,
            buildProgressNotification(snapshot),
            JOB_END_NOTIFICATION_POLICY_DETACH
        )

        val engine = UploadEngine(
            this,
            cancelCheck = { stateStore.load().status == UploadStatus.CANCELED },
            onStateChanged = { state ->
                broadcastState()
                if (state.status == UploadStatus.RUNNING) {
                    runCatching {
                        setNotification(
                            params,
                            NOTIFICATION_ID,
                            buildProgressNotification(state),
                            JOB_END_NOTIFICATION_POLICY_DETACH
                        )
                    }
                }
            }
        )

        runningJob = scope.launch {
            try {
                val completed = engine.run()
                runCatching {
                    setNotification(
                        params,
                        NOTIFICATION_ID,
                        buildFinalNotification(getString(R.string.upload_complete_notification), completed.fileName, true),
                        JOB_END_NOTIFICATION_POLICY_DETACH
                    )
                }
                broadcastState()
                jobFinished(params, false)
            } catch (_: CancellationException) {
                // If the app explicitly canceled, the Activity already persisted CANCELED.
                // If Android stopped the UIDT job for system health, keep RUNNING so a retry can resume.
                if (stateStore.load().status == UploadStatus.CANCELED) {
                    broadcastState()
                }
            } catch (e: Throwable) {
                val failed = engine.markError(e)
                runCatching {
                    setNotification(
                        params,
                        NOTIFICATION_ID,
                        buildFinalNotification(getString(R.string.upload_failed_notification), failed.fileName, false),
                        JOB_END_NOTIFICATION_POLICY_DETACH
                    )
                }
                broadcastState()
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJob?.cancel()
        // Explicit user cancel should end permanently. System stops should be retried by JobScheduler.
        return stateStore.load().status != UploadStatus.CANCELED
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun broadcastState() {
        sendBroadcast(Intent(UploadStateStore.ACTION_UPLOAD_STATE_CHANGED).setPackage(packageName))
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

    private fun buildFinalNotification(title: String, fileName: String, success: Boolean): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_upload)
            .setContentTitle(title)
            .setContentText(fileName)
            .setAutoCancel(true)
            .setContentIntent(mainPendingIntent())
            .build()
    }

    private fun mainPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        101,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.upload_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.upload_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val JOB_ID = 4102
        private const val CHANNEL_ID = "drive_upload_progress"
        private const val NOTIFICATION_ID = 4102
    }
}

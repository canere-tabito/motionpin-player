package com.caneretabito.motionpin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.caneretabito.motionpin.data.JobStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** API35 mediaProcessing quota belongs to Android; never restart to evade it. */
class AiProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: MotionRuntime
    private var observing = false

    override fun onCreate() {
        super.onCreate()
        runtime = MotionRuntime.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "AI区間生成", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            runtime.stopProcessing("通知から停止しました。再開すると区間を最初から生成します。")
            finishService()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < 35) {
            finishService()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification("処理を準備しています"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            runtime.onProcessingServiceChanged(true)
        } catch (failure: RuntimeException) {
            runtime.stopProcessing("OSがバックグラウンド処理を許可しませんでした: ${failure.javaClass.simpleName}")
            finishService()
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            scope.launch {
                runtime.repository.jobs.collectLatest { jobs ->
                    val active = jobs.firstOrNull { it.status == JobStatus.RUNNING || it.status == JobStatus.QUEUED }
                    if (active == null) {
                        // Allow an enqueue transaction to finish; do not retain an idle FGS.
                        delay(1500)
                        finishService()
                    } else {
                        val label = "${(active.progress.coerceIn(0f, 1f) * 100).toInt()}% · ${active.inferenceCount} AIフレーム"
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(label))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, AiProcessingService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("MotionPin · AIスロー生成")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", pause)
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        runtime.stopProcessing("AndroidのmediaProcessing時間枠に達しました。OSの制限が解除されてから手動で再開してください。")
        finishService()
    }

    private fun finishService() {
        runtime.onProcessingServiceChanged(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runtime.onProcessingServiceChanged(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "motionpin-ai"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_PAUSE = "com.caneretabito.motionpin.PAUSE_AI"
    }
}

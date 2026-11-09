package code.name.monkey.retromusic.loudness

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.db.LoudnessDao
import code.name.monkey.retromusic.activities.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get
import java.io.File

/**
 * Foreground scan job. Does not start on install. Cancel via stop action.
 */
class LoudnessScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                job?.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val root = intent.getStringExtra(EXTRA_ROOT) ?: return START_NOT_STICKY
                val rootId = intent.getStringExtra(EXTRA_ROOT_ID) ?: "music"
                startInForeground()
                job?.cancel()
                job = scope.launch {
                    try {
                        CollisionKey.ensureLoaded(this@LoudnessScanService)
                        val repo = LoudnessRepository(get(LoudnessDao::class.java))
                        val scanner = LoudnessScanner(this@LoudnessScanService, repo)
                        val files = LoudnessScanner.listAudioFiles(File(root))
                        scanner.scanFiles(rootId, files) { progress ->
                            if (!isActive) return@scanFiles
                            updateNotification(progress.processed, progress.total, progress.currentPath)
                        }
                    } finally {
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        ensureChannel()
        val notification = buildNotification(0, 0, null)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(done: Int, total: Int, current: String?) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(done, total, current))
    }

    private fun buildNotification(done: Int, total: Int, current: String?): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, LoudnessScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.loudness_scan_notification_title))
            .setContentText(
                if (total <= 0) getString(R.string.loudness_scan_notification_text)
                else "$done / $total ${current ?: ""}"
            )
            .setSmallIcon(R.drawable.ic_equalizer)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setProgress(total.coerceAtLeast(0), done.coerceAtLeast(0), total <= 0)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Loudness scan", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "loudness_scan"
        private const val NOTIFICATION_ID = 47201
        const val ACTION_START = "code.name.monkey.retromusic.loudness.SCAN_START"
        const val ACTION_STOP = "code.name.monkey.retromusic.loudness.SCAN_STOP"
        const val EXTRA_ROOT = "root"
        const val EXTRA_ROOT_ID = "rootId"

        fun start(context: Context, rootPath: String, rootId: String) {
            val i = Intent(context, LoudnessScanService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ROOT, rootPath)
                .putExtra(EXTRA_ROOT_ID, rootId)
            ContextCompat.startForegroundService(context, i)
        }
    }
}

package com.megatron.player.cast

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.megatron.player.MainActivity
import com.megatron.player.R

/** Keeps the authenticated relay alive while the display is off; no independent media queue. */
class CastPlaybackService : Service() {
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            CastController.shared(this).stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("cast_playback", "电视投屏", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 41, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 42, Intent(this, CastPlaybackService::class.java).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "cast_playback").setSmallIcon(R.drawable.ic_brand_notification)
            .setContentTitle(intent?.getStringExtra("title") ?: "正在投屏")
            .setContentText("投屏中")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(0, "停止投屏", stop).build()
        startForeground(42, notification)
        if (wake == null) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MegatronPlayer:cast").apply {
            setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
        }
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) { CastController.shared(this).stop(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() { wake?.let { if (it.isHeld) it.release() }; wake = null; super.onDestroy() }
}

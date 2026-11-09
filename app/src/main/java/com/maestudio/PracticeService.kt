package com.maestudio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Mantiene vivo el proceso mientras el metronomo o el Tabata estan activos, para que
 * Android no los corte con la pantalla apagada. La logica esta en PracticeEngine.
 */
class PracticeService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Práctica en curso", NotificationManager.IMPORTANCE_LOW)
        )

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        // La practica sigue aunque se cierre la app, asi que se puede detener desde aqui.
        val stop = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, StopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("MAEStudio")
            .setContentText("Práctica en curso")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Detener", stop)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)

        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "maestudio:practice").apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_MS)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    class StopReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            PracticeEngine.init(context)
            PracticeEngine.stopAll()
            // Por si el servicio quedo encendido sin practica activa.
            context.stopService(Intent(context, PracticeService::class.java))
        }
    }

    private companion object {
        const val CHANNEL_ID = "practice"
        const val NOTIFICATION_ID = 1
        const val MAX_WAKE_MS = 4 * 60 * 60 * 1000L
    }
}

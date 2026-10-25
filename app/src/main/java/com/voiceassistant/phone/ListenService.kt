package com.voiceassistant.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the microphone open for the wake phrase while the screen is off. Android requires a visible notification for this, and the
 * service has to be started while the app is on screen (so after a restart of the phone, open the app once).
 */
class ListenService : Service() {
    private var mic: Mic? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Controller.turnHandsFree(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_QUIT) {
            Controller.quitCompletely()
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (!Controller.attachHandsFree(applicationContext)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (mic == null) {
            val m = Mic { frame -> Controller.onHandsFreeFrame(frame) }
            if (m.start()) mic = m else { Controller.detachHandsFree(); stopSelf(); return START_NOT_STICKY }
        }
        if (wakeLock == null) {
            // without this the processor can doze while the screen is off and the wake phrase would be missed
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceAssistant:listen").also { it.acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mic?.stop(); mic = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        Controller.detachHandsFree()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Listening", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while the assistant is listening for its wake phrase"
            setShowBadge(false)
        })
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ListenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val quit = PendingIntent.getService(
            this, 2, Intent(this, ListenService::class.java).setAction(ACTION_QUIT), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Listening for “${Controller.wakePhrase}”")
            .setContentText("Tap to open. Nothing is sent until you say it.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .addAction(Notification.Action.Builder(null, "Quit app", quit).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.voiceassistant.phone.STOP"
        const val ACTION_QUIT = "com.voiceassistant.phone.QUIT"
        private const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 1
    }
}

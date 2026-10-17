package com.encore.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a call alive while Encore is in the background or the phone is locked.
 * Without a microphone foreground service Android 14+ silences the mic and may kill the process.
 * Also holds a CPU wake lock and a low-latency Wi-Fi lock so audio does not stutter with the screen off.
 */
class CallService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var since = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANGUP) {
            CallManager.hangup()
            stopSelf()
            return START_NOT_STICKY
        }
        // startForegroundService() demands startForeground() every time, even if we stop right after
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NID, build(), type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (CallManager.phase == CallPhase.Idle) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireLocks()
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        ensureChannel(this)
        val phase = CallManager.phase
        val name = CallManager.peerName.ifBlank { "Encore call" }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val hang = PendingIntent.getService(
            this, 1,
            Intent(this, CallService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val person = Person.Builder().setName(name).setImportant(true).build()
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(name)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hang))

        if (phase == CallPhase.Active) {
            if (since == 0L) since = System.currentTimeMillis() - CallManager.seconds * 1000L
            b.setContentText("Ongoing voice call")
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(since)
        } else {
            b.setContentText(if (phase == CallPhase.Calling) "Calling…" else "Connecting…")
                .setUsesChronometer(false)
                .setShowWhen(false)
        }
        return b.build()
    }

    private fun acquireLocks() {
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "encore:call").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // safety timeout: 4 hours
            }
        }
        if (wifi == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifi = wm.createWifiLock(mode, "encore:call").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { if (wake?.isHeld == true) wake?.release() }
        runCatching { if (wifi?.isHeld == true) wifi?.release() }
        wake = null
        wifi = null
        super.onDestroy()
    }

    companion object {
        private const val CH = "call_ongoing_v1"
        private const val NID = 7003
        const val ACTION_HANGUP = "com.encore.app.CALL_SERVICE_HANGUP"

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CH) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CH, "Ongoing calls", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a call is in progress"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }

        /** Starts or refreshes the service for the current call phase. Safe to call often. */
        fun sync(ctx: Context) {
            val app = ctx.applicationContext
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            try {
                ContextCompat.startForegroundService(app, Intent(app, CallService::class.java))
            } catch (e: Exception) {
                // Android refused a background start; the call still works while the app is on screen
            }
        }

        fun stop(ctx: Context) {
            val app = ctx.applicationContext
            runCatching { app.stopService(Intent(app, CallService::class.java)) }
        }
    }
}

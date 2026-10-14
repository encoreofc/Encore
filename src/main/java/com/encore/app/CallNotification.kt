package com.encore.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Rings for an incoming call when the app is closed or in the background.
 * The server sends a high-priority push for every new call (see calls.js); this turns it into a
 * ringing notification that wakes the screen and shows the call over the lock screen.
 */
object CallNotification {
    const val CHANNEL = "calls_v1"
    const val ID = 7001
    const val EXTRA_CALL = "incoming_call_id"
    const val EXTRA_PEER = "incoming_call_peer"
    const val EXTRA_NAME = "incoming_call_name"

    fun show(ctx: Context, d: Map<String, String>) {
        val id = d["call_id"] ?: return
        // the app is already showing this call (realtime reached it first): do not ring twice
        if (CallManager.phase != CallPhase.Idle && CallManager.activeCallId == id) return
        val name = d["caller_name"] ?: "Someone"
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings when someone calls you"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 900, 900)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CALL, id)
            putExtra(EXTRA_PEER, d["caller_id"] ?: "")
            putExtra(EXTRA_NAME, name)
        }
        val pi = PendingIntent.getActivity(
            ctx, ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(name)
            .setContentText("Incoming voice call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter(30000L)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        // keep ringing until answered, cancelled or timed out
        n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify(ID, n)
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}

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
import androidx.core.app.Person

/**
 * Rings for an incoming call when the app is closed or in the background.
 * The server sends a high-priority push for every new call (see calls.js); this turns it into a
 * ringing notification that wakes the screen and shows the call over the lock screen.
 */
object CallNotification {
    // v2: Android freezes a channel's settings after it is created, so the heads-up / ring settings
    // only apply to a fresh channel id; the old one is removed in ensureChannel()
    const val CHANNEL = "calls_v2"
    private const val LEGACY_CHANNEL = "calls_v1"
    const val ID = 7001
    const val EXTRA_CALL = "incoming_call_id"
    const val EXTRA_PEER = "incoming_call_peer"
    const val EXTRA_NAME = "incoming_call_name"
    const val EXTRA_ACCEPT = "incoming_call_accept"
    const val ACTION_DECLINE = "com.encore.app.CALL_DECLINE"

    /** The caller waits 45 s, so the phone rings for the same time. */
    private const val RING_MS = 45_000L

    /** Creates the call channel (safe to call repeatedly). */
    fun ensureChannel(ctx: Context) {
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
            nm.deleteNotificationChannel(LEGACY_CHANNEL)
        }
    }

    fun show(ctx: Context, d: Map<String, String>, force: Boolean = false) {
        val id = d["call_id"] ?: return
        // the app is already showing this call (realtime reached it first): do not ring twice
        if (!force && CallManager.phase != CallPhase.Idle && CallManager.activeCallId == id) return
        val name = d["caller_name"] ?: "Someone"
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return // nothing can ring or show with notifications switched off
        ensureChannel(ctx)
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CALL, id)
            putExtra(EXTRA_PEER, d["caller_id"] ?: "")
            putExtra(EXTRA_NAME, name)
        }
        val pi = PendingIntent.getActivity(
            ctx, ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // "Answer" opens the app and accepts once the call screen is up (an activity intent: Android 12+ blocks trampolines)
        val answer = Intent(open).putExtra(EXTRA_ACCEPT, true)
        val answerPi = PendingIntent.getActivity(
            ctx, ID + 1, answer, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // "Decline" is handled in the background, without opening the app
        val decline = Intent(ctx, CallActionReceiver::class.java).setAction(ACTION_DECLINE).putExtra(EXTRA_CALL, id)
        val declinePi = PendingIntent.getBroadcast(
            ctx, ID + 2, decline, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val caller = Person.Builder().setName(name).setImportant(true).build()
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(name)
            .setContentText("Incoming voice call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter(RING_MS)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declinePi, answerPi))
            .build()
        // keep ringing until answered, cancelled or timed out
        n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify(ID, n)
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    /** Shows the call notification unless it is already up (the realtime invite reached the app while it was in the background). */
    fun ensureShown(ctx: Context, id: String, peer: String, name: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 23 && nm.activeNotifications.any { it.id == ID }) return
        show(ctx, mapOf("call_id" to id, "caller_id" to peer, "caller_name" to name), force = true)
    }
}

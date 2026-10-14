package com.encore.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Push {
    /** Sends this phone's FCM token to the server (call after login and on every app start). */
    fun register() {
        if (Session.token == null) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { send(it) }
    }

    fun send(fcmToken: String) {
        runIo({
            Api.call("POST", "/v1/push/register", JSONObject().put("token", fcmToken).put("platform", "android"))
        }) { }
    }

    /** Runs on a background thread, before logout, so this phone stops getting the old account's pushes. */
    fun unregister(sessionToken: String) {
        try {
            val t = Tasks.await(FirebaseMessaging.getInstance().token, 8, TimeUnit.SECONDS)
            Api.call("POST", "/v1/push/unregister", JSONObject().put("token", t), token = sessionToken)
        } catch (e: Exception) {
        }
    }
}

class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Session.load(applicationContext)
        if (Session.token != null) Push.send(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        when (d["type"]) {
            "message" -> {
                // pull the new message into the local database right away, even if the app is closed
                Sync.kick()
                showMessage(d["c"] ?: return, d["title"] ?: "Encore", d["body"] ?: "New message")
            }
            "call" -> CallNotification.show(applicationContext, d)
            "missed_call" -> {
                CallNotification.cancel(applicationContext)
                showMessage(d["c"] ?: return, "Missed call", "You missed a call")
            }
        }
    }

    private fun showMessage(cid: String, title: String, body: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_cid", cid)
        }
        val pi = PendingIntent.getActivity(
            this, cid.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "messages")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(cid.hashCode(), n)
    }
}

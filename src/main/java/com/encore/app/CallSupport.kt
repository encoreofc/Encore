package com.encore.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat

/** Decline button on the call notification: rejects the call without opening the app. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != CallNotification.ACTION_DECLINE) return
        val id = intent.getStringExtra(CallNotification.EXTRA_CALL) ?: return
        CallNotification.cancel(ctx)
        if (CallManager.activeCallId == id) {
            CallManager.hangup() // the app already knows this call
            return
        }
        val pending = goAsync()
        Thread {
            try {
                Api.call("POST", "/v1/calls/$id/reject")
            } catch (e: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}

enum class CallGap { Notifications, Channel, FullScreen }

/** What still stops calls from popping up on screen like a phone call (things the app can check). */
object CallSetup {
    fun gaps(ctx: Context): List<CallGap> {
        val out = ArrayList<CallGap>()
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            out.add(CallGap.Notifications)
            return out
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = nm.getNotificationChannel(CallNotification.CHANNEL)
            if (ch != null && ch.importance < NotificationManager.IMPORTANCE_HIGH) out.add(CallGap.Channel)
        }
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) out.add(CallGap.FullScreen)
        return out
    }

    fun settingsIntent(ctx: Context, gap: CallGap): Intent {
        val pkg = ctx.packageName
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        val i = when (gap) {
            CallGap.Notifications ->
                if (Build.VERSION.SDK_INT >= 26) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                } else details
            CallGap.Channel ->
                if (Build.VERSION.SDK_INT >= 26) {
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, CallNotification.CHANNEL)
                } else details
            CallGap.FullScreen ->
                if (Build.VERSION.SDK_INT >= 34) {
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg"))
                } else details
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Drop this once into the main screen: asks for the first missing call setting, then stays out of the way. */
@Composable
fun CallSetupPrompt() {
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf(true) }
    if (!open) return
    val gap = remember { CallSetup.gaps(ctx).firstOrNull() } ?: return
    val title = when (gap) {
        CallGap.Notifications -> "Turn on notifications"
        CallGap.Channel -> "Let calls pop up on screen"
        CallGap.FullScreen -> "Show calls over the lock screen"
    }
    val body = when (gap) {
        CallGap.Notifications -> "Encore needs notifications to ring when someone calls you."
        CallGap.Channel -> "Incoming calls are set to silent or hidden. Set \"Incoming calls\" to pop on screen so they ring with Accept and Decline."
        CallGap.FullScreen -> "Allow full-screen notifications so an incoming call opens on a locked or sleeping phone, like a normal call."
    }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = {
                open = false
                try {
                    ctx.startActivity(CallSetup.settingsIntent(ctx, gap))
                } catch (e: Exception) {
                }
            }) { Text("Open settings") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Not now") } }
    )
}

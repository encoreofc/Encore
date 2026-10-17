package com.encore.app

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** One-time setup nudges that decide whether incoming calls can take over a locked screen. */
object CallSetup {
    private const val PREFS = "encore_call_setup"
    private const val ASKED_AT = "fsi_asked_at"
    private const val GAP_MS = 3L * 24 * 60 * 60 * 1000

    fun check(ctx: Context) {
        if (Build.VERSION.SDK_INT < 34) return // earlier Android allows full-screen calls by default
        val act = findActivity(ctx) ?: return
        if (act.isFinishing || act.isDestroyed) return
        val nm = act.getSystemService(NotificationManager::class.java) ?: return
        // wait until the notification permission question is settled, so two dialogs never stack
        if (!nm.areNotificationsEnabled()) return
        if (nm.canUseFullScreenIntent()) return

        val prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(ASKED_AT, 0L) < GAP_MS) return
        prefs.edit().putLong(ASKED_AT, now).apply()

        AlertDialog.Builder(act)
            .setTitle("Show calls on the lock screen")
            .setMessage("Allow Encore to open incoming calls over the lock screen, so you can answer without unlocking first and never miss one.")
            .setPositiveButton("Allow") { _, _ -> openSettings(act) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun openSettings(act: Activity) {
        val pkg = Uri.parse("package:${act.packageName}")
        try {
            act.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
        } catch (e: Exception) {
            runCatching { act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }
        }
    }

    private fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

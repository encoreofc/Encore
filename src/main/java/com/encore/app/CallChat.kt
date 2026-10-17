package com.encore.app

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.max

/* ------------------------------------------------------------------------------------------
 * In-call sharing: text, photos and videos that appear on the call screen while people talk.
 *
 * This file owns the on-device state and the UI-facing API. It does NOT own the network:
 * plug your existing realtime / upload layer in through [CallChatTransport] (see bottom of the
 * notes in CallScreen.kt for the 4 places to wire).
 * ---------------------------------------------------------------------------------------- */

enum class CallItemKind { Text, Photo, Video }

enum class CallItemState {
    /** Outgoing: uploading. Incoming: downloading the full file (the preview is already visible). */
    Sending,
    Sent,
    Failed
}

class CallItem(
    val id: String,
    val mine: Boolean,
    val kind: CallItemKind,
    val text: String = "",
    /** Small thumbnail. Always available first, so the card shows instantly while the full file moves. */
    val preview: Bitmap? = null
) {
    var state by mutableStateOf(CallItemState.Sending)
    /** 0..1. Leave at 0 when the transport cannot report progress; the card shows a spinner then. */
    var progress by mutableStateOf(0f)
    /** Full-quality file on this device (local pick for outgoing, downloaded file for incoming). */
    var media by mutableStateOf<Uri?>(null)
    internal var dismissed by mutableStateOf(false)

    /** How long the card stays on the call screen before it fades away. */
    internal val holdMs: Long
        get() = if (kind == CallItemKind.Text) (4_000L + text.length * 70L).coerceAtMost(12_000L) else 20_000L

    fun markSent(file: Uri? = null) {
        if (file != null) media = file
        progress = 1f
        state = CallItemState.Sent
    }

    fun markFailed() {
        state = CallItemState.Failed
    }

    /** Small JPEG of [preview] for sending ahead of the full media. Roughly 20-40 KB at quality 70. */
    fun previewJpeg(quality: Int = 70): ByteArray? {
        val p = preview ?: return null
        val out = ByteArrayOutputStream()
        p.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}

/** Implement this on top of your existing realtime + upload layer. */
interface CallChatTransport {
    /** Send [item].text. Call item.markSent() on success or item.markFailed() on error. */
    fun sendText(item: CallItem)

    /**
     * Send the preview ([CallItem.previewJpeg]) right away, then upload [source] in the background.
     * Report item.progress while it moves, then item.markSent() or item.markFailed().
     */
    fun sendMedia(item: CallItem, source: Uri)
}

object CallChat {
    /** Everything shared during the current call, oldest first. Main thread only. */
    val items = mutableStateListOf<CallItem>()

    private var transport: CallChatTransport? = null
    private const val MaxItems = 60

    fun attach(t: CallChatTransport?) {
        transport = t
    }

    fun clear() {
        items.clear()
    }

    private fun newId() = UUID.randomUUID().toString()

    private fun push(item: CallItem) {
        items.add(item)
        while (items.size > MaxItems) items.removeAt(0)
    }

    // ---- outgoing -------------------------------------------------------------------------

    fun sendText(raw: String) {
        val text = raw.trim().take(500)
        if (text.isEmpty()) return
        val item = CallItem(newId(), mine = true, kind = CallItemKind.Text, text = text)
        push(item)
        dispatch(item)
    }

    /** Picks up a gallery result: builds the preview off the main thread, shows the card, starts the upload. */
    suspend fun sendMedia(ctx: Context, uri: Uri) {
        val app = ctx.applicationContext
        val isVideo = app.contentResolver.getType(uri)?.startsWith("video/") == true
        val preview = withContext(Dispatchers.IO) { makePreview(app, uri, isVideo) }
        val item = CallItem(
            id = newId(),
            mine = true,
            kind = if (isVideo) CallItemKind.Video else CallItemKind.Photo,
            preview = preview
        )
        item.media = uri
        push(item)
        dispatch(item)
    }

    fun retry(item: CallItem) {
        if (!item.mine || item.state != CallItemState.Failed) return
        item.progress = 0f
        item.state = CallItemState.Sending
        dispatch(item)
    }

    private fun dispatch(item: CallItem) {
        val t = transport
        if (t == null) {
            item.markFailed()
            return
        }
        if (item.kind == CallItemKind.Text) {
            t.sendText(item)
        } else {
            val src = item.media
            if (src == null) item.markFailed() else t.sendMedia(item, src)
        }
    }

    // ---- incoming (call from your realtime handler, on the main thread) ---------------------

    fun receiveText(ctx: Context, id: String, text: String) {
        if (items.any { it.id == id }) return
        val item = CallItem(id, mine = false, kind = CallItemKind.Text, text = text)
        item.state = CallItemState.Sent
        push(item)
        CallChatNotifier.show(ctx, CallManager.peerName, item)
    }

    /**
     * Call as soon as the preview arrives. Keep the returned item: update item.progress while the full
     * file downloads, then item.markSent(localFileUri) or item.markFailed().
     */
    fun receiveMedia(ctx: Context, id: String, isVideo: Boolean, preview: Bitmap?): CallItem {
        items.firstOrNull { it.id == id }?.let { return it }
        val item = CallItem(
            id = id,
            mine = false,
            kind = if (isVideo) CallItemKind.Video else CallItemKind.Photo,
            preview = preview
        )
        push(item)
        CallChatNotifier.show(ctx, CallManager.peerName, item)
        return item
    }

    // ---- image helpers --------------------------------------------------------------------

    /** Decodes [uri] so its longest side is at least [maxSide] px (down-sampled), upright. */
    suspend fun loadBitmap(ctx: Context, uri: Uri, maxSide: Int): Bitmap? =
        withContext(Dispatchers.IO) { decodeBlocking(ctx.applicationContext.contentResolver, uri, maxSide) }

    private fun makePreview(ctx: Context, uri: Uri, isVideo: Boolean): Bitmap? =
        if (!isVideo) {
            decodeBlocking(ctx.contentResolver, uri, PreviewSide)
        } else {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame == null) {
                    null
                } else {
                    val scale = PreviewSide.toFloat() / max(frame.width, frame.height)
                    if (scale >= 1f) frame
                    else Bitmap.createScaledBitmap(
                        frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true
                    )
                }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
        }

    private const val PreviewSide = 480

    private fun decodeBlocking(cr: ContentResolver, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return@runCatching null

        val deg = readRotation(cr, uri)
        if (deg == 0) {
            raw
        } else {
            val m = Matrix().apply { postRotate(deg.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        }
    }.getOrNull()

    private fun readRotation(cr: ContentResolver, uri: Uri): Int {
        if (Build.VERSION.SDK_INT < 24) return 0
        return runCatching {
            cr.openInputStream(uri)?.use { s ->
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)
    }
}

/**
 * Heads-up banner for messages and media that arrive while Encore is not on screen
 * (the person is in another app, or the screen is locked). Does nothing while the app is in front,
 * because the call screen already shows the item.
 */
object CallChatNotifier {
    private const val Channel = "encore_call_chat"
    private const val NotificationId = 7102

    fun show(ctx: Context, peerName: String, item: CallItem) {
        val app = ctx.applicationContext
        if (item.mine || isForeground(app)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel(app)

        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tap = launch?.let {
            PendingIntent.getActivity(app, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val body = when (item.kind) {
            CallItemKind.Text -> item.text
            CallItemKind.Photo -> "Sent you a photo"
            CallItemKind.Video -> "Sent you a video"
        }

        val b = NotificationCompat.Builder(app, Channel)
            .setSmallIcon(app.applicationInfo.icon) // swap for your monochrome notification icon
            .setContentTitle(peerName)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(20_000)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (tap != null) b.setContentIntent(tap)

        val p = item.preview
        if (p != null) {
            b.setLargeIcon(p)
            if (item.kind == CallItemKind.Photo) {
                b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(p).bigLargeIcon(null as Bitmap?))
            }
        } else if (item.kind == CallItemKind.Text) {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        runCatching { NotificationManagerCompat.from(app).notify(NotificationId, b.build()) }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(Channel) != null) return
        nm.createNotificationChannel(
            NotificationChannel(Channel, "Messages during calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Texts, photos and videos sent to you while you are on a call"
                setShowBadge(false)
            }
        )
    }

    private fun isForeground(ctx: Context): Boolean {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return false
        val me = am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() } ?: return false
        return me.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}

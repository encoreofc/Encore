package com.encore.app

import android.app.DownloadManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Uploads {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private val imageOk = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif")

    class Picked(
        val uri: Uri,
        val kind: String,
        val name: String,
        val size: Long,
        val type: String,
        val w: Int,
        val h: Int
    )

    fun url(cid: String, mid: String) = "$API_BASE/v1/media/$cid/$mid"

    fun inspect(ctx: Context, uri: Uri, wanted: String): Picked {
        var name = "file"
        var size = -1L
        ctx.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size <= 0) throw ApiException("bad_file", "Can't read this file")
        val type = (ctx.contentResolver.getType(uri) ?: "application/octet-stream").lowercase()
        val kind = if (wanted == "image" && type !in imageOk) "file" else wanted
        val maxMb = if (kind == "image") 20L else 95L
        if (size > maxMb * 1024 * 1024) throw ApiException("too_large", "File is too large (max $maxMb MB)")
        var w = 0
        var h = 0
        if (kind == "image") {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            w = o.outWidth.coerceAtLeast(0)
            h = o.outHeight.coerceAtLeast(0)
        }
        return Picked(uri, kind, name, size, type, w, h)
    }

    private class StreamBody(
        val ctx: Context,
        val uri: Uri,
        val type: MediaType?,
        val length: Long,
        val onProgress: (Float) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType? = type
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Can't open file")
            input.use {
                val buf = ByteArray(32 * 1024)
                var sent = 0L
                var last = -1
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    sent += n
                    val pct = (sent * 100 / length).toInt()
                    if (pct - last >= 2 || pct == 100) {
                        last = pct
                        main.post { onProgress(pct / 100f) }
                    }
                }
            }
        }
    }

    fun upload(
        ctx: Context,
        cid: String,
        p: Picked,
        clientId: String,
        replyTo: String?,
        onProgress: (Float) -> Unit
    ): MsgItem {
        val q = StringBuilder("/v1/conversations/$cid/media?kind=${p.kind}&name=${Uri.encode(p.name)}&client_id=${Uri.encode(clientId)}")
        if (p.w > 0 && p.h > 0) q.append("&w=${p.w}&h=${p.h}")
        if (replyTo != null) q.append("&reply_to=${Uri.encode(replyTo)}")
        val body = StreamBody(ctx, p.uri, p.type.toMediaTypeOrNull(), p.size, onProgress)
        val req = Request.Builder()
            .url(API_BASE + q)
            .header("Authorization", "Bearer ${Session.token}")
            .post(body)
            .build()
        val item: MsgItem = try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string() ?: ""
                val j = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    JSONObject()
                }
                if (!r.isSuccessful) {
                    val err = j.optJSONObject("error")
                    throw ApiException(
                        err?.optString("code") ?: "http_${r.code}",
                        err?.optString("message") ?: "Upload failed (${r.code})"
                    )
                }
                parseItem(j.getJSONObject("message"))
            }
        } catch (e: java.io.IOException) {
            throw ApiException("network", "Upload failed. Check your connection.")
        }
        return item
    }

    fun download(ctx: Context, cid: String, mid: String, name: String) {
        try {
            val req = DownloadManager.Request(Uri.parse(url(cid, mid)))
                .addRequestHeader("Authorization", "Bearer ${Session.token}")
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(ctx, "Downloading to Downloads", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Can't download this file", Toast.LENGTH_SHORT).show()
        }
    }
}

fun sizeLabel(b: Long): String = when {
    b >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
    b >= 1024L -> (b / 1024).toString() + " KB"
    else -> "$b B"
}

@Composable
private fun PhotoIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.1f, w * 0.18f),
            size = Size(w * 0.8f, w * 0.64f),
            cornerRadius = CornerRadius(w * 0.1f),
            style = Stroke(width = w * 0.08f)
        )
        drawCircle(color, radius = w * 0.07f, center = Offset(w * 0.34f, w * 0.38f))
        val hill = Path().apply {
            moveTo(w * 0.16f, w * 0.76f)
            lineTo(w * 0.42f, w * 0.52f)
            lineTo(w * 0.58f, w * 0.66f)
            lineTo(w * 0.7f, w * 0.56f)
            lineTo(w * 0.84f, w * 0.76f)
            close()
        }
        drawPath(hill, color)
    }
}

@Composable
private fun FileIcon(color: Color, size: androidx.compose.ui.unit.Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val page = Path().apply {
            moveTo(w * 0.24f, w * 0.1f)
            lineTo(w * 0.58f, w * 0.1f)
            lineTo(w * 0.8f, w * 0.32f)
            lineTo(w * 0.8f, w * 0.9f)
            lineTo(w * 0.24f, w * 0.9f)
            close()
        }
        drawPath(page, color, style = Stroke(width = w * 0.08f))
        val fold = Path().apply {
            moveTo(w * 0.58f, w * 0.1f)
            lineTo(w * 0.58f, w * 0.32f)
            lineTo(w * 0.8f, w * 0.32f)
        }
        drawPath(fold, color, style = Stroke(width = w * 0.07f))
    }
}

@Composable
fun MediaBlock(cid: String, item: MsgItem, progress: Float?, onImage: () -> Unit, onFile: () -> Unit) {
    val m = item.media ?: return
    val ctx = LocalContext.current
    if (item.msg.kind == "image") {
        val ratio = if (m.w > 0 && m.h > 0) (m.h.toFloat() / m.w).coerceIn(0.6f, 1.5f) else 1f
        val data: Any = m.localUri?.let { Uri.parse(it) } ?: Uploads.url(cid, item.msg.id)
        val req = remember(data) {
            ImageRequest.Builder(ctx)
                .data(data)
                .apply { if (m.localUri == null) addHeader("Authorization", "Bearer ${Session.token}") }
                .crossfade(true)
                .build()
        }
        Box(
            modifier = Modifier
                .width(240.dp)
                .height((240f * ratio).dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = progress == null) { onImage() }
        ) {
            AsyncImage(
                model = req,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (progress != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000)),
                    contentAlignment = Alignment.Center
                ) {
                    if (progress > 0f) {
                        CircularProgressIndicator(progress = progress, color = Color.White, modifier = Modifier.size(36.dp))
                    } else {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                    }
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .widthIn(min = 200.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = progress == null) { onFile() }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(LocalContentColor.current.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                if (progress != null) {
                    if (progress > 0f) {
                        CircularProgressIndicator(progress = progress, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                } else {
                    FileIcon(LocalContentColor.current)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(m.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp))
                Text(sizeLabel(m.size), fontSize = 12.sp, color = LocalContentColor.current.copy(alpha = 0.65f))
            }
        }
    }
}

@Composable
fun ImageViewer(cid: String, item: MsgItem, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val url = Uploads.url(cid, item.msg.id)
    val req = remember(url) {
        ImageRequest.Builder(ctx)
            .data(url)
            .addHeader("Authorization", "Bearer ${Session.token}")
            .crossfade(true)
            .build()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { onClose() }
        ) {
            AsyncImage(
                model = req,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

@Composable
private fun AttachRow(title: String, sub: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            Text(sub, color = Ui.muted, fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(onPhoto: () -> Unit, onFile: () -> Unit, onSoon: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ui.bg) {
        AttachRow("Photo", "Send a picture from your gallery", onPhoto) { PhotoIcon(Ui.ink) }
        AttachRow("File", "Send any document or file", onFile) { FileIcon(Ui.ink) }
        AttachRow("Location", "Share where you are", { onSoon("Location") }) {
            Icon(Icons.Default.LocationOn, contentDescription = null, tint = Ui.ink)
        }
        AttachRow("Contact", "Share a contact", { onSoon("Contacts") }) {
            Icon(Icons.Default.Person, contentDescription = null, tint = Ui.ink)
        }
        Spacer(Modifier.height(24.dp))
    }
}

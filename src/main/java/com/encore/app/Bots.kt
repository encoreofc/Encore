package com.encore.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/* ------------------------------------------------------------------ */
/* models + api                                                        */
/* ------------------------------------------------------------------ */

data class BotButton(val id: String, val label: String)

data class Bot(
    val id: String,
    val handle: String,
    val name: String,
    val description: String?,
    val verified: Boolean,
    val buttons: List<BotButton>,
    val monthlyUsers: Int?,
    val added: Boolean
)

data class BotMsg(
    val id: String,
    val sender: String,
    val kind: String,
    val body: String?,
    val ref: String?,
    val createdAt: Long
)

data class Sticker(
    val id: String,
    val kind: String,
    val mime: String,
    val w: Int,
    val h: Int,
    val dur: Int?,
    val mine: Boolean,
    val isPublic: Boolean,
    val saved: Boolean
)

private fun JSONObject.sOrNull(key: String): String? = if (isNull(key)) null else getString(key)

object BotApi {
    private val http = OkHttpClient()

    private fun bot(o: JSONObject): Bot {
        val arr = o.optJSONArray("buttons") ?: JSONArray()
        val buttons = List(arr.length()) {
            val b = arr.getJSONObject(it)
            BotButton(b.getString("id"), b.getString("label"))
        }
        return Bot(
            o.getString("id"),
            o.getString("handle"),
            o.getString("name"),
            o.sOrNull("description"),
            o.optBoolean("verified"),
            buttons,
            if (o.isNull("monthly_users")) null else o.optInt("monthly_users"),
            o.optBoolean("added")
        )
    }

    private fun list(j: JSONObject): List<Bot> {
        val a = j.getJSONArray("bots")
        return List(a.length()) { bot(a.getJSONObject(it)) }
    }

    private fun sticker(o: JSONObject) = Sticker(
        o.getString("id"),
        o.getString("kind"),
        o.getString("mime"),
        o.optInt("w"),
        o.optInt("h"),
        if (o.isNull("dur")) null else o.optInt("dur"),
        o.optBoolean("mine"),
        o.optBoolean("public"),
        o.optBoolean("saved")
    )

    fun parseMsg(o: JSONObject) = BotMsg(
        o.getString("id"),
        o.getString("sender"),
        o.getString("kind"),
        o.sOrNull("body"),
        o.sOrNull("ref"),
        o.getLong("created_at")
    )

    fun search(q: String): List<Bot> = list(Api.call("GET", "/v1/bots?q=" + Uri.encode(q)))

    fun mine(): List<Bot> = list(Api.call("GET", "/v1/bots/mine"))

    fun add(id: String): Bot = bot(Api.call("PUT", "/v1/bots/$id/add").getJSONObject("bot"))

    fun remove(id: String) {
        Api.call("DELETE", "/v1/bots/$id/add")
    }

    fun messages(id: String): List<BotMsg> {
        val a = Api.call("GET", "/v1/bots/$id/messages?limit=100").getJSONArray("messages")
        return List(a.length()) { parseMsg(a.getJSONObject(it)) }
    }

    fun action(id: String, action: String) {
        Api.call("POST", "/v1/bots/$id/action", JSONObject().put("action", action))
    }

    /** Uploads the prepared file. Returns true when the bot made a sticker out of it. */
    fun input(id: String, bytes: ByteArray, type: String, w: Int, h: Int, dur: Int): Boolean {
        val q = "?w=$w&h=$h" + if (dur > 0) "&dur=$dur" else ""
        return Api.postBytes("/v1/bots/$id/input$q", bytes, type).optBoolean("created")
    }

    fun stickers(scope: String): List<Sticker> {
        val a = Api.call("GET", "/v1/stickers?scope=$scope").getJSONArray("stickers")
        return List(a.length()) { sticker(a.getJSONObject(it)) }
    }

    fun sticker(id: String): Sticker = sticker(Api.call("GET", "/v1/stickers/$id").getJSONObject("sticker"))

    fun share(id: String, on: Boolean) {
        Api.call("PUT", "/v1/stickers/$id/share", JSONObject().put("public", on))
    }

    fun unsave(id: String) {
        Api.call("DELETE", "/v1/stickers/$id/save")
    }

    fun deleteSticker(id: String) {
        Api.call("DELETE", "/v1/stickers/$id")
    }

    /** Video stickers are played from a local copy, so the player never needs range requests. */
    fun mediaFile(ctx: Context, id: String): String {
        val f = File(ctx.cacheDir, "stk_$id.bin")
        if (f.exists() && f.length() > 0) return f.path
        val req = Request.Builder()
            .url("$API_BASE/v1/stickers/$id/media")
            .header("Authorization", "Bearer ${Session.token}")
            .build()
        try {
            http.newCall(req).execute().use { r ->
                val body = r.body
                if (!r.isSuccessful || body == null) throw ApiException("http_${r.code}", "Couldn't load this sticker")
                f.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
        } catch (e: java.io.IOException) {
            f.delete()
            throw ApiException("network", "Can't reach the server. Check your connection.")
        }
        return f.path
    }
}

/* ------------------------------------------------------------------ */
/* picking + preparing files                                           */
/* ------------------------------------------------------------------ */

private class Prepared(val bytes: ByteArray, val type: String, val w: Int, val h: Int, val dur: Int)

private object StickerPrep {
    private const val MEGA = 1024 * 1024

    private fun readLimited(ctx: Context, uri: Uri, max: Int, tooBig: String): ByteArray {
        val out = ByteArrayOutputStream()
        val input = ctx.contentResolver.openInputStream(uri)
            ?: throw ApiException("bad_file", "Couldn't open this file.")
        input.use { s ->
            val buf = ByteArray(16384)
            var total = 0
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw ApiException("too_large", tooBig)
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun bounds(ctx: Context, uri: Uri): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        return o.outWidth to o.outHeight
    }

    @Suppress("DEPRECATION")
    private fun encode(bmp: Bitmap, lossless: Boolean): ByteArray {
        val bos = ByteArrayOutputStream()
        if (Build.VERSION.SDK_INT >= 30) {
            val fmt = if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
            bmp.compress(fmt, if (lossless) 100 else 85, bos)
        } else {
            bmp.compress(Bitmap.CompressFormat.WEBP, 90, bos)
        }
        return bos.toByteArray()
    }

    /** Any photo becomes a 512x512 WebP: the whole picture is kept and centered on a transparent square. */
    fun static(ctx: Context, uri: Uri): Prepared {
        val cr = ctx.contentResolver
        val (bw, bh) = bounds(ctx, uri)
        if (bw <= 0 || bh <= 0) throw ApiException("bad_image", "Couldn't read this image. Try another one.")
        var sample = 1
        while (bw / (sample * 2) >= 1024 && bh / (sample * 2) >= 1024) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw ApiException("bad_image", "Couldn't read this image. Try another one.")

        val rotation = try {
            cr.openInputStream(uri)?.use { s ->
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
        val upright = if (rotation != 0f) {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rotation) }, true)
        } else src

        val side = 512
        val scale = minOf(side.toFloat() / upright.width, side.toFloat() / upright.height)
        val dw = upright.width * scale
        val dh = upright.height * scale
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(
            upright,
            null,
            RectF((side - dw) / 2f, (side - dh) / 2f, (side + dw) / 2f, (side + dh) / 2f),
            paint
        )

        var bytes = encode(out, true)
        if (bytes.size > 2 * MEGA - 1024) bytes = encode(out, false)
        return Prepared(bytes, "image/webp", side, side, 0)
    }

    fun live(ctx: Context, uri: Uri): Prepared {
        val type = ctx.contentResolver.getType(uri)?.lowercase() ?: ""
        if (type != "image/gif" && type != "image/webp") {
            throw ApiException("bad_type", "Pick a GIF or an animated WebP image.")
        }
        val bytes = readLimited(ctx, uri, 3 * MEGA, "That file is over 3 MB. Pick a smaller one.")
        val (w, h) = bounds(ctx, uri)
        return Prepared(bytes, type, maxOf(w, 0), maxOf(h, 0), 0)
    }

    fun video(ctx: Context, uri: Uri): Prepared {
        val type = ctx.contentResolver.getType(uri)?.lowercase() ?: ""
        if (type != "video/mp4" && type != "video/webm") {
            throw ApiException("bad_type", "Pick an MP4 or WebM video.")
        }
        var dur = 0
        var w = 0
        var h = 0
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
            w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            throw ApiException("bad_video", "Couldn't read this video. Try another one.")
        } finally {
            r.release()
        }
        if (dur <= 0) throw ApiException("bad_video", "Couldn't read this video. Try another one.")
        if (dur > 3000) throw ApiException("too_long", "Video stickers can be up to 3 seconds. Trim it and try again.")
        val bytes = readLimited(ctx, uri, 3 * MEGA, "That video is over 3 MB. Pick a shorter or smaller one.")
        return Prepared(bytes, type, w, h, dur)
    }
}

/* ------------------------------------------------------------------ */
/* sticker image / video                                               */
/* ------------------------------------------------------------------ */

private object StickerImages {
    private var loader: ImageLoader? = null

    fun get(ctx: Context): ImageLoader = loader ?: ImageLoader.Builder(ctx.applicationContext)
        .components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .crossfade(true)
        .build()
        .also { loader = it }

    fun url(id: String) = "$API_BASE/v1/stickers/$id/media?token=${Session.token}"
}

@Composable
internal fun StickerView(id: String, kind: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    if (kind == "video") {
        var path by remember(id) { mutableStateOf<String?>(null) }
        LaunchedEffect(id) {
            runIo({ BotApi.mediaFile(ctx, id) }) { r -> r.onSuccess { path = it } }
        }
        val p = path
        if (p != null) {
            AndroidView(
                factory = { c ->
                    VideoView(c).apply {
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            mp.setVolume(0f, 0f)
                            mp.start()
                        }
                        setVideoPath(p)
                    }
                },
                modifier = modifier
            )
        } else {
            Box(modifier)
        }
    } else {
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(StickerImages.url(id)).build(),
            imageLoader = StickerImages.get(ctx),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier
        )
    }
}

private fun kindName(kind: String) = when (kind) {
    "live" -> "Live"
    "video" -> "Video"
    else -> "Static"
}

/* ------------------------------------------------------------------ */
/* small shared pieces                                                 */
/* ------------------------------------------------------------------ */

private fun actionSub(id: String): String? = when (id) {
    "new_static" -> "From a photo"
    "new_live" -> "From a GIF"
    "new_video" -> "Up to 3 seconds"
    "my" -> "Your collection"
    "help" -> "Quick guide"
    else -> null
}

@Composable
private fun ActionGlyph(id: String, tint: Color) {
    if (id == "help") {
        Text("?", color = tint, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        return
    }
    Box(
        Modifier
            .size(22.dp)
            .drawBehind {
                val s = size.minDimension
                val stroke = Stroke(width = s * 0.09f)
                when (id) {
                    "new_static" -> drawRoundRect(
                        tint,
                        topLeft = Offset(s * 0.1f, s * 0.1f),
                        size = Size(s * 0.8f, s * 0.8f),
                        cornerRadius = CornerRadius(s * 0.22f),
                        style = stroke
                    )
                    "new_live" -> {
                        drawCircle(tint, radius = s * 0.38f, style = stroke)
                        drawCircle(tint, radius = s * 0.14f)
                    }
                    "new_video" -> {
                        drawRoundRect(
                            tint,
                            topLeft = Offset(s * 0.08f, s * 0.08f),
                            size = Size(s * 0.84f, s * 0.84f),
                            cornerRadius = CornerRadius(s * 0.26f),
                            style = stroke
                        )
                        val p = Path().apply {
                            moveTo(s * 0.42f, s * 0.32f)
                            lineTo(s * 0.70f, s * 0.5f)
                            lineTo(s * 0.42f, s * 0.68f)
                            close()
                        }
                        drawPath(p, tint)
                    }
                    else -> {
                        val cells = listOf(0.12f to 0.12f, 0.56f to 0.12f, 0.12f to 0.56f, 0.56f to 0.56f)
                        cells.forEach { (x, y) ->
                            drawRoundRect(
                                tint,
                                topLeft = Offset(s * x, s * y),
                                size = Size(s * 0.32f, s * 0.32f),
                                cornerRadius = CornerRadius(s * 0.09f)
                            )
                        }
                    }
                }
            }
    )
}

@Composable
private fun VerifiedMark() {
    Spacer(Modifier.width(5.dp))
    Text("\u2B50", fontSize = 13.sp)
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 12.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.SemiBold,
        color = Ui.muted,
        modifier = Modifier.padding(start = 22.dp, top = 18.dp, bottom = 6.dp)
    )
}

/* ------------------------------------------------------------------ */
/* bot list                                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun BotCard(b: Bot, busy: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Ui.soft)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(b.id, b.name, 56.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    b.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (b.verified) VerifiedMark()
            }
            val users = b.monthlyUsers?.let { " \u00B7 $it monthly users" } ?: ""
            Text("@" + b.handle + users, fontSize = 12.sp, color = Ui.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!b.description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(b.description, fontSize = 13.sp, color = Ui.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (busy) "..." else if (b.added) "Open" else "Add",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (b.added) Ui.ink else Ui.onInk,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (b.added) Ui.bg else Ui.ink)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun BotsScreen(onBack: () -> Unit, onOpen: (Bot) -> Unit) {
    var q by remember { mutableStateOf("") }
    var mine by remember { mutableStateOf<List<Bot>>(emptyList()) }
    var found by remember { mutableStateOf<List<Bot>?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runIo({ BotApi.mine() }) { r -> r.onSuccess { mine = it } }
    }

    LaunchedEffect(q) {
        val t = q.trim()
        if (t.isNotEmpty()) delay(350)
        runIo({ BotApi.search(t) }) { r ->
            if (q.trim() == t) {
                r.onSuccess {
                    found = it
                    problem = null
                }.onFailure { problem = it.message }
            }
        }
    }

    fun openOrAdd(b: Bot) {
        if (b.added) {
            onOpen(b)
            return
        }
        busyId = b.id
        runIo({ BotApi.add(b.id) }) { r ->
            busyId = null
            r.onSuccess { onOpen(it) }.onFailure { problem = it.message }
        }
    }

    val searching = q.trim().isNotEmpty()
    val shown = found ?: emptyList()
    val discover = if (searching) shown else shown.filter { !it.added }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ui.ink)
            }
            Text("Bots", fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        }

        TextField(
            value = q,
            onValueChange = { q = it },
            placeholder = { Text("Search bots", color = Ui.muted) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            trailingIcon = {
                if (q.isNotEmpty()) {
                    IconButton(onClick = { q = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = Ui.muted)
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Ui.soft,
                unfocusedContainerColor = Ui.soft,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        problem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
        }

        if (searching && found != null && discover.isEmpty()) {
            Text("No bots found", color = Ui.muted, modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (!searching && mine.isNotEmpty()) {
                item { SectionLabel("My bots") }
                items(mine, key = { "m" + it.id }) { b ->
                    BotCard(b, busyId == b.id) { openOrAdd(b) }
                }
            }
            if (discover.isNotEmpty()) {
                item { SectionLabel(if (searching) "Results" else "Discover") }
                items(discover, key = { "d" + it.id }) { b ->
                    BotCard(b, busyId == b.id) { openOrAdd(b) }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* chat pieces                                                         */
/* ------------------------------------------------------------------ */

@Composable
private fun BotIntro(bot: Bot) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Avatar(bot.id, bot.name, 76.dp)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(bot.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (bot.verified) VerifiedMark()
        }
        Text("@" + bot.handle, fontSize = 13.sp, color = Ui.muted)
        if (!bot.description.isNullOrBlank()) {
            Text(
                bot.description,
                fontSize = 14.sp,
                color = Ui.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 36.dp, vertical = 8.dp)
            )
        }
    }
}

private val BotShape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
private val UserShape = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomStart = 20.dp, bottomEnd = 20.dp)

@Composable
private fun BotMessageRow(m: BotMsg, time: String?, onSticker: (String) -> Unit, onLibrary: () -> Unit) {
    val mine = m.sender == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
    ) {
        when {
            m.kind == "sticker" && m.ref != null -> {
                Column(
                    modifier = Modifier
                        .widthIn(max = 260.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Ui.soft)
                        .clickable { onSticker(m.ref) }
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(184.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Ui.bg)
                            .padding(10.dp)
                    ) {
                        StickerView(m.ref, "static", Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(m.body ?: "Your sticker is ready.", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Tap to share or delete", fontSize = 12.sp, color = Ui.muted)
                }
            }
            m.kind == "sticker_list" -> {
                val n = try { JSONArray(m.ref ?: "[]").length() } catch (e: Exception) { 0 }
                Column(
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .clip(BotShape)
                        .background(Ui.soft)
                        .clickable { onLibrary() }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(m.body ?: "Your stickers", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("$n stickers \u00B7 Open collection", fontSize = 13.sp, color = Ui.muted)
                }
            }
            mine -> {
                Row(
                    modifier = Modifier
                        .clip(UserShape)
                        .background(Ui.ink)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActionGlyph(if (m.body == "Video") "new_video" else "new_static", Ui.onInk)
                    Spacer(Modifier.width(8.dp))
                    Text((m.body ?: "") + " sent", color = Ui.onInk, fontSize = 15.sp)
                }
            }
            else -> {
                Text(
                    m.body ?: "",
                    color = Ui.ink,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(BotShape)
                        .background(Ui.soft)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
        if (time != null) {
            Text(
                time,
                fontSize = 11.sp,
                color = Ui.muted,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 3.dp)
            )
        }
    }
}

@Composable
private fun ActionTile(b: BotButton, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Ui.soft)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Ui.bg),
            contentAlignment = Alignment.Center
        ) {
            ActionGlyph(b.id, Ui.ink)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                b.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) Ui.ink else Ui.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            actionSub(b.id)?.let {
                Text(it, fontSize = 12.sp, color = Ui.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun pendingText(kind: String) = when (kind) {
    "live" -> "Choose a GIF or animated WebP"
    "video" -> "Choose a video up to 3 seconds"
    else -> "Choose a photo"
}

/* ------------------------------------------------------------------ */
/* sticker library + detail                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun StickerLibrary(version: Int, onBack: () -> Unit, onOpen: (Sticker) -> Unit) {
    var tab by remember { mutableStateOf("mine") }
    var list by remember(tab, version) { mutableStateOf<List<Sticker>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tab, version) {
        val t = tab
        runIo({ BotApi.stickers(t) }) { r ->
            r.onSuccess { if (t == tab) list = it }.onFailure { problem = it.message }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        ScreenBar("Stickers", onBack)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("mine" to "Mine", "saved" to "Saved").forEach { (k, label) ->
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (tab == k) Ui.onInk else Ui.ink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (tab == k) Ui.ink else Ui.soft)
                        .clickable { tab = k }
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }
        }
        problem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        val l = list
        if (l == null) {
            Text("Loading\u2026", color = Ui.muted, modifier = Modifier.padding(20.dp))
        } else if (l.isEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp)) {
                Text(
                    if (tab == "mine") "No stickers yet" else "Nothing saved yet",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (tab == "mine") "Go back and pick a sticker type to make your first one." else "Stickers you save from others show up here.",
                    color = Ui.muted,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                gridItems(l, key = { it.id }) { s ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Ui.soft)
                            .clickable { onOpen(s) }
                            .padding(10.dp)
                    ) {
                        StickerView(s.id, s.kind, Modifier.fillMaxSize())
                        if (s.isPublic) {
                            Text(
                                "Public",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = Ui.onInk,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Ui.ink)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StickerDetail(id: String, onClose: () -> Unit, onChanged: () -> Unit) {
    var s by remember(id) { mutableStateOf<Sticker?>(null) }
    var problem by remember(id) { mutableStateOf<String?>(null) }
    var confirm by remember(id) { mutableStateOf(false) }
    var busy by remember(id) { mutableStateOf(false) }

    LaunchedEffect(id) {
        runIo({ BotApi.sticker(id) }) { r ->
            r.onSuccess { s = it }.onFailure { problem = it.message }
        }
    }

    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Ui.bg)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val cur = s
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Ui.soft)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (cur != null) StickerView(cur.id, cur.kind, Modifier.fillMaxSize())
                else CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Ui.muted)
            }

            if (cur != null) {
                Spacer(Modifier.height(14.dp))
                val extra = if (cur.kind == "video" && cur.dur != null) " \u00B7 " + String.format("%.1f", cur.dur / 1000f) + "s" else ""
                Text(kindName(cur.kind) + " sticker" + extra, fontSize = 15.sp, fontWeight = FontWeight.Medium)

                Spacer(Modifier.height(14.dp))
                if (cur.mine) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .border(1.dp, Ui.line, RoundedCornerShape(18.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Public", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (cur.isPublic) "Anyone with it can view and save" else "Only you can see it",
                                fontSize = 12.sp,
                                color = Ui.muted
                            )
                        }
                        Switch(
                            checked = cur.isPublic,
                            enabled = !busy,
                            onCheckedChange = { on ->
                                busy = true
                                problem = null
                                runIo({ BotApi.share(cur.id, on) }) { r ->
                                    busy = false
                                    r.onSuccess {
                                        s = cur.copy(isPublic = on)
                                        onChanged()
                                    }.onFailure { problem = it.message }
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = Ui.ink,
                                checkedThumbColor = Ui.onInk,
                                uncheckedTrackColor = Ui.soft,
                                uncheckedThumbColor = Ui.muted,
                                uncheckedBorderColor = Ui.line
                            )
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (confirm) "Tap again to delete for good" else "Delete",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = !busy) {
                                if (!confirm) {
                                    confirm = true
                                } else {
                                    busy = true
                                    runIo({ BotApi.deleteSticker(cur.id) }) { r ->
                                        busy = false
                                        r.onSuccess {
                                            onChanged()
                                            onClose()
                                        }.onFailure { problem = it.message }
                                    }
                                }
                            }
                            .padding(vertical = 12.dp)
                    )
                } else {
                    Text(
                        "Remove from saved",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = !busy) {
                                busy = true
                                runIo({ BotApi.unsave(cur.id) }) { r ->
                                    busy = false
                                    r.onSuccess {
                                        onChanged()
                                        onClose()
                                    }.onFailure { problem = it.message }
                                }
                            }
                            .padding(vertical = 12.dp)
                    )
                }
            }

            problem?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Close",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Ui.onInk,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Ui.ink)
                    .clickable(onClick = onClose)
                    .padding(vertical = 13.dp)
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* bot chat                                                            */
/* ------------------------------------------------------------------ */

@Composable
fun BotChatScreen(bot: Bot, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var messages by remember { mutableStateOf<List<BotMsg>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    var trayOpen by remember { mutableStateOf(true) }
    var library by remember { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var libVersion by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(bot.id) {
        runIo({ BotApi.messages(bot.id) }) { r ->
            r.onSuccess { messages = it }.onFailure { problem = it.message }
        }
    }

    val rows = 1 + messages.size + (if (busy || uploading) 1 else 0)
    LaunchedEffect(rows) {
        listState.animateScrollToItem(rows - 1)
    }

    OnRealtime { e ->
        if (e.optString("type") == "bot_message") {
            val m = e.optJSONObject("message")
            if (m != null && m.optString("bot_id") == bot.id) {
                val bm = BotApi.parseMsg(m)
                if (messages.none { it.id == bm.id }) messages = messages + bm
            }
        }
    }

    BackHandler(enabled = library) { library = false }

    fun upload(uri: Uri) {
        val kind = pending ?: return
        uploading = true
        problem = null
        runIo({
            val p = when (kind) {
                "static" -> StickerPrep.static(ctx, uri)
                "live" -> StickerPrep.live(ctx, uri)
                else -> StickerPrep.video(ctx, uri)
            }
            val created = BotApi.input(bot.id, p.bytes, p.type, p.w, p.h, p.dur)
            created to BotApi.messages(bot.id)
        }) { r ->
            uploading = false
            r.onSuccess { (created, list) ->
                messages = list
                if (created) {
                    pending = null
                    libVersion++
                }
            }.onFailure { problem = it.message }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) upload(uri)
    }

    fun pick(kind: String) {
        picker.launch(if (kind == "video") "video/*" else "image/*")
    }

    fun press(b: BotButton) {
        if (busy || uploading) return
        if (b.id == "my") {
            library = true
            return
        }
        busy = true
        problem = null
        runIo({
            BotApi.action(bot.id, b.id)
            BotApi.messages(bot.id)
        }) { r ->
            busy = false
            r.onSuccess {
                messages = it
                val k = when (b.id) {
                    "new_static" -> "static"
                    "new_live" -> "live"
                    "new_video" -> "video"
                    else -> null
                }
                pending = k
                if (k != null) pick(k)
            }.onFailure { problem = it.message }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Ui.bg)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ui.ink)
                }
                Avatar(bot.id, bot.name, 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            bot.name,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (bot.verified) VerifiedMark()
                    }
                    val sub = if (bot.verified) "Official bot" else "Bot"
                    val users = bot.monthlyUsers?.let { " \u00B7 $it monthly users" } ?: ""
                    Text(sub + users, fontSize = 12.sp, color = Ui.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Ui.ink)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Remove bot") },
                            onClick = {
                                menu = false
                                runIo({ BotApi.remove(bot.id) }) { r ->
                                    r.onSuccess { onBack() }.onFailure { problem = it.message }
                                }
                            }
                        )
                    }
                }
            }
            HorizontalDivider(color = Ui.line)

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item(key = "intro") { BotIntro(bot) }
                itemsIndexed(messages, key = { _, m -> m.id }) { i, m ->
                    val last = i == messages.lastIndex || messages[i + 1].sender != m.sender
                    BotMessageRow(
                        m,
                        if (last) timeLabel(m.createdAt) else null,
                        onSticker = { detailId = it },
                        onLibrary = { library = true }
                    )
                }
                if (busy || uploading) {
                    item(key = "working") {
                        Row(
                            modifier = Modifier
                                .clip(BotShape)
                                .background(Ui.soft)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Ui.muted)
                            Spacer(Modifier.width(10.dp))
                            Text(if (uploading) "Making your sticker\u2026" else "One moment\u2026", fontSize = 14.sp, color = Ui.muted)
                        }
                    }
                }
            }

            problem?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            HorizontalDivider(color = Ui.line)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                val pk = pending
                if (pk != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Ui.ink)
                            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Waiting for your file",
                            color = Ui.onInk,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            pendingText(pk),
                            color = Ui.ink,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = 200.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Ui.bg)
                                .clickable(enabled = !uploading) { pick(pk) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                        IconButton(onClick = { pending = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Ui.onInk)
                        }
                    }
                }

                Text(
                    if (trayOpen) "Hide actions" else "Show actions",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Ui.muted,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { trayOpen = !trayOpen }
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                )

                if (trayOpen) {
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        bot.buttons.chunked(2).forEach { rowButtons ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowButtons.forEach { b ->
                                    ActionTile(b, !busy && !uploading, Modifier.weight(1f)) { press(b) }
                                }
                                if (rowButtons.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        if (library) {
            StickerLibrary(
                version = libVersion,
                onBack = { library = false },
                onOpen = { detailId = it.id }
            )
        }

        detailId?.let { id ->
            StickerDetail(
                id = id,
                onClose = { detailId = null },
                onChanged = { libVersion++ }
            )
        }
    }
}

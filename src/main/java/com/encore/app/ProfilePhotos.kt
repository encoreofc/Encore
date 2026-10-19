package com.encore.app

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Window
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.min

/** One profile photo. at = when it was set (ms), 0 = not known (set before history existed). */
class ProfilePhoto(val url: String, val at: Long, val video: Boolean = false)

private fun ppFetchPhotos(userId: String, currentUrl: String): List<ProfilePhoto> {
    val j = Api.call("GET", "/v1/users/$userId/photos")
    val cur = if (j.isNull("current")) null else j.optString("current")
    val a = j.getJSONArray("photos")
    return List(a.length()) {
        val o = a.getJSONObject(it)
        val id = o.getString("id")
        // the photo in use is already loaded in the profile, so it keeps the same address
        ProfilePhoto(
            if (id == cur) currentUrl else "$API_BASE/v1/avatar/p/$userId/$id",
            o.optLong("created_at"),
            // a profile video has "video" as its kind (its id starts with w)
            o.optString("kind") == "video" || id.startsWith("w")
        )
    }
}

/* ------------------------------------------------------------------ */
/* saving                                                              */
/* ------------------------------------------------------------------ */

object PhotoSaver {
    /** Downloads one photo and files it in Pictures/Encore or Download/Encore. Call off the main thread. */
    fun save(ctx: Context, url: String, baseName: String, toGallery: Boolean, video: Boolean = false) {
        if (video) {
            saveVideo(ctx, url, baseName, toGallery)
            return
        }
        val got = download(url)
        val type = got.second
        val ext = when (type) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            else -> "jpg"
        }
        try {
            write(ctx, got.first, "$baseName.$ext", type, toGallery)
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("save_failed", "Couldn't save the photo. Check storage access and try again.")
        }
    }

    /** Videos are streamed straight to storage (never held in memory): Movies/Encore or Download/Encore. */
    private fun saveVideo(ctx: Context, url: String, baseName: String, toGallery: Boolean) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("Authorization", "Bearer ${Session.token}")
            try {
                if (conn.responseCode !in 200..299) {
                    throw ApiException("http_${conn.responseCode}", "Couldn't download the video")
                }
                val raw = (conn.contentType ?: "video/mp4").substringBefore(';').trim().lowercase()
                val type = if (raw.startsWith("video/")) raw else "video/mp4"
                val ext = when (type) {
                    "video/webm" -> "webm"
                    "video/3gpp" -> "3gp"
                    "video/quicktime" -> "mov"
                    else -> "mp4"
                }
                conn.inputStream.use { input ->
                    try {
                        writeStream(ctx, input, "$baseName.$ext", type, toGallery)
                    } catch (e: ApiException) {
                        throw e
                    } catch (e: Exception) {
                        throw ApiException("save_failed", "Couldn't save the video. Check storage access and try again.")
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: java.io.IOException) {
            throw ApiException("network", "Can't reach the server. Check your connection.")
        }
    }

    private fun writeStream(ctx: Context, input: java.io.InputStream, name: String, type: String, toGallery: Boolean) {
        val folder = if (toGallery) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_DOWNLOADS
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, type)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Encore")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (toGallery) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val uri = resolver.insert(collection, values)
                ?: throw ApiException("save_failed", "Couldn't save the video")
            try {
                val out = resolver.openOutputStream(uri) ?: throw ApiException("save_failed", "Couldn't save the video")
                out.use { input.copyTo(it) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(folder), "Encore")
            dir.mkdirs()
            val file = File(dir, name)
            file.outputStream().use { input.copyTo(it) }
            MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf(type), null)
        }
    }

    private fun download(url: String): Pair<ByteArray, String> {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Authorization", "Bearer ${Session.token}")
            try {
                if (conn.responseCode !in 200..299) {
                    throw ApiException("http_${conn.responseCode}", "Couldn't download the photo")
                }
                val type = (conn.contentType ?: "image/jpeg").substringBefore(';').trim().lowercase()
                return conn.inputStream.use { it.readBytes() } to type
            } finally {
                conn.disconnect()
            }
        } catch (e: java.io.IOException) {
            throw ApiException("network", "Can't reach the server. Check your connection.")
        }
    }

    private fun write(ctx: Context, bytes: ByteArray, name: String, type: String, toGallery: Boolean) {
        val folder = if (toGallery) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, type)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Encore")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (toGallery) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val uri = resolver.insert(collection, values)
                ?: throw ApiException("save_failed", "Couldn't save the photo")
            try {
                val out = resolver.openOutputStream(uri) ?: throw ApiException("save_failed", "Couldn't save the photo")
                out.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(folder), "Encore")
            dir.mkdirs()
            val file = File(dir, name)
            file.outputStream().use { it.write(bytes) }
            MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf(type), null)
        }
    }
}

/* ------------------------------------------------------------------ */
/* small drawings (no extra icon library needed)                       */
/* ------------------------------------------------------------------ */

@Composable
internal fun PpGalleryGlyph(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val sw = w * 0.08f
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.1f, w * 0.14f),
            size = Size(w * 0.8f, w * 0.72f),
            cornerRadius = CornerRadius(w * 0.12f),
            style = Stroke(width = sw)
        )
        val mountain = Path().apply {
            moveTo(w * 0.12f, w * 0.74f)
            lineTo(w * 0.36f, w * 0.54f)
            lineTo(w * 0.54f, w * 0.68f)
            lineTo(w * 0.68f, w * 0.58f)
            lineTo(w * 0.88f, w * 0.74f)
        }
        drawPath(mountain, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, radius = w * 0.07f, center = Offset(w * 0.34f, w * 0.36f))
    }
}

@Composable
internal fun PpDownloadGlyph(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val sw = w * 0.09f
        drawLine(color, Offset(w * 0.5f, w * 0.14f), Offset(w * 0.5f, w * 0.62f), strokeWidth = sw, cap = StrokeCap.Round)
        val head = Path().apply {
            moveTo(w * 0.3f, w * 0.44f)
            lineTo(w * 0.5f, w * 0.64f)
            lineTo(w * 0.7f, w * 0.44f)
        }
        drawPath(head, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val tray = Path().apply {
            moveTo(w * 0.18f, w * 0.64f)
            lineTo(w * 0.18f, w * 0.84f)
            lineTo(w * 0.82f, w * 0.84f)
            lineTo(w * 0.82f, w * 0.64f)
        }
        drawPath(tray, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/* ------------------------------------------------------------------ */
/* the viewer                                                          */
/* ------------------------------------------------------------------ */

// the picture opens out of the round avatar: p = 0 is the avatar, p = 1 is the full screen
private class PpHeroShape(val p: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val top = ((h - w) / 2f * (1f - p)).coerceAtLeast(0f)
        val r = w / 2f * (1f - p)
        return Outline.Rounded(RoundRect(0f, top, w, h - top, CornerRadius(r, r)))
    }
}

private fun Context.ppWindow(): Window? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c.window
        c = c.baseContext
    }
    return null
}

private fun ppRequest(ctx: Context, url: String): ImageRequest =
    ImageRequest.Builder(ctx)
        .data(url)
        .addHeader("Authorization", "Bearer ${Session.token}")
        .build()

@Composable
private fun PpPhoto(url: String) {
    val ctx = LocalContext.current
    val req = remember(url) {
        ImageRequest.Builder(ctx)
            .data(url)
            .addHeader("Authorization", "Bearer ${Session.token}")
            .crossfade(true)
            .build()
    }
    var state by remember(url) { mutableStateOf<AsyncImagePainter.State?>(null) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = req,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onState = { state = it }
        )
        when (state) {
            is AsyncImagePainter.State.Loading -> CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp)
            )
            is AsyncImagePainter.State.Error -> Text("Couldn't load this photo", color = Color(0xB3FFFFFF), fontSize = 14.sp)
            else -> {}
        }
    }
}

/** A profile video in the viewer: with sound, looping, the whole picture visible. Plays only while it is the page on screen. */
@Composable
private fun PpVideo(url: String, active: Boolean) {
    var ready by remember(url) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoopingVideo(
            url = url,
            modifier = Modifier.fillMaxSize(),
            muted = false,
            crop = false,
            active = active,
            onReady = { ready = true }
        )
        if (!ready) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun PpSegments(count: Int, current: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(count) { i ->
            val a by animateFloatAsState(if (i <= current) 1f else 0.32f, tween(160), label = "seg")
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = a))
            )
        }
    }
}

@Composable
internal fun PpAction(title: String, sub: String, glyph: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        glyph()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = Ui.ink)
            Spacer(Modifier.height(2.dp))
            Text(sub, fontSize = 13.sp, color = Ui.muted)
        }
    }
}

/**
 * Full-screen viewer for everyone's profile photos. Opens out of the avatar (from = where the
 * avatar is on screen), swipe or tap the sides to move, swipe down to close, three dots to save.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProfilePhotoViewer(
    userId: String,
    username: String,
    name: String,
    currentUrl: String,
    from: Rect?,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // the photo in use is known right away, the rest of the history is filled in when it arrives
    var pages by remember(userId, currentUrl) { mutableStateOf(listOf(ProfilePhoto(currentUrl, 0L, Avatars.isVideo(userId)))) }
    var menu by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var closing by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val open = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val dragY = remember { Animatable(0f) }
    val pager = rememberPagerState(pageCount = { pages.size })
    val chromeAlpha by animateFloatAsState(if (chrome) 1f else 0f, tween(200), label = "chrome")
    val dateFmt = remember { java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()) }

    LaunchedEffect(Unit) { open.animateTo(1f, tween(340, easing = FastOutSlowInEasing)) }

    LaunchedEffect(userId) {
        runIo({ ppFetchPhotos(userId, currentUrl) }) { r ->
            r.onSuccess { if (it.isNotEmpty()) pages = it }
        }
    }

    // the next and the previous photo load while this one is on screen
    LaunchedEffect(pager.currentPage, pages) {
        val i = pager.currentPage
        for (j in listOf(i - 1, i + 1)) {
            pages.getOrNull(j)?.let { if (!it.video) ctx.imageLoader.enqueue(ppRequest(ctx, it.url)) }
        }
    }

    // light status bar icons would vanish on the dark background
    DisposableEffect(Unit) {
        val w = view.context.ppWindow()
        val c = if (w != null) WindowCompat.getInsetsController(w, view) else null
        val old = c?.isAppearanceLightStatusBars
        c?.isAppearanceLightStatusBars = false
        onDispose {
            if (c != null && old != null) c.isAppearanceLightStatusBars = old
        }
    }

    // collapse = shrink back into the avatar, only makes sense while the first photo is showing
    fun close(collapse: Boolean) {
        if (closing) return
        closing = true
        scope.launch {
            if (collapse && from != null) open.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
            else fade.animateTo(0f, tween(180))
            onClose()
        }
    }

    fun go(to: Int) {
        if (to in 0 until pages.size) scope.launch { pager.animateScrollToPage(to) }
    }

    fun save(toGallery: Boolean) {
        menu = false
        val page = pages.getOrNull(pager.currentPage) ?: return
        val safe = username.replace(Regex("[^A-Za-z0-9_]"), "")
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val base = "Encore_" + (if (safe.isEmpty()) "photo" else safe) + "_" + stamp
        val app = ctx.applicationContext
        val isVideo = page.video
        runIo({ PhotoSaver.save(app, page.url, base, toGallery, video = isVideo) }) { r ->
            r.onSuccess {
                Toast.makeText(app, if (toGallery) "Saved to Gallery" else "Saved to Downloads", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(app, it.message ?: (if (isVideo) "Couldn't save the video" else "Couldn't save the photo"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler(enabled = !closing) { close(pager.currentPage == 0 && dragY.value == 0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
    ) {
        // dark background, fades in with the opening and thins out while dragging down
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val drag = (abs(dragY.value) / (size.height * 0.5f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    alpha = open.value * fade.value * (1f - drag)
                }
                .background(Color.Black)
        )

        // the photos
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            scope.launch { dragY.snapTo(dragY.value + dy) }
                        },
                        onDragEnd = {
                            if (abs(dragY.value) > size.height * 0.16f) close(false)
                            else scope.launch { dragY.animateTo(0f, spring()) }
                        },
                        onDragCancel = { scope.launch { dragY.animateTo(0f, spring()) } }
                    )
                }
                .graphicsLayer {
                    val p = open.value
                    val w = size.width
                    val h = size.height
                    // where the avatar sits, relative to this layer
                    val side = if (from != null) min(from.width, from.height) else w * 0.8f
                    val cx = if (from != null) from.center.x - origin.x else w / 2f
                    val cy = if (from != null) from.center.y - origin.y else h / 2f
                    val k0 = if (w > 0f) side / w else 1f
                    val dragFrac = (abs(dragY.value) / h.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val k = (k0 + (1f - k0) * p) * (1f - dragFrac * 0.2f)
                    scaleX = k
                    scaleY = k
                    translationX = (cx - w / 2f) * (1f - p)
                    translationY = (cy - h / 2f) * (1f - p) + dragY.value
                    alpha = fade.value
                    shape = PpHeroShape(p)
                    clip = true
                }
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { o ->
                            val w = size.width
                            when {
                                o.x < w * 0.3f -> go(pager.currentPage - 1)
                                o.x > w * 0.7f -> go(pager.currentPage + 1)
                                else -> chrome = !chrome
                            }
                        }
                    }
            ) { i ->
                val page = pages[i]
                if (page.video) PpVideo(page.url, active = pager.currentPage == i) else PpPhoto(page.url)
            }
        }

        // top bar: segments, name, count and date, menu
        val barAlpha = ((open.value - 0.55f) / 0.45f).coerceIn(0f, 1f) * fade.value * chromeAlpha *
            (1f - (abs(dragY.value) / 200f).coerceIn(0f, 1f))
        if (barAlpha > 0.01f) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = barAlpha }
                    .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(top = 8.dp, bottom = 16.dp)
            ) {
                if (pages.size > 1) {
                    PpSegments(pages.size, pager.currentPage)
                    Spacer(Modifier.height(4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { close(pager.currentPage == 0 && dragY.value == 0f) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val at = pages.getOrNull(pager.currentPage)?.at ?: 0L
                        val dateText = if (at > 0L) dateFmt.format(java.util.Date(at)) else null
                        val isVid = pages.getOrNull(pager.currentPage)?.video == true
                        val sub = if (pages.size > 1) {
                            (pager.currentPage + 1).toString() + " of " + pages.size + (if (dateText != null) " · $dateText" else "")
                        } else {
                            dateText ?: (if (isVid) "Profile video" else "Profile photo")
                        }
                        Text(sub, color = Color(0xB3FFFFFF), fontSize = 12.sp)
                    }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                    }
                }
            }
        }
    }

    if (menu) {
        ModalBottomSheet(onDismissRequest = { menu = false }, containerColor = Ui.bg) {
            val vidNow = pages.getOrNull(pager.currentPage)?.video == true
            PpAction("Save to Gallery", (if (vidNow) "Movies" else "Pictures") + " \u00B7 Encore", { PpGalleryGlyph(Ui.ink) }) { save(true) }
            PpAction("Save to Downloads", "Download \u00B7 Encore", { PpDownloadGlyph(Ui.ink) }) { save(false) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

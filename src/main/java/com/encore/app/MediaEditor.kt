package com.encore.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** What the editor hands back. ttl: 0 = keep, -1 = view once, otherwise seconds after opening. */
data class EditedMedia(
    val uri: Uri,
    val isVideo: Boolean,
    val caption: String,
    val ttl: Int,
    val width: Int,
    val height: Int
)

private enum class Quality(val label: String, val maxEdge: Int, val jpeg: Int) {
    Standard("Standard", 1280, 76),
    HD("HD", 2560, 88),
    Original("Original", Int.MAX_VALUE, 95)
}

private enum class Panel { Adjust, Timer, Quality }

private val TTL_OPTIONS = listOf(0 to "Keep", -1 to "Once", 3 to "3s", 10 to "10s", 30 to "30s")

/** Everything decided for ONE item. The UI writes here, export reads. */
private class EditState {
    var exposure by mutableFloatStateOf(0f)
    var contrast by mutableFloatStateOf(0f)
    var saturation by mutableFloatStateOf(0f)
    var ttl by mutableIntStateOf(0)
    var quality by mutableStateOf(Quality.Standard)
    var caption by mutableStateOf("")

    val dirty: Boolean get() = exposure != 0f || contrast != 0f || saturation != 0f

    fun reset() {
        exposure = 0f; contrast = 0f; saturation = 0f
    }

    /** saturation -> exposure gain -> contrast. Same numbers drive preview and export. */
    fun matrix(): FloatArray {
        val m = android.graphics.ColorMatrix()
        m.setSaturation((1f + saturation).coerceIn(0f, 2f))
        val g = 2f.pow(exposure.coerceIn(-1f, 1f))
        m.postConcat(android.graphics.ColorMatrix().apply { setScale(g, g, g, 1f) })
        val s = (1f + contrast).coerceIn(0f, 2f)
        val t = (1f - s) * 127.5f
        m.postConcat(
            android.graphics.ColorMatrix(
                floatArrayOf(
                    s, 0f, 0f, 0f, t,
                    0f, s, 0f, 0f, t,
                    0f, 0f, s, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        return m.array
    }
}

@Composable
fun MediaEditor(items: List<GalleryItem>, onClose: () -> Unit, onSend: (List<EditedMedia>) -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val states = remember { items.map { EditState() } }
    var index by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var sending by remember { mutableStateOf(false) }

    val item = items[index]
    val st = states[index]

    val preview by produceState<Bitmap?>(null, item.uri) {
        value = null
        value = withContext(Dispatchers.IO) {
            if (item.isVideo) Thumbs.get(ctx, item, 1080) else decodeOriented(ctx, item.uri, 1600)
        }
    }

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
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (!sending) onClose() }) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Ui.ink)
            }
            Spacer(Modifier.weight(1f))
            if (items.size > 1) {
                Text("${index + 1} / ${items.size}", fontSize = 14.sp, color = Ui.muted, modifier = Modifier.padding(end = 16.dp))
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Ui.soft),
            contentAlignment = Alignment.Center
        ) {
            val p = preview
            if (p != null) {
                Image(
                    bitmap = p.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    colorFilter = if (!item.isVideo && st.dirty) ColorFilter.colorMatrix(ColorMatrix(st.matrix())) else null
                )
            }
            if (item.isVideo) {
                Text(
                    "VIDEO · ${durationLabel(item.durationMs)}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .background(Color(0x99000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        if (items.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items) { i, g ->
                    val shape = RoundedCornerShape(10.dp)
                    Thumb(
                        g, 160,
                        Modifier
                            .size(52.dp)
                            .clip(shape)
                            .alpha(if (i == index) 1f else 0.45f)
                            .then(if (i == index) Modifier.border(2.dp, Ui.ink, shape) else Modifier)
                            .clickable { if (!sending) index = i }
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(horizontal = 20.dp)
        ) {
            when (panel) {
                Panel.Adjust -> {
                    Spacer(Modifier.height(8.dp))
                    AdjustRow("Exposure", st.exposure) { st.exposure = it }
                    AdjustRow("Contrast", st.contrast) { st.contrast = it }
                    AdjustRow("Saturation", st.saturation) { st.saturation = it }
                    TextButton(onClick = { st.reset() }, modifier = Modifier.align(Alignment.End)) {
                        Text("Reset", color = Ui.ink, fontWeight = FontWeight.SemiBold)
                    }
                }
                Panel.Timer -> {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TTL_OPTIONS.forEach { (v, label) -> Pill(label, st.ttl == v) { st.ttl = v } }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when (st.ttl) {
                            0 -> "Stays in the chat."
                            -1 -> "Can be opened once, then it's gone."
                            else -> "Disappears ${st.ttl} seconds after it's opened."
                        },
                        fontSize = 13.sp, color = Ui.muted
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Panel.Quality -> {
                    Spacer(Modifier.height(12.dp))
                    if (item.isVideo) {
                        Text("Videos are sent in their original quality for now.", fontSize = 13.sp, color = Ui.muted)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Quality.values().forEach { q -> Pill(q.label, st.quality == q) { st.quality = q } }
                        }
                        Spacer(Modifier.height(10.dp))
                        val est = estimateBytes(item, st.quality)
                        Text(
                            if (st.quality == Quality.Original) "Original · ${sizeLabel(item.sizeBytes)}"
                            else "About ${sizeLabel(est)} · original ${sizeLabel(item.sizeBytes)}",
                            fontSize = 13.sp, color = Ui.muted
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                null -> {}
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            if (!item.isVideo) {
                Tab("Adjust", panel == Panel.Adjust, st.dirty, Modifier.weight(1f)) {
                    panel = if (panel == Panel.Adjust) null else Panel.Adjust
                }
            }
            Tab(
                if (st.ttl == 0) "Timer" else "Timer · " + (TTL_OPTIONS.firstOrNull { it.first == st.ttl }?.second ?: ""),
                panel == Panel.Timer, st.ttl != 0, Modifier.weight(1f)
            ) { panel = if (panel == Panel.Timer) null else Panel.Timer }
            Tab(
                if (item.isVideo) "Quality" else "Quality · ${st.quality.label}",
                panel == Panel.Quality, false, Modifier.weight(1f)
            ) { panel = if (panel == Panel.Quality) null else Panel.Quality }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = st.caption,
                onValueChange = { st.caption = it },
                placeholder = { Text("Add a caption…", color = Ui.muted) },
                shape = RoundedCornerShape(24.dp),
                maxLines = 3,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Ui.soft,
                    unfocusedContainerColor = Ui.soft,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    if (sending) return@IconButton
                    sending = true
                    scope.launch {
                        try {
                            val out = withContext(Dispatchers.IO) {
                                items.mapIndexed { i, g ->
                                    val s = states[i]
                                    if (g.isVideo) EditedMedia(g.uri, true, s.caption.trim(), s.ttl, g.width, g.height)
                                    else exportImage(ctx, g, s)
                                }
                            }
                            onSend(out)
                        } catch (e: Exception) {
                            sending = false
                            Toast.makeText(ctx, "Couldn't prepare the media. Try again.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Ui.ink),
                    contentAlignment = Alignment.Center
                ) {
                    if (sending) {
                        CircularProgressIndicator(color = Ui.onInk, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Ui.onInk, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** Quick send from the picker: no edits, Standard quality, no timer. */
internal suspend fun quickExport(ctx: Context, items: List<GalleryItem>): List<EditedMedia> =
    withContext(Dispatchers.IO) {
        items.map { g ->
            if (g.isVideo) EditedMedia(g.uri, true, "", 0, g.width, g.height)
            else exportImage(ctx, g, EditState())
        }
    }

@Composable
private fun AdjustRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = Ui.ink, modifier = Modifier.width(84.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = -1f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Ui.ink,
                activeTrackColor = Ui.ink,
                inactiveTrackColor = Ui.line
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Ui.ink else Ui.soft)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Ui.onInk else Ui.ink)
    }
}

@Composable
private fun Tab(text: String, open: Boolean, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (open || active) Ui.ink else Ui.muted
        )
    }
}

// ---------------------------------------------------------------- image work

private fun estimateBytes(g: GalleryItem, q: Quality): Long {
    if (q == Quality.Original) return g.sizeBytes
    val edge = max(g.width, g.height)
    if (edge <= 0) return g.sizeBytes
    val r = min(q.maxEdge, edge).toFloat() / edge
    val f = if (q == Quality.Standard) 0.7f else 0.95f
    return min(g.sizeBytes, (g.sizeBytes * r * r * f).toLong())
}

/** Decodes with sampling, applies EXIF rotation, and limits the longest side to [maxEdge]. */
internal fun decodeOriented(ctx: Context, uri: Uri, maxEdge: Int): Bitmap? {
    val r = ctx.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val raw = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

    val m = exifMatrix(readOrientation(ctx, uri))
    val edge = max(raw.width, raw.height)
    if (edge > maxEdge) {
        val s = maxEdge.toFloat() / edge
        m.postScale(s, s)
    }
    if (m.isIdentity) return raw
    val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    if (out !== raw) raw.recycle()
    return out
}

private fun exportImage(ctx: Context, g: GalleryItem, s: EditState): EditedMedia {
    val dir = File(ctx.cacheDir, "encore_out").apply { mkdirs() }
    val stamp = "${System.currentTimeMillis()}_${(0..9999).random()}"
    val caption = s.caption.trim()

    // Original and untouched: copy the bytes as they are, no quality loss.
    if (s.quality == Quality.Original && !s.dirty) {
        val ext = when (ctx.contentResolver.getType(g.uri)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> "jpg"
        }
        val f = File(dir, "img_$stamp.$ext")
        ctx.contentResolver.openInputStream(g.uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return EditedMedia(Uri.fromFile(f), false, caption, s.ttl, g.width, g.height)
    }

    // Edited "Original" is capped so a 48MP photo can't run out of memory.
    val cap = if (s.quality == Quality.Original) 3072 else s.quality.maxEdge
    val src = decodeOriented(ctx, g.uri, cap) ?: error("Could not read image")
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    if (s.dirty) paint.colorFilter = ColorMatrixColorFilter(android.graphics.ColorMatrix(s.matrix()))
    canvas.drawBitmap(src, 0f, 0f, paint)
    src.recycle()

    val f = File(dir, "img_$stamp.jpg")
    f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, s.quality.jpeg, it) }
    val w = out.width
    val h = out.height
    out.recycle()
    return EditedMedia(Uri.fromFile(f), false, caption, s.ttl, w, h)
}

private fun readOrientation(ctx: Context, uri: Uri): Int = try {
    ctx.contentResolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
} catch (e: Exception) {
    ExifInterface.ORIENTATION_NORMAL
}

private fun exifMatrix(o: Int): Matrix = Matrix().apply {
    when (o) {
        ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
    }
}

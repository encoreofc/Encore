package com.encore.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GalleryItem(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int
) {
    /** Image and video ids can collide in MediaStore. */
    val key: Long get() = if (isVideo) id or (1L shl 40) else id
}

private const val MAX_PICK = 10

@Composable
fun MediaPicker(
    onClose: () -> Unit,
    onEdit: (List<GalleryItem>) -> Unit,
    onSend: (List<GalleryItem>) -> Unit,
    /** Called when the gallery can't be used (permission refused or unreadable). */
    onDenied: () -> Unit = {}
) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    var access by remember { mutableStateOf(hasMediaAccess(ctx)) }
    var denied by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<GalleryItem>() }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = hasMediaAccess(ctx)
        denied = !access
        if (denied) onDenied()
    }

    LaunchedEffect(access) {
        if (access) {
            items = try {
                withContext(Dispatchers.IO) { queryGallery(ctx) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onDenied()
                emptyList()
            }
            loaded = true
        } else {
            askPermission.launch(mediaPermissions())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Ui.ink)
                }
                Text("Recent", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ui.ink, modifier = Modifier.weight(1f))
                if (selected.isNotEmpty()) {
                    Text("${selected.size} selected", fontSize = 13.sp, color = Ui.muted, modifier = Modifier.padding(end = 16.dp))
                }
            }

            when {
                !access -> CenterMessage(
                    text = if (denied) "Photo access is turned off. Enable it in Settings to send media."
                    else "Allow access to your photos and videos to send them.",
                    action = if (denied) "Open settings" else "Allow access"
                ) {
                    if (denied) {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } else askPermission.launch(mediaPermissions())
                }
                loaded && items.isEmpty() -> CenterMessage("No photos or videos yet.", null) {}
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.key }) { g ->
                        Cell(g, selected.indexOf(g) + 1) {
                            val at = selected.indexOf(g)
                            if (at >= 0) selected.removeAt(at)
                            else if (selected.size >= MAX_PICK) {
                                Toast.makeText(ctx, "You can select up to $MAX_PICK items", Toast.LENGTH_SHORT).show()
                            } else selected.add(g)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = selected.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Ui.soft)
                        .border(1.dp, Ui.line, CircleShape)
                        .clickable { onEdit(selected.toList()) }
                        .padding(horizontal = 26.dp, vertical = 15.dp)
                ) {
                    Text("Edit", color = Ui.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Ui.ink)
                        .clickable { onSend(selected.toList()) }
                        .padding(horizontal = 30.dp, vertical = 15.dp)
                ) {
                    Text("Send · ${selected.size}", color = Ui.onInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CenterMessage(text: String, action: String?, onAction: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text, color = Ui.ink, fontSize = 15.sp, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Ui.ink)
                    .clickable { onAction() }
                    .padding(horizontal = 28.dp, vertical = 12.dp)
            ) {
                Text(action, color = Ui.onInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Cell(item: GalleryItem, number: Int, onClick: () -> Unit) {
    val on = number > 0
    val s by animateFloatAsState(if (on) 0.88f else 1f, label = "sel")
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Ui.soft)
            .clickable(onClick = onClick)
    ) {
        Thumb(item, 320, Modifier.fillMaxSize().graphicsLayer { scaleX = s; scaleY = s })
        if (item.isVideo) {
            Text(
                durationLabel(item.durationMs),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (on) Ui.ink else Color(0x55000000))
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (on) Text(number.toString(), color = Ui.onInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Gallery thumbnail, no image library needed. */
@Composable
internal fun Thumb(item: GalleryItem, px: Int, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, item.uri, px) {
        value = withContext(Dispatchers.IO) { Thumbs.get(ctx, item, px) }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = scale)
    else Box(modifier.background(Ui.soft))
}

internal object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @Suppress("DEPRECATION")
    fun get(ctx: Context, item: GalleryItem, px: Int): Bitmap? {
        val key = "${item.uri}@$px"
        cache.get(key)?.let { return it }
        val bmp = try {
            if (Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.loadThumbnail(item.uri, Size(px, px), null)
            } else if (item.isVideo) {
                MediaStore.Video.Thumbnails.getThumbnail(ctx.contentResolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            } else {
                MediaStore.Images.Thumbnails.getThumbnail(ctx.contentResolver, item.id, MediaStore.Images.Thumbnails.MINI_KIND, null)
            }
        } catch (e: Exception) {
            null
        }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }
}

internal fun durationLabel(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** Android 14 "selected photos only" also counts as access. */
private fun hasMediaAccess(ctx: Context): Boolean {
    fun ok(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 34 -> ok(Manifest.permission.READ_MEDIA_IMAGES) ||
            ok(Manifest.permission.READ_MEDIA_VIDEO) || ok(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> ok(Manifest.permission.READ_MEDIA_IMAGES) || ok(Manifest.permission.READ_MEDIA_VIDEO)
        else -> ok(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun queryGallery(ctx: Context): List<GalleryItem> {
    val images = queryType(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
    val videos = queryType(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
    return (images + videos).sortedByDescending { it.first }.take(3000).map { it.second }
}

private fun queryType(ctx: Context, base: Uri, video: Boolean): List<Pair<Long, GalleryItem>> {
    val projection = buildList {
        add(MediaStore.MediaColumns._ID)
        add(MediaStore.MediaColumns.DATE_ADDED)
        add(MediaStore.MediaColumns.SIZE)
        add(MediaStore.MediaColumns.WIDTH)
        add(MediaStore.MediaColumns.HEIGHT)
        if (video) add(MediaStore.Video.VideoColumns.DURATION)
    }.toTypedArray()
    val out = ArrayList<Pair<Long, GalleryItem>>()
    ctx.contentResolver.query(base, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
        val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val iW = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val iH = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val iDur = if (video) c.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION) else -1
        while (c.moveToNext() && out.size < 2500) {
            val id = c.getLong(iId)
            out += c.getLong(iDate) to GalleryItem(
                id = id,
                uri = ContentUris.withAppendedId(base, id),
                isVideo = video,
                durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                sizeBytes = c.getLong(iSize),
                width = c.getInt(iW),
                height = c.getInt(iH)
            )
        }
    }
    return out
}

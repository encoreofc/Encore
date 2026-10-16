package com.encore.app

import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
private fun PlayGlyph() {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val tri = Path().apply {
            moveTo(w * 0.28f, h * 0.12f)
            lineTo(w * 0.86f, h * 0.5f)
            lineTo(w * 0.28f, h * 0.88f)
            close()
        }
        drawPath(tri, Color.White)
    }
}

@Composable
fun VideoBlock(item: MsgItem, progress: Float?, onPlay: () -> Unit) {
    val m = item.media
    val ctx = LocalContext.current
    val local = m?.localUri
    val (bw, bh) = mediaBoxSize(m)
    Box(
        modifier = Modifier
            .width(bw)
            .height(bh)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1B1D21))
            .clickable(enabled = progress == null) { onPlay() },
        contentAlignment = Alignment.Center
    ) {
        if (local != null) {
            // first frame of the video that is already on the phone
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(Uri.parse(local)).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (progress != null) {
            if (progress > 0f) {
                CircularProgressIndicator(progress = progress, color = Color.White, modifier = Modifier.size(36.dp))
            } else {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
            }
        } else {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000)),
                contentAlignment = Alignment.Center
            ) {
                PlayGlyph()
            }
        }
        Text(
            "Video" + if (m != null && m.size > 0) " · " + sizeLabel(m.size) else "",
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerDialog(cid: String, item: MsgItem, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val url = Uploads.url(cid, item.msg.id)
    val local = item.media?.localUri
    var menu by remember { mutableStateOf(false) }
    val open = remember { Animatable(0f) }
    val whenText = remember(item.msg.createdAt) {
        java.text.SimpleDateFormat("d MMM yyyy \u00B7 HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(item.msg.createdAt))
    }

    LaunchedEffect(Unit) { open.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }

    fun save(toGallery: Boolean) {
        menu = false
        val app = ctx.applicationContext
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        Toast.makeText(app, "Saving video\u2026", Toast.LENGTH_SHORT).show()
        runIo({ PhotoSaver.save(app, url, "Encore_$stamp", toGallery, video = true) }) { r ->
            r.onSuccess {
                Toast.makeText(app, if (toGallery) "Saved to Gallery" else "Saved to Downloads", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(app, it.message ?: "Couldn't save the video", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = open.value }
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { c ->
                    VideoView(c).apply {
                        val mc = MediaController(c)
                        mc.setAnchorView(this)
                        setMediaController(mc)
                        // a downloaded copy plays instantly and offline; otherwise stream it
                        if (local != null) setVideoURI(Uri.parse(local))
                        else setVideoURI(Uri.parse(url), mapOf("Authorization" to "Bearer ${Session.token}"))
                        setOnPreparedListener { it.start() }
                        setOnErrorListener { _, _, _ ->
                            Toast.makeText(c, "Can't play this video", Toast.LENGTH_SHORT).show()
                            true
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(top = 6.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text("Video", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(whenText, color = Color(0xB3FFFFFF), fontSize = 12.sp)
                }
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                }
            }
        }
    }

    if (menu) {
        ModalBottomSheet(onDismissRequest = { menu = false }, containerColor = Ui.bg) {
            PpAction("Save to Gallery", "Movies \u00B7 Encore", { PpGalleryGlyph(Ui.ink) }) { save(true) }
            PpAction("Save to Downloads", "Download \u00B7 Encore", { PpDownloadGlyph(Ui.ink) }) { save(false) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

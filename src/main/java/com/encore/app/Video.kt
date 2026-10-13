package com.encore.app

import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
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
import androidx.compose.material3.Text
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
    Box(
        modifier = Modifier
            .width(240.dp)
            .height(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1B1D21))
            .clickable(enabled = progress == null) { onPlay() },
        contentAlignment = Alignment.Center
    ) {
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

@Composable
fun VideoPlayerDialog(cid: String, item: MsgItem, onClose: () -> Unit) {
    val url = Uploads.url(cid, item.msg.id)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val mc = MediaController(ctx)
                        mc.setAnchorView(this)
                        setMediaController(mc)
                        setVideoURI(Uri.parse(url), mapOf("Authorization" to "Bearer ${Session.token}"))
                        setOnPreparedListener { it.start() }
                        setOnErrorListener { _, _, _ ->
                            Toast.makeText(ctx, "Can't play this video", Toast.LENGTH_SHORT).show()
                            true
                        }
                    }
                },
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

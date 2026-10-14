package com.encore.app

import android.net.Uri
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.delay

/** An opened view-once / timed message. deadline = local clock time when it closes (null = view once, closes by hand). */
class OnceSession(val item: MsgItem, val deadline: Long?)

@Composable
private fun OnceGlyph(ttl: Int, tint: Color) {
    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                tint,
                style = Stroke(width = 1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            )
        }
        Text(
            if (ttl == -1) "1" else ttl.toString(),
            color = tint,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Chat bubble content for a message that disappears. Never loads the real media. */
@Composable
fun OnceBlock(item: MsgItem, mine: Boolean, progress: Float?, onOpen: () -> Unit) {
    val ttl = item.ttl
    val opened = item.openedAt
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    if (ttl > 0 && opened != null) {
        LaunchedEffect(opened) {
            while (System.currentTimeMillis() < opened + ttl * 1000L) {
                delay(500)
                now = System.currentTimeMillis()
            }
            now = System.currentTimeMillis()
        }
    }
    val expired = !mine && opened != null && (ttl == -1 || now > opened + ttl * 1000L)
    val canOpen = !mine && progress == null && !expired

    val label = if (ttl == -1) "View once" else "${ttl}s"
    val title = (if (item.msg.kind == "video") "Video" else "Photo") + " · " + label
    val sub = when {
        mine -> if (opened != null) "Opened" else "Not opened yet"
        expired -> if (ttl == -1) "Opened" else "Expired"
        opened != null -> "Tap to view again"
        else -> "Tap to view"
    }

    Box(
        modifier = Modifier
            .width(240.dp)
            .height(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2A2D33), Color(0xFF14161A))))
            .clickable(enabled = canOpen) { onOpen() },
        contentAlignment = Alignment.Center
    ) {
        SpoilerDust(Modifier.matchParentSize(), revealed = expired)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (progress != null) {
                if (progress > 0f) {
                    CircularProgressIndicator(progress = progress, color = Color.White, modifier = Modifier.size(36.dp))
                } else {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                }
            } else {
                OnceGlyph(ttl, if (expired) Color(0x66FFFFFF) else Color.White)
            }
            Spacer(Modifier.height(8.dp))
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = Color(0xB3FFFFFF), fontSize = 12.sp)
        }
    }
}

/** Full-screen viewer. Screenshots and screen recording are blocked, nothing is cached. */
@Composable
fun OnceViewer(cid: String, s: OnceSession, onClose: () -> Unit) {
    val item = s.item
    val ctx = LocalContext.current
    val url = Uploads.url(cid, item.msg.id)
    val isVideo = item.msg.kind == "video"
    val deadline = s.deadline
    var left by remember { mutableStateOf(1f) }

    if (deadline != null) {
        LaunchedEffect(deadline) {
            val total = (item.ttl * 1000f).coerceAtLeast(1f)
            while (true) {
                val rem = deadline - System.currentTimeMillis()
                if (rem <= 0L) {
                    onClose()
                    break
                }
                left = (rem / total).coerceIn(0f, 1f)
                delay(50)
            }
        }
    }

    val req = remember(url) {
        ImageRequest.Builder(ctx)
            .data(url)
            .addHeader("Authorization", "Bearer ${Session.token}")
            .diskCachePolicy(CachePolicy.DISABLED)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (isVideo) {
                AndroidView(
                    factory = { c ->
                        VideoView(c).apply {
                            setVideoURI(Uri.parse(url), mapOf("Authorization" to "Bearer ${Session.token}"))
                            setOnPreparedListener { it.start() }
                            setOnErrorListener { _, _, _ ->
                                Toast.makeText(c, "Can't play this video", Toast.LENGTH_SHORT).show()
                                true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AsyncImage(
                    model = req,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                if (deadline != null) {
                    LinearProgressIndicator(
                        progress = left,
                        color = Color.White,
                        trackColor = Color(0x55FFFFFF),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }

            val cap = item.msg.body
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000))))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!cap.isNullOrBlank()) {
                    Text(cap, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                }
                if (item.ttl == -1) {
                    Text("You can only view this once", color = Color(0xB3FFFFFF), fontSize = 12.sp)
                }
            }
        }
    }
}

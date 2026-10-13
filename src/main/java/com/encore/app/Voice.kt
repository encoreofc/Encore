package com.encore.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

fun durLabel(ms: Long): String {
    val s = (ms / 1000).toInt()
    return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
}

object VoiceUpload {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    fun send(cid: String, f: File, durMs: Long, clientId: String, replyTo: String?): MsgItem {
        val q = StringBuilder("/v1/conversations/$cid/media?kind=voice&name=voice.m4a&dur=$durMs&client_id=${Uri.encode(clientId)}")
        if (replyTo != null) q.append("&reply_to=${Uri.encode(replyTo)}")
        val req = Request.Builder()
            .url(API_BASE + q)
            .header("Authorization", "Bearer ${Session.token}")
            .post(f.asRequestBody("audio/mp4".toMediaType()))
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
}

class VoiceController(private val st: ConversationState, private val ctx: Context) {
    var recording by mutableStateOf(false)
    var seconds by mutableStateOf(0)
    var askPermission: () -> Unit = {}

    private val h = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            if (seconds >= 600) {
                stopAndSend()
            } else {
                h.postDelayed(this, 250)
            }
        }
    }

    fun onMicClick() {
        val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (ok) start() else askPermission()
    }

    fun start() {
        if (recording) return
        val f = File(ctx.cacheDir, "voice-" + System.currentTimeMillis() + ".m4a")
        try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            VoicePlayer.stop()
            recorder = r
            file = f
            startedAt = System.currentTimeMillis()
            seconds = 0
            recording = true
            h.post(tick)
        } catch (e: Exception) {
            f.delete()
            st.problem = "Can't start recording"
        }
    }

    private fun finish(): Pair<File, Long>? {
        val r = recorder ?: return null
        val f = file
        val dur = System.currentTimeMillis() - startedAt
        recording = false
        h.removeCallbacks(tick)
        var ok = true
        try {
            r.stop()
        } catch (e: RuntimeException) {
            ok = false
        }
        r.release()
        recorder = null
        file = null
        if (ok && f != null && dur >= 700L) return f to dur
        f?.delete()
        return null
    }

    fun cancel() {
        finish()?.first?.delete()
    }

    fun stopAndSend() {
        val res = finish()
        if (res == null) {
            st.problem = "Recording too short"
            return
        }
        sendVoice(res.first, res.second)
    }

    private fun sendVoice(f: File, durMs: Long) {
        val cid = java.util.UUID.randomUUID().toString()
        val lid = "local-$cid"
        val rep = st.replyTo
        val local = MsgItem(
            Msg(lid, st.conv.id, st.me.id, "voice", null, System.currentTimeMillis(), false, cid),
            null, null, rep?.msg?.id, null,
            Media("voice.m4a", f.length(), "audio/mp4", 0, 0, durMs, Uri.fromFile(f).toString())
        )
        st.pending = st.pending + local
        st.progress = st.progress + (lid to 0f)
        st.replyTo = null
        st.problem = null
        runIo({ VoiceUpload.send(st.conv.id, f, durMs, cid, rep?.msg?.id) }) { r ->
            st.pending = st.pending.filter { it.msg.clientId != cid }
            st.progress = st.progress - lid
            f.delete()
            r.onSuccess { m -> st.items = addItem(st.items, m) }.onFailure { st.problem = it.message }
        }
    }
}

@Composable
fun rememberVoice(st: ConversationState): VoiceController {
    val ctx = LocalContext.current
    val v = remember(st.conv.id) { VoiceController(st, ctx.applicationContext) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) v.start() else st.problem = "Microphone permission is needed to record"
    }
    v.askPermission = { perm.launch(Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(v) {
        onDispose {
            v.cancel()
            VoicePlayer.stop()
        }
    }
    return v
}

object VoicePlayer {
    var playingId by mutableStateOf<String?>(null)
    var position by mutableStateOf(0)
    private var mp: MediaPlayer? = null
    private val h = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            val p = mp
            if (p != null && playingId != null) {
                try {
                    position = p.currentPosition
                } catch (e: Exception) {
                }
                h.postDelayed(this, 200)
            }
        }
    }

    fun toggle(ctx: Context, cid: String, mid: String, localUri: String?) {
        if (playingId == mid) {
            stop()
            return
        }
        stop()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            if (localUri != null) {
                p.setDataSource(ctx, Uri.parse(localUri))
            } else {
                p.setDataSource(ctx, Uri.parse(Uploads.url(cid, mid)), mapOf("Authorization" to "Bearer ${Session.token}"))
            }
            p.setOnPreparedListener {
                it.start()
                position = 0
                h.post(ticker)
            }
            p.setOnCompletionListener { stop() }
            p.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            mp = p
            playingId = mid
            position = 0
            p.prepareAsync()
        } catch (e: Exception) {
            p.release()
            stop()
        }
    }

    fun stop() {
        h.removeCallbacks(ticker)
        mp?.let {
            try {
                it.stop()
            } catch (e: Exception) {
            }
            it.release()
        }
        mp = null
        playingId = null
        position = 0
    }
}

@Composable
private fun PlayPauseIcon(playing: Boolean, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        if (playing) {
            drawRect(color, Offset(w * 0.22f, h * 0.18f), Size(w * 0.18f, h * 0.64f))
            drawRect(color, Offset(w * 0.6f, h * 0.18f), Size(w * 0.18f, h * 0.64f))
        } else {
            val tri = Path().apply {
                moveTo(w * 0.3f, h * 0.15f)
                lineTo(w * 0.82f, h * 0.5f)
                lineTo(w * 0.3f, h * 0.85f)
                close()
            }
            drawPath(tri, color)
        }
    }
}

@Composable
fun VoiceRow(cid: String, item: MsgItem, progress: Float?) {
    val m = item.media ?: return
    val ctx = LocalContext.current
    val id = item.msg.id
    val playing = VoicePlayer.playingId == id
    val total = m.dur.coerceAtLeast(1L)
    val frac = if (playing) (VoicePlayer.position.toFloat() / total).coerceIn(0f, 1f) else 0f
    val shown = if (playing) VoicePlayer.position.toLong() else total
    val tone = LocalContentColor.current
    Row(
        modifier = Modifier
            .widthIn(min = 220.dp)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(tone.copy(alpha = 0.15f))
                .clickable(enabled = progress == null) { VoicePlayer.toggle(ctx, cid, id, m.localUri) },
            contentAlignment = Alignment.Center
        ) {
            if (progress != null) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                PlayPauseIcon(playing, tone)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = frac,
                color = tone,
                trackColor = tone.copy(alpha = 0.25f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.height(6.dp))
            Text(durLabel(shown), fontSize = 12.sp, color = tone.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun RecordingBar(v: VoiceController) {
    val t = rememberInfiniteTransition(label = "rec")
    val alpha by t.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dot"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { v.cancel() }) {
            Icon(Icons.Default.Delete, contentDescription = "Cancel", tint = Ui.ink)
        }
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color(0xFFE5483B).copy(alpha = alpha))
        )
        Spacer(Modifier.width(10.dp))
        Text(durLabel(v.seconds * 1000L), fontSize = 16.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = { v.stopAndSend() }) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Ui.ink),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = Ui.onInk,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

package com.encore.app

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

// ---------------------------------------------------------------------------
// Profile video / GIF / background picture.
// Limits are the same as the server: video 20 s and 12 MB, GIF 12 MB, picture 5 MB, background 8 MB.
// A video longer than 20 s is cut to its first 20 s on the phone (MP4 only), without re-encoding.
// ---------------------------------------------------------------------------

private const val PM_MB = 1024L * 1024L
private const val PM_MAX_MS = 20000L
private const val PM_VIDEO_LIMIT = 12 * PM_MB
private val PM_VIDEO_TYPES = listOf("video/mp4", "video/webm", "video/quicktime", "video/3gpp")
private val PM_IMAGE_TYPES = listOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif")

internal class ProfileUpload(val bytes: ByteArray, val type: String, val durMs: Long)

private fun pmRead(ctx: Context, uri: Uri, limit: Long): ByteArray {
    val s = ctx.contentResolver.openInputStream(uri) ?: throw ApiException("bad_file", "Can't read this file")
    return s.use {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = it.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw ApiException("too_large", "This file is too large (max ${limit / PM_MB} MB)")
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }
}

private fun pmDuration(ctx: Context, uri: Uri): Long {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, uri)
        return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        return 0L
    } finally {
        try {
            r.release()
        } catch (e: Exception) {
        }
    }
}

private fun pmRotation(ctx: Context, uri: Uri): Int {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, uri)
        return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    } catch (e: Exception) {
        return 0
    } finally {
        try {
            r.release()
        } catch (e: Exception) {
        }
    }
}

/** Copies the picture part of the first [maxUs] microseconds into a new MP4 (no sound: smaller file, the avatar plays muted). */
private fun pmTrim(ctx: Context, uri: Uri, maxUs: Long): ByteArray {
    val out = File(ctx.cacheDir, "profile_trim_" + System.nanoTime() + ".mp4")
    val ex = MediaExtractor()
    var mux: MediaMuxer? = null
    var started = false
    try {
        ex.setDataSource(ctx, uri, null)
        mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var src = -1
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                src = i
                track = mux.addTrack(f)
                break
            }
        }
        if (src < 0) throw ApiException("bad_file", "Can't read this video")
        ex.selectTrack(src)
        mux.setOrientationHint(pmRotation(ctx, uri))
        mux.start()
        started = true
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            info.offset = 0
            info.size = ex.readSampleData(buf, 0)
            if (info.size < 0) break
            val t = ex.sampleTime
            if (t < 0 || t > maxUs) break
            info.presentationTimeUs = t
            info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_SYNC_FRAME else 0
            mux.writeSampleData(track, buf, info)
            ex.advance()
        }
        mux.stop()
        started = false
        return out.readBytes()
    } catch (e: ApiException) {
        throw e
    } catch (e: Exception) {
        throw ApiException("trim_failed", "Couldn't cut this video to 20 seconds. Pick a shorter MP4.")
    } finally {
        try {
            if (started) mux?.stop()
        } catch (e: Exception) {
        }
        try {
            mux?.release()
        } catch (e: Exception) {
        }
        try {
            ex.release()
        } catch (e: Exception) {
        }
        out.delete()
    }
}

/** Reads what the person picked and makes it fit the server limits. Runs off the main thread. */
internal fun prepareProfileMedia(ctx: Context, uri: Uri, cover: Boolean): ProfileUpload {
    val mime = (ctx.contentResolver.getType(uri) ?: "").lowercase()
    if (mime.startsWith("video/")) {
        if (cover) throw ApiException("bad_type", "The background must be a picture")
        if (mime !in PM_VIDEO_TYPES) throw ApiException("bad_type", "Use an MP4 or WebM video")
        val dur = pmDuration(ctx, uri)
        if (dur <= 0L) throw ApiException("bad_file", "Can't read this video")
        if (dur <= PM_MAX_MS) {
            return ProfileUpload(pmRead(ctx, uri, PM_VIDEO_LIMIT), mime, dur)
        }
        if (mime != "video/mp4") throw ApiException("too_long", "A profile video can be 20 seconds at most")
        val bytes = pmTrim(ctx, uri, PM_MAX_MS * 1000L)
        if (bytes.size > PM_VIDEO_LIMIT) throw ApiException("too_large", "This video is too large (max 12 MB)")
        return ProfileUpload(bytes, "video/mp4", PM_MAX_MS)
    }
    if (mime !in PM_IMAGE_TYPES) throw ApiException("bad_type", "Pick a picture, a GIF or a video")
    val limit = if (mime == "image/gif") 12 * PM_MB else if (cover) 8 * PM_MB else 5 * PM_MB
    return ProfileUpload(pmRead(ctx, uri, limit), mime, 0L)
}

/** Picker for the round profile picture: picture, GIF or video. */
@Composable
internal fun rememberProfileAvatarPicker(
    onStart: () -> Unit,
    onPicked: (ByteArray, String, Long) -> Unit,
    onError: (String) -> Unit
): () -> Unit {
    val app = LocalContext.current.applicationContext
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            onStart()
            runIo({ prepareProfileMedia(app, uri, cover = false) }) { r ->
                r.onSuccess { onPicked(it.bytes, it.type, it.durMs) }
                    .onFailure { onError(it.message ?: "Can't use this file") }
            }
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
}

/** Picker for the profile background: picture or GIF. */
@Composable
internal fun rememberCoverPicker(
    onStart: () -> Unit,
    onPicked: (ByteArray, String) -> Unit,
    onError: (String) -> Unit
): () -> Unit {
    val app = LocalContext.current.applicationContext
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            onStart()
            runIo({ prepareProfileMedia(app, uri, cover = true) }) { r ->
                r.onSuccess { onPicked(it.bytes, it.type) }
                    .onFailure { onError(it.message ?: "Can't use this picture") }
            }
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

// ---------------------------------------------------------------------------
// Playing
// ---------------------------------------------------------------------------

private fun fillCrop(v: TextureView, vw: Int, vh: Int) {
    val w = v.width.toFloat()
    val h = v.height.toFloat()
    if (vw <= 0 || vh <= 0 || w <= 0f || h <= 0f) return
    // a TextureView stretches the video to its box; this puts the true shape back and crops the overflow
    val videoAspect = vw.toFloat() / vh
    val viewAspect = w / h
    val sx = if (videoAspect > viewAspect) videoAspect / viewAspect else 1f
    val sy = if (videoAspect > viewAspect) 1f else viewAspect / videoAspect
    val m = Matrix()
    m.setScale(sx, sy, w / 2f, h / 2f)
    v.setTransform(m)
}

/** A muted video that loops forever and fills its box (crop). Clip it with the modifier for a round avatar. */
@Composable
internal fun LoopingVideo(url: String, modifier: Modifier = Modifier) {
    var ready by remember(url) { mutableStateOf(false) }
    Box(modifier) {
        AndroidView(
            factory = { c ->
                TextureView(c).apply {
                    var mp: MediaPlayer? = null
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            val p = MediaPlayer()
                            mp = p
                            try {
                                p.setDataSource(c, Uri.parse(url), mapOf("Authorization" to "Bearer ${Session.token}"))
                                p.setSurface(Surface(st))
                                p.isLooping = true
                                p.setVolume(0f, 0f)
                                p.setOnVideoSizeChangedListener { _, vw, vh -> fillCrop(this@apply, vw, vh) }
                                p.setOnPreparedListener {
                                    it.start()
                                    ready = true
                                }
                                p.setOnErrorListener { _, _, _ -> true }
                                p.prepareAsync()
                            } catch (e: Exception) {
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            try {
                                mp?.release()
                            } catch (e: Exception) {
                            }
                            mp = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            // hidden until the first frame is ready, so the letters underneath show meanwhile
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (ready) 1f else 0f)
        )
    }
}

/** Full screen look at someone's profile video, with sound, looping. Tap anywhere or the arrow to close. */
@Composable
internal fun AvatarVideoDialog(url: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onClose)
        ) {
            AndroidView(
                factory = { c ->
                    VideoView(c).apply {
                        setVideoURI(Uri.parse(url), mapOf("Authorization" to "Bearer ${Session.token}"))
                        setOnPreparedListener {
                            it.isLooping = true
                            it.start()
                        }
                        setOnErrorListener { _, _, _ ->
                            Toast.makeText(c, "Can't play this video", Toast.LENGTH_SHORT).show()
                            true
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center)
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(4.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }
    }
}

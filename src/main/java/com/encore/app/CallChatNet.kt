package com.encore.app

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Network side of in-call sharing. Plugs into [CallChat] (see CallChat.kt) using the existing Encore API:
 *  - text:   POST /v1/conversations/{cid}/messages            (client_id = item.id)
 *  - media:  POST /v1/conversations/{cid}/call-preview        (instant thumbnail, realtime only)
 *            POST /v1/conversations/{cid}/media?kind=..       (full file, client_id = item.id)
 *  - incoming: realtime "call_item" (preview) then "message" (text / full media) from the peer.
 * Installed once from CallHost.
 */
object CallChatNet : CallChatTransport {
    private var app: Context? = null
    private var listening = false
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Incoming media whose preview arrived and whose full file is still on its way, keyed by client id. */
    private val waiting = ConcurrentHashMap<String, CallItem>()

    @Volatile
    private var dmCache: Pair<String, String>? = null

    fun install(ctx: Context) {
        app = ctx.applicationContext
        CallChat.attach(this)
        if (!listening) {
            listening = true
            Rt.listeners.add({ j: JSONObject -> onEvent(j) })
        }
    }

    // ---- outgoing -------------------------------------------------------------------------

    /** The DM conversation with the person on the other end of the call. Blocking: call off the main thread. */
    private fun conversationId(): String {
        val pid = CallManager.peerId.toString()
        dmCache?.let { if (it.first == pid) return it.second }
        val c = Api.conversations().firstOrNull { it.type == "dm" && it.peer?.id == pid }
            ?: throw ApiException("no_conversation", "Chat not found")
        dmCache = pid to c.id
        return c.id
    }

    override fun sendText(item: CallItem) {
        io.execute {
            try {
                Api.send(conversationId(), item.text, item.id)
                main.post { item.markSent() }
            } catch (e: Exception) {
                main.post { item.markFailed() }
            }
        }
    }

    override fun sendMedia(item: CallItem, source: Uri) {
        val ctx = app
        if (ctx == null) {
            item.markFailed()
            return
        }
        io.execute {
            try {
                val cid = conversationId()
                val isVideo = item.kind == CallItemKind.Video
                val kind = if (isVideo) "video" else "image"

                // 1) instant preview for the other person (not stored on the server)
                item.previewJpeg()?.let { jpg ->
                    runCatching {
                        Api.call(
                            "POST", "/v1/conversations/$cid/call-preview",
                            JSONObject()
                                .put("id", item.id)
                                .put("kind", kind)
                                .put("preview", Base64.encodeToString(jpg, Base64.NO_WRAP))
                        )
                    }
                }

                // 2) the full file, streamed from disk with progress
                val cr = ctx.contentResolver
                val type = cr.getType(source) ?: if (isVideo) "video/mp4" else "image/jpeg"
                val size = sizeOf(ctx, source)
                if (size <= 0) throw ApiException("bad_file", "Can't read this file")

                val body = object : RequestBody() {
                    override fun contentType() = type.toMediaType()
                    override fun contentLength() = size
                    override fun writeTo(sink: BufferedSink) {
                        val input = cr.openInputStream(source) ?: throw IOException("Can't open file")
                        input.use { s ->
                            val buf = ByteArray(16 * 1024)
                            var sent = 0L
                            var lastTick = 0L
                            while (true) {
                                val n = s.read(buf)
                                if (n < 0) break
                                sink.write(buf, 0, n)
                                sent += n
                                val now = System.currentTimeMillis()
                                if (now - lastTick > 150) {
                                    lastTick = now
                                    val p = (sent.toFloat() / size).coerceAtMost(0.99f)
                                    main.post { item.progress = p }
                                }
                            }
                        }
                    }
                }
                val req = Request.Builder()
                    .url(API_BASE + "/v1/conversations/$cid/media?kind=$kind&client_id=" + item.id)
                    .header("Authorization", "Bearer ${Session.token}")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw ApiException("http_${r.code}", "Upload failed")
                }
                main.post { item.markSent() }
            } catch (e: Exception) {
                main.post { item.markFailed() }
            }
        }
    }

    private fun sizeOf(ctx: Context, uri: Uri): Long {
        runCatching {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { if (it.length > 0) return it.length }
        }
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val v = c.getLong(0)
                    if (v > 0) return v
                }
            }
        }
        return -1
    }

    // ---- incoming (realtime listener, main thread) ----------------------------------------

    private fun inCall() = CallManager.phase != CallPhase.Idle

    private fun onEvent(j: JSONObject) {
        val ctx = app ?: return
        when (j.optString("type")) {
            "call_item" -> {
                if (!inCall()) return
                val id = j.optString("id")
                if (id.isEmpty() || j.optString("from") == Session.user?.id) return
                val bmp = if (j.isNull("preview")) null else runCatching {
                    val b = Base64.decode(j.getString("preview"), Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(b, 0, b.size)
                }.getOrNull()
                waiting[id] = CallChat.receiveMedia(ctx, id, j.optString("kind") == "video", bmp)
            }

            "message" -> {
                val o = j.optJSONObject("message") ?: return
                val m = runCatching { Api.parseMsg(o) }.getOrNull() ?: return
                if (m.senderId == Session.user?.id || !inCall()) return
                if (m.senderId != CallManager.peerId.toString()) return
                when (m.kind) {
                    "text" -> if (!m.body.isNullOrEmpty()) CallChat.receiveText(ctx, m.id, m.body.orEmpty())
                    "image", "video" -> {
                        val item = waiting.remove(m.clientId ?: m.id)
                            ?: CallChat.receiveMedia(ctx, m.id, m.kind == "video", null)
                        download(ctx, item, m.conversationId, m.id, m.kind)
                    }
                }
            }
        }
    }

    private fun download(ctx: Context, item: CallItem, cid: String, mid: String, kind: String) {
        io.execute {
            try {
                val req = Request.Builder()
                    .url("$API_BASE/v1/media/$cid/$mid")
                    .header("Authorization", "Bearer ${Session.token}")
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw ApiException("http_${r.code}", "Download failed")
                    val body = r.body ?: throw IOException("Empty response")
                    val total = body.contentLength()
                    val dir = File(ctx.cacheDir, "call_media").apply { mkdirs() }
                    val file = File(dir, mid + if (kind == "video") ".mp4" else ".jpg")
                    body.byteStream().use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(16 * 1024)
                            var got = 0L
                            var lastTick = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                val now = System.currentTimeMillis()
                                if (total > 0 && now - lastTick > 150) {
                                    lastTick = now
                                    val p = (got.toFloat() / total).coerceAtMost(0.99f)
                                    main.post { item.progress = p }
                                }
                            }
                        }
                    }
                    main.post { item.markSent(Uri.fromFile(file)) }
                }
            } catch (e: Exception) {
                main.post { item.markFailed() }
            }
        }
    }
}

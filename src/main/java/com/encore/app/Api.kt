package com.encore.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

const val API_BASE = "https://encore-api.jahidhasanofficial397.workers.dev"

class ApiException(val code: String, message: String) : Exception(message)

data class User(val id: String, val username: String, val displayName: String)
data class Peer(val id: String, val username: String, val displayName: String, val lastSeen: Long?)
data class LastMsg(val senderId: String, val kind: String, val preview: String?, val createdAt: Long, val deleted: Boolean)
data class Conv(
    val id: String,
    val peer: Peer?,
    val last: LastMsg?,
    val unread: Int,
    val myRead: String,
    val peerRead: String,
    val updatedAt: Long
)
data class Msg(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val kind: String,
    val body: String?,
    val createdAt: Long,
    val deleted: Boolean,
    val clientId: String?
)

private fun JSONObject.s(key: String): String? = if (isNull(key)) null else getString(key)

object Session {
    private const val FILE = "encore_session"
    var token: String? = null
        private set
    var user: User? = null
        private set

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        token = p.getString("token", null)
        val id = p.getString("uid", null)
        user = if (token != null && id != null) {
            User(id, p.getString("uname", "") ?: "", p.getString("dname", "") ?: "")
        } else null
    }

    fun save(ctx: Context, t: String, u: User) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("token", t).putString("uid", u.id)
            .putString("uname", u.username).putString("dname", u.displayName).apply()
        token = t
        user = u
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
        token = null
        user = null
    }
}

object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

        internal fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true,
        token: String? = Session.token
    ): JSONObject {
        val b = Request.Builder().url(API_BASE + path)
        if (auth && token != null) b.header("Authorization", "Bearer $token")
        if (method == "GET") b.get() else b.method(method, (body ?: JSONObject()).toString().toRequestBody(JSON))
        val result: JSONObject = try {
            client.newCall(b.build()).execute().use { r ->
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
                        err?.optString("message") ?: "Server error ${r.code}"
                    )
                }
                j
            }
        } catch (e: java.io.IOException) {
            throw ApiException("network", "Can't reach the server. Check your connection.")
        }
        return result
    }

    private fun user(o: JSONObject) = User(o.getString("id"), o.getString("username"), o.optString("display_name", o.getString("username")))

    fun parseMsg(o: JSONObject) = Msg(
        o.getString("id"), o.getString("conversation_id"), o.getString("sender_id"), o.getString("kind"),
        o.s("body"), o.getLong("created_at"), o.optBoolean("deleted"), o.s("client_id")
    )

    private fun conv(o: JSONObject): Conv {
        val p = o.optJSONObject("peer")
        val lm = o.optJSONObject("last_message")
        return Conv(
            id = o.getString("id"),
            peer = p?.let {
                Peer(
                    it.getString("id"), it.getString("username"),
                    it.optString("display_name", it.getString("username")),
                    it.optLong("last_seen", 0L).takeIf { t -> t > 0 }
                )
            },
            last = lm?.let {
                LastMsg(it.getString("sender_id"), it.getString("kind"), it.s("preview"), it.getLong("created_at"), it.optBoolean("deleted"))
            },
            unread = o.optInt("unread"),
            myRead = o.s("my_read") ?: "",
            peerRead = o.s("peer_read") ?: "",
            updatedAt = o.optLong("updated_at")
        )
    }

    fun auth(path: String, username: String, password: String): Pair<String, User> {
        val body = JSONObject().put("username", username).put("password", password).put("device", android.os.Build.MODEL)
        val j = call("POST", path, body, auth = false)
        return j.getString("token") to user(j.getJSONObject("user"))
    }

    fun logout(token: String) {
        call("POST", "/v1/auth/logout", token = token)
    }

    fun conversations(): List<Conv> {
        val arr = call("GET", "/v1/conversations").getJSONArray("conversations")
        return List(arr.length()) { conv(arr.getJSONObject(it)) }
    }

    fun startDm(username: String): Conv {
        val c = call("POST", "/v1/conversations/dm", JSONObject().put("username", username)).getJSONObject("conversation")
        val p = c.getJSONObject("peer")
        val peer = Peer(p.getString("id"), p.getString("username"), p.optString("display_name", p.getString("username")), null)
        return Conv(c.getString("id"), peer, null, 0, "", "", System.currentTimeMillis())
    }

    fun messages(cid: String): List<Msg> {
        val arr = call("GET", "/v1/conversations/$cid/messages?limit=100").getJSONArray("messages")
        return List(arr.length()) { parseMsg(arr.getJSONObject(it)) }
    }

    fun send(cid: String, body: String, clientId: String): Msg {
        val j = call("POST", "/v1/conversations/$cid/messages", JSONObject().put("body", body).put("client_id", clientId))
        return parseMsg(j.getJSONObject("message"))
    }

    fun markRead(cid: String, upTo: String) {
        call("POST", "/v1/conversations/$cid/read", JSONObject().put("up_to", upTo))
    }

    fun presence(uid: String): Pair<Boolean, Long?> {
        val j = call("GET", "/v1/presence/$uid")
        return j.optBoolean("online") to j.optLong("last_seen", 0L).takeIf { it > 0 }
    }
}

object Rt {
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private val handler = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var wanted = false
    private var attempt = 0
    val listeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()

    fun start() {
        if (wanted) return
        wanted = true
        attempt = 0
        connect()
    }

    fun stop() {
        wanted = false
        handler.removeCallbacksAndMessages(null)
        socket?.close(1000, "bye")
        socket = null
    }

    fun send(o: JSONObject) {
        socket?.send(o.toString())
    }

    private fun connect() {
        val token = Session.token ?: return
        val url = API_BASE.replaceFirst("https://", "wss://") + "/v1/ws?token=" + token
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    return
                }
                handler.post { listeners.forEach { l -> l(j) } }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                retry()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                retry()
            }
        })
    }

    private fun retry() {
        if (!wanted) return
        attempt++
        val wait = minOf(30000L, 1000L * (1 shl minOf(attempt, 5)))
        handler.postDelayed({ if (wanted) connect() }, wait)
    }
}

@Composable
fun OnRealtime(onEvent: (JSONObject) -> Unit) {
    val latest by rememberUpdatedState(onEvent)
    DisposableEffect(Unit) {
        val l: (JSONObject) -> Unit = { latest(it) }
        Rt.listeners.add(l)
        onDispose { Rt.listeners.remove(l) }
    }
}

package com.encore.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

const val API_BASE = "https://encore-api.jahidhasanofficial397.workers.dev"

class ApiException(val code: String, message: String) : Exception(message)

data class User(val id: String, val username: String, val displayName: String)
data class Peer(
    val id: String,
    val username: String,
    val displayName: String,
    val lastSeen: Long?,
    val avatarKey: String? = null
)
data class LastMsg(val senderId: String, val kind: String, val preview: String?, val createdAt: Long, val deleted: Boolean)
data class Conv(
    val id: String,
    val peer: Peer?,
    val last: LastMsg?,
    val unread: Int,
    val myRead: String,
    val peerRead: String,
    val updatedAt: Long,
    val type: String = "dm",
    val memberCount: Int = 0,
    val muted: Boolean = false
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
data class ProfileInfo(val user: User, val bio: String?, val avatarKey: String?, val blocked: Boolean = false)
data class Member(val id: String, val username: String, val displayName: String, val role: String)

private fun JSONObject.s(key: String): String? = if (isNull(key)) null else getString(key)

object Avatars {
    private val map = mutableStateMapOf<String, String>()
    private val main = Handler(Looper.getMainLooper())

    fun url(seed: String): String? = map[seed]

    fun put(seed: String, kind: String, key: String?) {
        main.post {
            if (key == null) map.remove(seed) else map[seed] = "$API_BASE/v1/avatar/$kind/$seed?v=$key"
        }
    }
}

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

    private fun execute(req: Request): JSONObject {
        val result: JSONObject = try {
            client.newCall(req).execute().use { r ->
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
        return execute(b.build())
    }

    internal fun postBytes(path: String, bytes: ByteArray, type: String): JSONObject {
        val req = Request.Builder()
            .url(API_BASE + path)
            .header("Authorization", "Bearer ${Session.token}")
            .post(bytes.toRequestBody(type.toMediaType()))
            .build()
        return execute(req)
    }

    private fun user(o: JSONObject) =
        User(o.getString("id"), o.getString("username"), o.optString("display_name", o.getString("username")))

    fun parseMsg(o: JSONObject) = Msg(
        o.getString("id"), o.getString("conversation_id"), o.getString("sender_id"), o.getString("kind"),
        o.s("body"), o.getLong("created_at"), o.optBoolean("deleted"), o.s("client_id")
    )

    private fun peerOf(it: JSONObject): Peer {
        val key = it.s("avatar_key")
        Avatars.put(it.getString("id"), "u", key)
        return Peer(
            it.getString("id"), it.getString("username"),
            it.optString("display_name", it.getString("username")),
            it.optLong("last_seen", 0L).takeIf { t -> t > 0 },
            key
        )
    }

    private fun conv(o: JSONObject): Conv {
        val type = o.optString("type", "dm")
        val id = o.getString("id")
        val lm = o.optJSONObject("last_message")
        val peer: Peer? = if (type == "group") {
            val key = o.s("avatar_key")
            Avatars.put(id, "g", key)
            Peer(id, "", o.s("title") ?: "Group", null, key)
        } else {
            o.optJSONObject("peer")?.let { peerOf(it) }
        }
        return Conv(
            id = id,
            peer = peer,
            last = lm?.let {
                LastMsg(it.getString("sender_id"), it.getString("kind"), it.s("preview"), it.getLong("created_at"), it.optBoolean("deleted"))
            },
            unread = o.optInt("unread"),
            myRead = o.s("my_read") ?: "",
            peerRead = o.s("peer_read") ?: "",
            updatedAt = o.optLong("updated_at"),
            type = type,
            memberCount = o.optInt("member_count"),
            muted = o.optBoolean("muted")
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
        return Conv(c.getString("id"), peerOf(c.getJSONObject("peer")), null, 0, "", "", System.currentTimeMillis())
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

    /* ---------- profile ---------- */

    private fun profileOf(u: JSONObject): ProfileInfo {
        val key = u.s("avatar_key")
        Avatars.put(u.getString("id"), "u", key)
        return ProfileInfo(user(u), u.s("bio"), key, u.optBoolean("blocked"))
    }

    fun me(): ProfileInfo = profileOf(call("GET", "/v1/me").getJSONObject("user"))

    fun updateMe(name: String, bio: String): ProfileInfo =
        profileOf(call("PATCH", "/v1/me", JSONObject().put("display_name", name).put("bio", bio)).getJSONObject("user"))

    fun setAvatar(bytes: ByteArray, type: String): ProfileInfo =
        profileOf(postBytes("/v1/me/avatar", bytes, type).getJSONObject("user"))

    fun changePassword(old: String, new: String) {
        call("POST", "/v1/auth/password", JSONObject().put("old_password", old).put("new_password", new))
    }

    fun userProfile(uid: String): ProfileInfo = profileOf(call("GET", "/v1/users/$uid").getJSONObject("user"))

    fun block(uid: String, on: Boolean) {
        call(if (on) "PUT" else "DELETE", "/v1/blocks/$uid")
    }

    fun report(type: String, targetId: String, reason: String) {
        call("POST", "/v1/reports", JSONObject().put("target_type", type).put("target_id", targetId).put("reason", reason))
    }

    fun mute(cid: String, on: Boolean) {
        call(if (on) "PUT" else "DELETE", "/v1/conversations/$cid/mute")
    }

    /* ---------- groups ---------- */

    fun members(cid: String): List<Member> {
        val arr = call("GET", "/v1/conversations/$cid/members").getJSONArray("members")
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Avatars.put(o.getString("id"), "u", o.s("avatar_key"))
            Member(o.getString("id"), o.getString("username"), o.optString("display_name", o.getString("username")), o.optString("role", "member"))
        }
    }

    fun createGroup(title: String, usernames: List<String>): Conv {
        val body = JSONObject().put("title", title).put("usernames", JSONArray(usernames))
        val c = call("POST", "/v1/groups", body).getJSONObject("conversation")
        val id = c.getString("id")
        return Conv(
            id, Peer(id, "", c.optString("title", title), null, null), null, 0, "", "",
            System.currentTimeMillis(), "group", c.optInt("member_count")
        )
    }

    fun addMembers(cid: String, usernames: List<String>) {
        call("POST", "/v1/groups/$cid/members", JSONObject().put("usernames", JSONArray(usernames)))
    }

    fun removeMember(cid: String, uid: String) {
        call("DELETE", "/v1/groups/$cid/members/$uid")
    }

    fun renameGroup(cid: String, title: String) {
        call("PATCH", "/v1/groups/$cid", JSONObject().put("title", title))
    }

    fun setGroupAvatar(cid: String, bytes: ByteArray, type: String) {
        val key = postBytes("/v1/groups/$cid/avatar", bytes, type).getString("avatar_key")
        Avatars.put(cid, "g", key)
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

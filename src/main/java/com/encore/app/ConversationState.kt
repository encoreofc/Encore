package com.encore.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

private val stateHandler = Handler(Looper.getMainLooper())

class ConversationState(
    val me: User,
    val conv: Conv,
    private val appCtx: Context,
    private val onLogout: () -> Unit
) {
    var items by mutableStateOf<List<MsgItem>>(emptyList())
    var pending by mutableStateOf<List<MsgItem>>(emptyList())
    var progress by mutableStateOf<Map<String, Float>>(emptyMap())
    var reacts by mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap())
    var text by mutableStateOf("")
    var problem by mutableStateOf<String?>(null)
    var peerRead by mutableStateOf(conv.peerRead)
    var typingAt by mutableStateOf(0L)
    var online by mutableStateOf<Boolean?>(null)
    var lastSeen by mutableStateOf<Long?>(conv.peer?.lastSeen)
    var replyTo by mutableStateOf<MsgItem?>(null)
    var editing by mutableStateOf<MsgItem?>(null)

    private var marked = conv.myRead
    private var opening = false
    private var typedAt = 0L

    fun load() {
        runIo({ Features.sync(conv.id) }) { r ->
            r.onSuccess { s ->
                items = s.items
                reacts = s.reactions
            }.onFailure {
                if ((it as? ApiException)?.code == "unauthorized") onLogout()
            }
        }
    }

    fun refreshPresence() {
        val peer = conv.peer ?: return
        runIo({ Api.presence(peer.id) }) { r ->
            r.onSuccess {
                online = it.first
                if (it.second != null) lastSeen = it.second
            }
        }
    }

    fun markReadIfNeeded() {
        val l = items.lastOrNull()?.msg ?: return
        if (l.senderId != me.id && l.id > marked) {
            marked = l.id
            runIo({ Api.markRead(conv.id, l.id) }) { }
        }
    }

    fun onTextChange(value: String) {
        text = value
        problem = null
        val now = System.currentTimeMillis()
        if (editing == null && value.isNotBlank() && now - typedAt > 3000L) {
            typedAt = now
            Rt.send(JSONObject().put("type", "typing").put("c", conv.id).put("s", true))
        }
    }

    fun startReply(x: MsgItem) {
        editing = null
        replyTo = x
    }

    fun startEdit(x: MsgItem) {
        replyTo = null
        editing = x
        text = x.msg.body ?: ""
    }

    fun cancelEdit() {
        editing = null
        text = ""
    }

    fun send() {
        val body = text.trim()
        if (body.isEmpty()) return
        val ed = editing
        if (ed != null) {
            editing = null
            text = ""
            runIo({ Features.edit(ed.msg.id, body) }) { r ->
                r.onSuccess {
                    items = items.map { x ->
                        if (x.msg.id == ed.msg.id) x.copy(msg = x.msg.copy(body = body), editedAt = System.currentTimeMillis()) else x
                    }
                }.onFailure { problem = it.message }
            }
            return
        }
        val rep = replyTo
        val cid = java.util.UUID.randomUUID().toString()
        val local = MsgItem(
            Msg("local-$cid", conv.id, me.id, "text", body, System.currentTimeMillis(), false, cid),
            null, null, rep?.msg?.id, null
        )
        pending = pending + local
        text = ""
        replyTo = null
        typedAt = 0L
        Rt.send(JSONObject().put("type", "typing").put("c", conv.id).put("s", false))
        runIo({ Features.send(conv.id, body, cid, rep?.msg?.id) }) { r ->
            pending = pending.filter { it.msg.clientId != cid }
            r.onSuccess { m -> items = addItem(items, m) }.onFailure {
                problem = it.message
                if (text.isBlank()) text = body
            }
        }
    }

    fun sendMedia(uri: Uri, wanted: String, caption: String = "", ttl: Int = 0) {
        val picked = try {
            Uploads.inspect(appCtx, uri, wanted)
        } catch (e: Exception) {
            problem = e.message ?: "Can't read this file"
            return
        }
        val cid = java.util.UUID.randomUUID().toString()
        val lid = "local-$cid"
        val rep = replyTo
        val local = MsgItem(
            Msg(lid, conv.id, me.id, picked.kind, caption.ifBlank { null }, System.currentTimeMillis(), false, cid),
            null, null, rep?.msg?.id, null,
            Media(picked.name, picked.size, picked.type, picked.w, picked.h, 0L, uri.toString()),
            ttl = ttl
        )
        pending = pending + local
        progress = progress + (lid to 0f)
        replyTo = null
        problem = null
        runIo({
            Uploads.upload(appCtx, conv.id, picked, cid, rep?.msg?.id, ttl, caption) { p -> progress = progress + (lid to p) }
        }) { r ->
            pending = pending.filter { it.msg.clientId != cid }
            progress = progress - lid
            r.onSuccess { m -> items = addItem(items, m) }.onFailure { problem = it.message }
        }
    }

    fun sendEdited(m: EditedMedia) =
        sendMedia(m.uri, if (m.isVideo) "video" else "image", m.caption, m.ttl)

    /** Opens a view-once / timed message: the server records the open, then the viewer is shown. */
    fun openOnce(x: MsgItem, onReady: (OnceSession) -> Unit) {
        if (opening) return
        opening = true
        runIo({ Features.open(x.msg.id) }) { r ->
            opening = false
            r.onSuccess { res ->
                val at = res.first
                val remaining = res.second
                items = items.map { y -> if (y.msg.id == x.msg.id) y.copy(openedAt = at) else y }
                onReady(OnceSession(x, remaining?.let { System.currentTimeMillis() + it }))
            }.onFailure {
                problem = it.message
                load()
            }
        }
    }

    fun react(id: String, emoji: String) {
        val mine = reacts[id]?.firstOrNull { it.first == me.id }?.second
        reacts = setReaction(reacts, id, me.id, if (mine == emoji) null else emoji)
        runIo({ Features.react(id, emoji) }) { r ->
            r.onFailure {
                problem = it.message
                load()
            }
        }
    }

    fun delete(x: MsgItem) {
        runIo({ Features.delete(x.msg.id) }) { r ->
            r.onSuccess {
                items = items.map { y ->
                    if (y.msg.id == x.msg.id) y.copy(msg = y.msg.copy(deleted = true, body = null)) else y
                }
                reacts = reacts - x.msg.id
            }.onFailure { problem = it.message }
        }
    }

    fun forward(x: MsgItem, target: Conv) {
        runIo({ Features.forward(x.msg.id, target.id) }) { r ->
            r.onSuccess {
                Toast.makeText(appCtx, "Forwarded", Toast.LENGTH_SHORT).show()
            }.onFailure { problem = it.message }
        }
    }

    fun onEvent(j: JSONObject) {
        when (j.optString("type")) {
            "hello" -> load()
            "message" -> {
                val x = parseItem(j.getJSONObject("message"))
                if (x.msg.conversationId == conv.id) {
                    pending = pending.filter { it.msg.clientId != x.msg.clientId }
                    items = addItem(items, x)
                    if (x.msg.senderId != me.id) typingAt = 0L
                }
            }
            "read" -> {
                val up = j.optString("up_to")
                if (j.optString("c") == conv.id && j.optString("u") != me.id && up > peerRead) peerRead = up
            }
            "typing" -> {
                if (j.optString("c") == conv.id && j.optString("u") != me.id) {
                    if (j.optBoolean("s", true)) {
                        typingAt = System.currentTimeMillis()
                        stateHandler.postDelayed({
                            if (System.currentTimeMillis() - typingAt >= 5000L) typingAt = 0L
                        }, 5100L)
                    } else {
                        typingAt = 0L
                    }
                }
            }
            "reaction" -> {
                if (j.optString("c") == conv.id) {
                    val e = if (j.isNull("e")) null else j.optString("e")
                    reacts = setReaction(reacts, j.optString("m"), j.optString("u"), e)
                }
            }
            "delete" -> {
                if (j.optString("c") == conv.id) {
                    val id = j.optString("id")
                    items = items.map { y ->
                        if (y.msg.id == id) y.copy(msg = y.msg.copy(deleted = true, body = null)) else y
                    }
                    reacts = reacts - id
                }
            }
            "edit" -> {
                if (j.optString("c") == conv.id) {
                    val id = j.optString("id")
                    val body = j.optString("body")
                    val at = j.optLong("edited_at", 0L)
                    items = items.map { y ->
                        if (y.msg.id == id) y.copy(msg = y.msg.copy(body = body), editedAt = at) else y
                    }
                }
            }
            "opened" -> {
                if (j.optString("c") == conv.id) {
                    val id = j.optString("m")
                    val by = j.optString("u")
                    val at = j.optLong("at", System.currentTimeMillis())
                    items = items.map { y ->
                        if (y.msg.id == id && y.openedAt == null && (by == me.id || y.msg.senderId == me.id)) y.copy(openedAt = at) else y
                    }
                }
            }
            else -> {}
        }
    }
}

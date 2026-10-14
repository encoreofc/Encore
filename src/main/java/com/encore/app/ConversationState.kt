package com.encore.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val stateHandler = Handler(Looper.getMainLooper())

/**
 * The chat screen reads its messages from the local database (Room), so it opens instantly and works offline.
 * Sync (see Sync.kt) keeps the database up to date in the background; sending goes through the Outbox.
 * Call close() when the chat screen leaves the composition.
 */
class ConversationState(
    val me: User,
    val conv: Conv,
    private val appCtx: Context,
    private val onLogout: () -> Unit
) {
    var items by mutableStateOf<List<MsgItem>>(emptyList())

    /** kept so existing screens compile; unsent messages now live in the database as "local-..." rows inside [items] */
    var pending by mutableStateOf<List<MsgItem>>(emptyList())

    /** upload progress by local message id */
    val progress: Map<String, Float> get() = Outbox.progress

    var reacts by mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap())
    var text by mutableStateOf("")
    var problem by mutableStateOf<String?>(null)
    var peerRead by mutableStateOf(conv.peerRead)
    var typingAt by mutableStateOf(0L)
    var online by mutableStateOf<Boolean?>(null)
    var lastSeen by mutableStateOf<Long?>(conv.peer?.lastSeen)
    var replyTo by mutableStateOf<MsgItem?>(null)
    var editing by mutableStateOf<MsgItem?>(null)

    private val dao = Db.dao(appCtx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val window = MutableStateFlow(100)
    private var started = false
    private var exhausted = false
    private var marked = conv.myRead
    private var opening = false
    private var typedAt = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    fun load() {
        if (!started) {
            started = true
            Outbox.onProblem = { msg -> stateHandler.post { problem = msg } }
            scope.launch {
                window
                    .flatMapLatest { dao.observeMessages(conv.id, it) }
                    .map { list -> list.map { it.toItem() } }
                    .flowOn(Dispatchers.Default)
                    .collect { items = it }
            }
            scope.launch {
                dao.observeReactions(conv.id)
                    .map { list -> list.groupBy({ it.messageId }, { it.userId to it.emoji }) }
                    .flowOn(Dispatchers.Default)
                    .collect { reacts = it }
            }
        }
        // catch up with the server in the background; what is already on the phone is shown immediately
        scope.launch(Dispatchers.IO) {
            try {
                Sync.openConversation(conv.id)
            } catch (e: ApiException) {
                if (e.code == "unauthorized") withContext(Dispatchers.Main) { onLogout() }
            }
        }
    }

    /** call when the user scrolls to the top: shows 100 more messages, fetching them from the server if needed */
    fun loadOlder() {
        window.value = window.value + 100
        if (exhausted) return
        scope.launch(Dispatchers.IO) {
            try {
                if (!Sync.loadOlder(conv.id)) exhausted = true
            } catch (e: ApiException) {
            }
        }
    }

    fun close() {
        if (Outbox.onProblem != null) Outbox.onProblem = null
        scope.cancel()
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
        val l = items.lastOrNull { !it.msg.id.startsWith("local-") }?.msg ?: return
        if (l.senderId != me.id && l.id > marked) {
            marked = l.id
            scope.launch(Dispatchers.IO) { dao.markRead(conv.id, l.id) }
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
            val old = ed.msg.body ?: ""
            val oldAt = ed.editedAt
            scope.launch(Dispatchers.IO) { dao.edit(ed.msg.id, body, System.currentTimeMillis()) }
            runIo({ Features.edit(ed.msg.id, body) }) { r ->
                r.onFailure {
                    problem = it.message
                    scope.launch(Dispatchers.IO) { dao.edit(ed.msg.id, old, oldAt) }
                }
            }
            return
        }
        val rep = replyTo
        text = ""
        replyTo = null
        typedAt = 0L
        Rt.send(JSONObject().put("type", "typing").put("c", conv.id).put("s", false))
        scope.launch(Dispatchers.IO) { Outbox.enqueueText(conv.id, me, body, rep) }
    }

    fun sendMedia(uri: Uri, wanted: String, caption: String = "", ttl: Int = 0) {
        val rep = replyTo
        replyTo = null
        problem = null
        scope.launch(Dispatchers.IO) {
            try {
                Outbox.enqueueMedia(appCtx, conv.id, me, uri, wanted, caption, ttl, rep)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { problem = e.message ?: "Can't read this file" }
            }
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
                scope.launch(Dispatchers.IO) { dao.markOpened(x.msg.id, at) }
                onReady(OnceSession(x, remaining?.let { System.currentTimeMillis() + it }))
            }.onFailure {
                problem = it.message
                Sync.kick()
            }
        }
    }

    fun react(id: String, emoji: String) {
        val mine = reacts[id]?.firstOrNull { it.first == me.id }?.second
        scope.launch(Dispatchers.IO) {
            if (mine == emoji) dao.deleteReaction(id, me.id) else dao.putReaction(ReactionEntity(id, me.id, emoji))
        }
        runIo({ Features.react(id, emoji) }) { r ->
            r.onFailure {
                problem = it.message
                Sync.kick()
            }
        }
    }

    fun delete(x: MsgItem) {
        runIo({ Features.delete(x.msg.id) }) { r ->
            r.onSuccess {
                scope.launch(Dispatchers.IO) { Sync.deleteLocally(x.msg.id) }
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

    /**
     * Messages, edits, deletes, reactions and "opened" are written to the database by Sync for every chat,
     * so only the small live things that are not stored are handled here.
     */
    fun onEvent(j: JSONObject) {
        when (j.optString("type")) {
            "message" -> {
                val o = j.optJSONObject("message")
                if (o != null && o.optString("conversation_id") == conv.id && o.optString("sender_id") != me.id) typingAt = 0L
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
            else -> {}
        }
    }
}

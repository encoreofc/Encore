package com.encore.app

import android.content.Context
import androidx.room.withTransaction
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/**
 * Keeps the local database (Room) in step with the server, the way modern chat apps do:
 *  - the UI only reads the database
 *  - on start / reconnect only what changed since the last cursor is downloaded (text, edits, deletes, reactions)
 *  - realtime events are written straight into the database, for every chat, not just the open one
 */
object Sync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var attached = false

    private const val HISTORY = 50
    private const val BOOT_CHATS = 30

    private val ctx: Context get() = EncoreApp.instance
    private val dao: AppDao get() = Db.dao(ctx)

    /** call once after login (and on every app start while logged in) */
    fun start() {
        attach()
        kick()
        Outbox.kick()
    }

    private fun attach() {
        if (attached) return
        attached = true
        Rt.listeners.add { j -> scope.launch { runCatching { onEvent(j) } } }
    }

    /** sync now (push notification arrived, app came to the foreground, ...) */
    fun kick() {
        scope.launch { run() }
    }

    /** logout: forget everything that belongs to the account */
    fun wipe() {
        scope.launch {
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork("prefetch")
            wm.cancelUniqueWork("outbox")
            runCatching { Db.get(ctx).clearAllTables() }
            runCatching { File(ctx.filesDir, "media").deleteRecursively() }
            runCatching { File(ctx.filesDir, "outbox").deleteRecursively() }
            Outbox.reset()
        }
    }

    fun convFlow(): Flow<List<Conv>> = dao.observeConvs().map { list ->
        list.map { e ->
            val c = e.toConv()
            // avatars are cached in memory by Api; fill them from the database so they show before the network answers
            if (c.type == "group") Avatars.put(c.id, "g", e.avatarKey)
            else if (c.peer != null) Avatars.put(c.peer.id, "u", e.peerAvatar)
            c
        }
    }

    private suspend fun cursor(): Long? = dao.meta("cursor")?.toLongOrNull()

    private suspend fun run() = mutex.withLock {
        try {
            if (Session.token == null) return@withLock
            if (cursor() == null) bootstrap() else delta()
        } catch (e: ApiException) {
            // network / server errors: try again on the next trigger
        } catch (e: java.io.IOException) {
        }
    }

    /** first run on this device: take a cursor first, then load the chat list and the latest messages of the busiest chats */
    private suspend fun bootstrap() {
        val first = Api.call("GET", "/v1/updates")
        val cur = first.getLong("cursor")
        refreshConvs()
        val ids = dao.convIds().take(BOOT_CHATS)
        for (group in ids.chunked(3)) {
            coroutineScope {
                group.map { cid -> async { runCatching { fetchHistory(cid, null, HISTORY) } } }.awaitAll()
            }
        }
        dao.putMeta(MetaEntity("cursor", cur.toString()))
        Prefetch.kick()
    }

    /** every later run: only what changed */
    private suspend fun delta() {
        var since = cursor() ?: return bootstrap()
        var changed = false
        while (true) {
            val j = Api.call("GET", "/v1/updates?since=$since")
            val arr = j.getJSONArray("messages")
            if (arr.length() > 0) {
                val items = List(arr.length()) { parseItem(arr.getJSONObject(it)) }
                applyItems(items, parseReactions(j.getJSONObject("reactions")))
                changed = true
            }
            var next = j.getLong("cursor")
            val more = j.optBoolean("has_more")
            if (more && next <= since) next = since + 1
            since = next
            dao.putMeta(MetaEntity("cursor", since.toString()))
            if (!more) break
        }
        refreshConvs()
        if (changed) Prefetch.kick()
    }

    suspend fun refreshConvs() {
        val list = Api.conversations()
        dao.putConvs(list.map { it.toEntity() })
        dao.pruneConvs(list.map { it.id })
    }

    /** returns true when the server has older messages than the ones it just sent */
    private suspend fun fetchHistory(cid: String, before: String?, limit: Int): Boolean {
        val q = "/v1/conversations/$cid/sync?limit=$limit" + (if (before != null) "&before=$before" else "")
        val j = Api.call("GET", q)
        val arr = j.getJSONArray("messages")
        val items = List(arr.length()) { parseItem(arr.getJSONObject(it)) }
        applyItems(items, parseReactions(j.getJSONObject("reactions")))
        return j.optBoolean("has_more")
    }

    /** a chat was opened: make sure it has history, then catch up */
    suspend fun openConversation(cid: String) {
        if (dao.count(cid) < 30) runCatching { fetchHistory(cid, null, 100) }
        run()
    }

    /** the user scrolled past the oldest local message: returns true when there may be more to load */
    suspend fun loadOlder(cid: String): Boolean {
        val oldest = dao.oldestId(cid) ?: return false
        return fetchHistory(cid, oldest, 100)
    }

    private fun parseReactions(rx: JSONObject): Map<String, List<Pair<String, String>>> {
        val map = HashMap<String, List<Pair<String, String>>>()
        for (k in rx.keys()) {
            val a = rx.getJSONArray(k)
            map[k] = List(a.length()) { i ->
                val o = a.getJSONObject(i)
                o.getString("u") to o.getString("e")
            }
        }
        return map
    }

    /**
     * Writes server messages into the database. Keeps the downloaded file path of rows that already exist,
     * replaces the "local-" copy of a message we sent, and replaces the reactions of every message in the batch
     * when `rx` is given (it lists the complete current set per message).
     */
    suspend fun applyItems(items: List<MsgItem>, rx: Map<String, List<Pair<String, String>>>?) {
        if (items.isEmpty()) return
        val me = Session.user?.id
        val gone = ArrayList<String>()
        Db.get(ctx).withTransaction {
            val keep = dao.keepers(items.map { it.msg.id }).associateBy { it.id }
            val rows = items.map { x ->
                var e = x.toEntity()
                val k = keep[x.msg.id]
                if (k != null) e = e.copy(localPath = k.localPath, dlFailed = k.dlFailed)
                val p = e.localPath
                if (e.deleted && p != null) {
                    if (p.startsWith("/")) gone.add(p)
                    e = e.copy(localPath = null)
                }
                e
            }
            dao.putMessages(rows)
            for (x in items) {
                val cid = x.msg.clientId
                if (cid != null && x.msg.senderId == me) dao.deleteLocalByClient(x.msg.conversationId, cid)
            }
            if (rx != null) {
                val ids = items.map { it.msg.id }
                dao.clearReactions(ids)
                dao.putReactions(ids.flatMap { id -> (rx[id] ?: emptyList()).map { ReactionEntity(id, it.first, it.second) } })
            }
        }
        gone.forEach { runCatching { File(it).delete() } }
    }

    suspend fun deleteLocally(id: String) {
        val p = dao.pathOf(id)
        dao.markDeleted(id)
        dao.clearReactions(listOf(id))
        if (p != null && p.startsWith("/")) runCatching { File(p).delete() }
    }

    private suspend fun onEvent(j: JSONObject) {
        val me = Session.user?.id ?: return
        when (j.optString("type")) {
            "hello" -> run()
            "message" -> {
                val item = parseItem(j.getJSONObject("message"))
                applyItems(listOf(item), null)
                val m = item.msg
                if (dao.getConv(m.conversationId) == null) {
                    runCatching { refreshConvs() }
                } else {
                    dao.touchConv(
                        m.conversationId, m.senderId, m.kind,
                        if (m.deleted || item.ttl != 0) null else if (m.kind == "sticker") "Sticker" else m.body,
                        m.createdAt, m.deleted,
                        if (m.senderId != me && m.kind != "system") 1 else 0
                    )
                }
                Prefetch.kick()
            }
            "delete" -> deleteLocally(j.optString("id"))
            "edit" -> dao.edit(j.optString("id"), j.optString("body"), j.optLong("edited_at", 0L).takeIf { it > 0 })
            "reaction" -> {
                val mid = j.optString("m")
                val uid = j.optString("u")
                if (j.isNull("e")) dao.deleteReaction(mid, uid)
                else dao.putReaction(ReactionEntity(mid, uid, j.optString("e")))
            }
            "opened" -> dao.markOpenedBy(j.optString("m"), j.optLong("at", System.currentTimeMillis()), j.optString("u"), me)
            "read" -> {
                if (j.optString("u") != me) dao.setPeerRead(j.optString("c"), j.optString("up_to"))
            }
        }
    }
}

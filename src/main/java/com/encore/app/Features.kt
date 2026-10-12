package com.encore.app

import org.json.JSONObject

data class ReplyInfo(val id: String, val senderId: String?, val body: String?, val deleted: Boolean)

data class MsgItem(
    val msg: Msg,
    val editedAt: Long?,
    val fwdFrom: String?,
    val replyTo: String?,
    val reply: ReplyInfo?
)

data class Synced(val items: List<MsgItem>, val reactions: Map<String, List<Pair<String, String>>>)

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else getString(key)

fun parseItem(o: JSONObject): MsgItem {
    val r = o.optJSONObject("reply")
    return MsgItem(
        msg = Api.parseMsg(o),
        editedAt = o.optLong("edited_at", 0L).takeIf { it > 0 },
        fwdFrom = o.str("fwd_from"),
        replyTo = o.str("reply_to"),
        reply = r?.let { ReplyInfo(it.getString("id"), it.str("sender_id"), it.str("body"), it.optBoolean("deleted")) }
    )
}

object Features {
    fun sync(cid: String): Synced {
        val j = Api.call("GET", "/v1/conversations/$cid/sync?limit=100")
        val arr = j.getJSONArray("messages")
        val items = List(arr.length()) { parseItem(arr.getJSONObject(it)) }
        val rx = j.getJSONObject("reactions")
        val map = HashMap<String, List<Pair<String, String>>>()
        for (k in rx.keys()) {
            val a = rx.getJSONArray(k)
            map[k] = List(a.length()) { i ->
                val o = a.getJSONObject(i)
                o.getString("u") to o.getString("e")
            }
        }
        return Synced(items, map)
    }

    fun send(cid: String, body: String, clientId: String, replyTo: String?): MsgItem {
        val b = JSONObject().put("body", body).put("client_id", clientId)
        if (replyTo != null) b.put("reply_to", replyTo)
        return parseItem(Api.call("POST", "/v1/conversations/$cid/messages", b).getJSONObject("message"))
    }

    fun react(id: String, emoji: String) {
        Api.call("PUT", "/v1/messages/$id/reaction", JSONObject().put("emoji", emoji))
    }

    fun delete(id: String) {
        Api.call("DELETE", "/v1/messages/$id")
    }

    fun edit(id: String, body: String) {
        Api.call("PATCH", "/v1/messages/$id", JSONObject().put("body", body))
    }

    fun forward(id: String, cid: String) {
        Api.call("POST", "/v1/messages/$id/forward", JSONObject().put("conversation_id", cid))
    }
}

package com.encore.app

data class Reaction(val messageId: Long, val userId: Long, val emoji: String)

object ReactDb {
    private var ready = false

    private fun setup() {
        if (ready) return
        Db.query(
            "CREATE TABLE IF NOT EXISTS reactions (" +
                "message_id INTEGER NOT NULL, " +
                "user_id INTEGER NOT NULL, " +
                "emoji TEXT NOT NULL, " +
                "PRIMARY KEY (message_id, user_id))"
        )
        ready = true
    }

    fun react(messageId: Long, me: Long, emoji: String) {
        setup()
        val current = Db.query(
            "SELECT emoji FROM reactions WHERE message_id = ? AND user_id = ?",
            messageId, me
        )
        if (current.isNotEmpty() && current[0][0] == emoji) {
            Db.query("DELETE FROM reactions WHERE message_id = ? AND user_id = ?", messageId, me)
        } else {
            Db.query(
                "INSERT OR REPLACE INTO reactions (message_id, user_id, emoji) VALUES (?, ?, ?)",
                messageId, me, emoji
            )
        }
    }

    fun reactions(me: Long, other: Long): Map<Long, List<Reaction>> {
        setup()
        return Db.query(
            "SELECT r.message_id, r.user_id, r.emoji FROM reactions r " +
                "JOIN messages m ON m.id = r.message_id " +
                "WHERE (m.sender_id = ? AND m.receiver_id = ?) OR (m.sender_id = ? AND m.receiver_id = ?)",
            me, other, other, me
        ).map { Reaction(it[0]!!.toLong(), it[1]!!.toLong(), it[2]!!) }
            .groupBy { it.messageId }
    }

    fun deleteMessage(messageId: Long, me: Long) {
        setup()
        val own = Db.query("SELECT id FROM messages WHERE id = ? AND sender_id = ?", messageId, me)
        if (own.isEmpty()) throw DbException("You can only delete your own messages")
        Db.query("DELETE FROM reactions WHERE message_id = ?", messageId)
        Db.query("DELETE FROM messages WHERE id = ?", messageId)
    }
}

package com.encore.app

data class Contact(val id: Long, val username: String)

data class Message(val id: Long, val senderId: Long, val body: String, val createdAt: Long)

object ChatDb {
    private var ready = false

    private fun setup() {
        if (ready) return
        Db.query(
            "CREATE TABLE IF NOT EXISTS contacts (" +
                "owner_id INTEGER NOT NULL, " +
                "contact_id INTEGER NOT NULL, " +
                "PRIMARY KEY (owner_id, contact_id))"
        )
        Db.query(
            "CREATE TABLE IF NOT EXISTS messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "sender_id INTEGER NOT NULL, " +
                "receiver_id INTEGER NOT NULL, " +
                "body TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL)"
        )
        ready = true
    }

    fun addContact(ownerId: Long, username: String): Contact {
        setup()
        val name = username.trim().lowercase().removePrefix("@")
        val rows = Db.query("SELECT id, username FROM users WHERE username = ?", name)
        if (rows.isEmpty()) throw DbException("No user with this username")
        val id = rows[0][0]!!.toLong()
        if (id == ownerId) throw DbException("That is your own username")
        Db.query("INSERT OR IGNORE INTO contacts (owner_id, contact_id) VALUES (?, ?)", ownerId, id)
        return Contact(id, rows[0][1]!!)
    }

    fun chats(me: Long): List<Contact> {
        setup()
        return Db.query(
            "SELECT id, username FROM users WHERE " +
                "id IN (SELECT contact_id FROM contacts WHERE owner_id = ?) " +
                "OR id IN (SELECT receiver_id FROM messages WHERE sender_id = ?) " +
                "OR id IN (SELECT sender_id FROM messages WHERE receiver_id = ?) " +
                "ORDER BY username",
            me, me, me
        ).map { Contact(it[0]!!.toLong(), it[1]!!) }
    }

    fun messages(me: Long, other: Long): List<Message> {
        setup()
        return Db.query(
            "SELECT id, sender_id, body, created_at FROM messages " +
                "WHERE (sender_id = ? AND receiver_id = ?) OR (sender_id = ? AND receiver_id = ?) " +
                "ORDER BY id DESC LIMIT 100",
            me, other, other, me
        ).map { Message(it[0]!!.toLong(), it[1]!!.toLong(), it[2]!!, it[3]!!.toLong()) }
            .reversed()
    }

    fun send(me: Long, other: Long, body: String) {
        setup()
        Db.query(
            "INSERT INTO messages (sender_id, receiver_id, body, created_at) VALUES (?, ?, ?, ?)",
            me, other, body.trim(), System.currentTimeMillis()
        )
    }
}

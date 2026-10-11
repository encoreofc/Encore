package com.encore.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

const val TURSO_URL = "libsql://encore-jahid.aws-ap-south-1.turso.io"
const val TURSO_TOKEN = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJpYXQiOjE3OTE3MTI2NzEsImlkIjoiMDFhMTJhNjUtODIwMS03NWJmLTg4NzYtNzAzZmNlM2I1MTdjIiwia2lkIjoiSXlnRGl4ZDhjbXJjMmxIOTVORExmZ0dTU1d0LVFKRUFnMm54RHhUS0lWSSIsInJpZCI6IjNhMjkzZjQ0LTdjODktNDczZi05YTk3LWJiYTdkZTMwMzhlNSJ9.7ieuZwGCF5C0yquJ4Ccw2ZUI3sVcol4D3rkFkj1miIzicSiuJ2jMa_kHWGcda5Vp53sv7BtdsVbLfrDNdnkhAg"

class DbException(message: String) : Exception(message)

data class User(val id: Long, val username: String)

object Db {
    private var ready = false

    private fun endpoint(): String =
        TURSO_URL.trim().replace("libsql://", "https://").trimEnd('/') + "/v2/pipeline"

    private fun arg(v: Any?): JSONObject = when (v) {
        null -> JSONObject().put("type", "null")
        is Int, is Long -> JSONObject().put("type", "integer").put("value", v.toString())
        else -> JSONObject().put("type", "text").put("value", v.toString())
    }

    fun query(sql: String, vararg args: Any?): List<List<String?>> {
        val stmt = JSONObject().put("sql", sql)
        stmt.put("args", JSONArray().apply { args.forEach { put(arg(it)) } })
        val body = JSONObject().put(
            "requests", JSONArray()
                .put(JSONObject().put("type", "execute").put("stmt", stmt))
                .put(JSONObject().put("type", "close"))
        )

        val conn = URL(endpoint()).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $TURSO_TOKEN")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw DbException("Server error $code: $text")

            val first = JSONObject(text).getJSONArray("results").getJSONObject(0)
            if (first.getString("type") == "error") {
                throw DbException(first.getJSONObject("error").getString("message"))
            }

            val rows = first.getJSONObject("response")
                .getJSONObject("result").getJSONArray("rows")
            return List(rows.length()) { i ->
                val row = rows.getJSONArray(i)
                List(row.length()) { j ->
                    val cell = row.getJSONObject(j)
                    if (cell.getString("type") == "null") null else cell.getString("value")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun setup() {
        if (ready) return
        query(
            "CREATE TABLE IF NOT EXISTS users (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "username TEXT NOT NULL UNIQUE, " +
                "salt TEXT NOT NULL, " +
                "pass_hash TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL)"
        )
        ready = true
    }

    private fun hash(salt: String, password: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((salt + password).toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun signUp(username: String, password: String): User {
        val name = username.trim().lowercase()
        if (!Regex("[a-z0-9_]{3,20}").matches(name)) {
            throw DbException("Username must be 3-20 characters: letters, numbers, underscore")
        }
        if (password.length < 6) throw DbException("Password must be at least 6 characters")
        setup()
        if (query("SELECT id FROM users WHERE username = ?", name).isNotEmpty()) {
            throw DbException("This username is taken")
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        query(
            "INSERT INTO users (username, salt, pass_hash, created_at) VALUES (?, ?, ?, ?)",
            name, salt, hash(salt, password), System.currentTimeMillis()
        )
        return login(name, password)
    }

    fun login(username: String, password: String): User {
        val name = username.trim().lowercase()
        setup()
        val rows = query("SELECT id, salt, pass_hash FROM users WHERE username = ?", name)
        if (rows.isEmpty()) throw DbException("Wrong username or password")
        val (id, salt, stored) = rows[0]
        if (hash(salt!!, password) != stored) throw DbException("Wrong username or password")
        return User(id!!.toLong(), name)
    }
}

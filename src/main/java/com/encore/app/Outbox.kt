package com.encore.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Messages are saved on the phone first (as "local-..." rows the chat already knows how to show),
 * then sent from a queue that survives app restarts and waits for the network.
 */
object Outbox {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val ctx: Context get() = EncoreApp.instance
    private val dao: AppDao get() = Db.dao(ctx)

    /** upload progress by local message id ("local-<clientId>"), read by the chat bubbles */
    var progress by mutableStateOf<Map<String, Float>>(emptyMap())
        private set

    fun setProgress(id: String, p: Float) { progress = progress + (id to p) }
    fun clearProgress(id: String) { progress = progress - id }

    /** the open chat sets this to show a send error */
    @Volatile
    var onProblem: ((String) -> Unit)? = null

    fun reset() {
        progress = emptyMap()
    }

    fun kick() {
        scope.launch { flush() }
    }

    suspend fun enqueueText(convId: String, me: User, body: String, rep: MsgItem?) {
        val clientId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val reply = rep?.let { ReplyInfo(it.msg.id, it.msg.senderId, it.msg.body, it.msg.deleted, it.msg.kind) }
        val local = MsgItem(
            Msg("local-$clientId", convId, me.id, "text", body, now, false, clientId),
            null, null, rep?.msg?.id, reply
        )
        dao.putMessages(listOf(local.toEntity()))
        dao.putOutbox(
            OutboxEntity(clientId, convId, "text", body, rep?.msg?.id, null, null, 0L, null, 0, 0, 0, now, 0)
        )
        dao.touchConv(convId, me.id, "text", body, now, false, 0)
        kick()
    }

    /** copies the file into app storage (so it survives a restart), shows it at once, uploads in the background */
    suspend fun enqueueMedia(
        c: Context,
        convId: String,
        me: User,
        uri: Uri,
        wanted: String,
        caption: String,
        ttl: Int,
        rep: MsgItem?
    ) {
        val picked = Uploads.inspect(c, uri, wanted)
        val clientId = UUID.randomUUID().toString()
        val dir = File(c.filesDir, "outbox").apply { mkdirs() }
        val ext = picked.name.substringAfterLast('.', "").filter { it.isLetterOrDigit() }.take(8)
        val dest = File(dir, clientId + if (ext.isNotEmpty()) ".$ext" else "")
        val input = c.contentResolver.openInputStream(uri) ?: throw ApiException("bad_file", "Can't read this file")
        input.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        val localUri = Uri.fromFile(dest).toString()
        val now = System.currentTimeMillis()
        val reply = rep?.let { ReplyInfo(it.msg.id, it.msg.senderId, it.msg.body, it.msg.deleted, it.msg.kind) }
        val cap = caption.ifBlank { null }
        val local = MsgItem(
            Msg("local-$clientId", convId, me.id, picked.kind, cap, now, false, clientId),
            null, null, rep?.msg?.id, reply,
            Media(picked.name, picked.size, picked.type, picked.w, picked.h, 0L, localUri),
            ttl = ttl
        )
        dao.putMessages(listOf(local.toEntity()))
        dao.putOutbox(
            OutboxEntity(
                clientId, convId, picked.kind, cap, rep?.msg?.id, localUri, picked.name,
                picked.size, picked.type, picked.w, picked.h, ttl, now, 0
            )
        )
        progress = progress + ("local-$clientId" to 0f)
        kick()
    }

    private fun transient(e: ApiException): Boolean =
        e.code == "network" || e.code == "rate_limited" || e.code == "internal" || e.code.startsWith("http_5")

    suspend fun flush() = mutex.withLock {
        if (Session.token == null) return@withLock
        while (true) {
            val row = dao.nextOutbox() ?: break
            try {
                send(row)
            } catch (e: ApiException) {
                if (e.code == "unauthorized") return@withLock
                if (transient(e)) {
                    dao.bumpAttempts(row.clientId)
                    progress = progress - "local-${row.clientId}"
                    scheduleRetry()
                    return@withLock
                }
                drop(row, e.message ?: "Couldn't send")
            } catch (e: java.io.IOException) {
                scheduleRetry()
                return@withLock
            }
        }
    }

    private suspend fun send(row: OutboxEntity) {
        val lid = "local-${row.clientId}"
        if (row.kind == "text") {
            val item = Features.send(row.conversationId, row.body ?: "", row.clientId, row.replyTo)
            Sync.applyItems(listOf(item), null)
            dao.deleteOutbox(row.clientId)
            return
        }
        val uri = Uri.parse(row.uri ?: throw ApiException("bad_file", "Can't read this file"))
        val file = File(uri.path ?: "")
        if (!file.exists()) throw ApiException("bad_file", "The file is no longer available")
        val picked = Uploads.Picked(uri, row.kind, row.name ?: "file", file.length(), row.type ?: "application/octet-stream", row.w, row.h)
        progress = progress + (lid to 0f)
        val item = Uploads.upload(ctx, row.conversationId, picked, row.clientId, row.replyTo, row.ttl, row.body) { p ->
            progress = progress + (lid to p)
        }
        Sync.applyItems(listOf(item), null)
        if (row.ttl == 0) {
            // keep our own upload on the phone: no need to download it again
            val kept = MediaFiles.adopt(ctx, item.msg.id, file, picked.type, picked.name)
            dao.setLocalPath(item.msg.id, kept.absolutePath)
        } else {
            file.delete()
        }
        dao.deleteOutbox(row.clientId)
        progress = progress - lid
    }

    private suspend fun drop(row: OutboxEntity, message: String) {
        dao.deleteLocalByClient(row.conversationId, row.clientId)
        dao.deleteOutbox(row.clientId)
        row.uri?.let { u -> Uri.parse(u).path?.let { runCatching { File(it).delete() } } }
        progress = progress - "local-${row.clientId}"
        onProblem?.invoke(message)
    }

    private fun scheduleRetry() {
        val req = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("outbox", ExistingWorkPolicy.KEEP, req)
    }
}

class OutboxWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        Session.load(applicationContext)
        if (Session.token == null) return Result.success()
        Outbox.flush()
        return if (Db.dao(applicationContext).outboxCount() > 0) Result.retry() else Result.success()
    }
}

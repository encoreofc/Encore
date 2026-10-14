package com.encore.app

import android.content.Context
import android.net.Uri
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import java.io.File

/* ---------- tables ---------- */

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId", "sortKey"]), Index(value = ["clientId"])]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val kind: String,
    val body: String?,
    val createdAt: Long,
    val deleted: Boolean,
    val clientId: String?,
    val editedAt: Long?,
    val fwdFrom: String?,
    val replyTo: String?,
    val replyBody: String?,
    val replySender: String?,
    val replyDeleted: Boolean,
    val replyKind: String?,
    val hasMedia: Boolean,
    val mediaName: String?,
    val mediaSize: Long,
    val mediaType: String?,
    val mediaW: Int,
    val mediaH: Int,
    val mediaDur: Long,
    val ttl: Int,
    val openedAt: Long?,
    /** server rows sort by id; not-yet-sent local rows ("local-...") always sort after them */
    val sortKey: String,
    /** downloaded file (absolute path) for server rows, or the file being uploaded (uri string) for local rows */
    val localPath: String? = null,
    /** download attempts that failed; 99 = evicted from the cache, do not auto-download again */
    val dlFailed: Int = 0
)

@Entity(tableName = "reactions", primaryKeys = ["messageId", "userId"], indices = [Index("messageId")])
data class ReactionEntity(val messageId: String, val userId: String, val emoji: String)

@Entity(tableName = "convs")
data class ConvEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String?,
    val avatarKey: String?,
    val peerId: String?,
    val peerUsername: String?,
    val peerName: String?,
    val peerAvatar: String?,
    val peerSeen: Long?,
    val lastSenderId: String?,
    val lastKind: String?,
    val lastPreview: String?,
    val lastAt: Long,
    val lastDeleted: Boolean,
    val unread: Int,
    val myRead: String,
    val peerRead: String,
    val updatedAt: Long,
    val memberCount: Int,
    val muted: Boolean
)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val clientId: String,
    val conversationId: String,
    /** "text", or the upload kind: image / video / file / voice */
    val kind: String,
    val body: String?,
    val replyTo: String?,
    val uri: String?,
    val name: String?,
    val size: Long,
    val type: String?,
    val w: Int,
    val h: Int,
    val ttl: Int,
    val createdAt: Long,
    val attempts: Int
)

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val userId: String,
    val bio: String?,
    val location: String?,
    val blocked: Boolean
)

@Entity(tableName = "meta")
data class MetaEntity(@PrimaryKey val k: String, val v: String)

data class Keeper(val id: String, val localPath: String?, val dlFailed: Int)

/* ---------- queries ---------- */

@Dao
abstract class AppDao {
    // messages
    @Query("SELECT * FROM (SELECT * FROM messages WHERE conversationId = :cid ORDER BY sortKey DESC LIMIT :limit) ORDER BY sortKey ASC")
    abstract fun observeMessages(cid: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT r.* FROM reactions r JOIN messages m ON m.id = r.messageId WHERE m.conversationId = :cid")
    abstract fun observeReactions(cid: String): Flow<List<ReactionEntity>>

    @Query("SELECT id, localPath, dlFailed FROM messages WHERE id IN (:ids)")
    abstract suspend fun keepers(ids: List<String>): List<Keeper>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putMessages(list: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putReactions(list: List<ReactionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putReaction(r: ReactionEntity)

    @Query("DELETE FROM reactions WHERE messageId IN (:ids)")
    abstract suspend fun clearReactions(ids: List<String>)

    @Query("DELETE FROM reactions WHERE messageId = :mid AND userId = :uid")
    abstract suspend fun deleteReaction(mid: String, uid: String)

    @Query("SELECT * FROM messages WHERE id = :id")
    abstract suspend fun getMessage(id: String): MessageEntity?

    @Query("DELETE FROM messages WHERE conversationId = :cid AND clientId = :clientId AND id LIKE 'local-%'")
    abstract suspend fun deleteLocalByClient(cid: String, clientId: String)

    @Query("SELECT MIN(id) FROM messages WHERE conversationId = :cid AND id NOT LIKE 'local-%'")
    abstract suspend fun oldestId(cid: String): String?

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :cid AND id NOT LIKE 'local-%'")
    abstract suspend fun count(cid: String): Int

    @Query("UPDATE messages SET deleted = 1, body = NULL, localPath = NULL WHERE id = :id")
    abstract suspend fun markDeleted(id: String)

    @Query("SELECT localPath FROM messages WHERE id = :id")
    abstract suspend fun pathOf(id: String): String?

    @Query("UPDATE messages SET body = :body, editedAt = :at WHERE id = :id")
    abstract suspend fun edit(id: String, body: String, at: Long?)

    @Query("UPDATE messages SET openedAt = :at WHERE id = :id AND openedAt IS NULL")
    abstract suspend fun markOpened(id: String, at: Long)

    @Query("UPDATE messages SET openedAt = :at WHERE id = :id AND openedAt IS NULL AND (senderId = :me OR :by = :me)")
    abstract suspend fun markOpenedBy(id: String, at: Long, by: String, me: String)

    @Query("UPDATE messages SET localPath = :path WHERE id = :id")
    abstract suspend fun setLocalPath(id: String, path: String)

    @Query("UPDATE messages SET dlFailed = dlFailed + 1 WHERE id = :id")
    abstract suspend fun bumpFailed(id: String)

    @Query("UPDATE messages SET localPath = NULL, dlFailed = 99 WHERE localPath = :path")
    abstract suspend fun evicted(path: String)

    /** images / videos small enough to fetch in the background, newest first */
    @Query(
        "SELECT * FROM messages WHERE hasMedia = 1 AND localPath IS NULL AND deleted = 0 AND ttl = 0 " +
            "AND kind IN ('image','video') AND (kind = 'image' OR :allowVideo = 1) " +
            "AND mediaSize BETWEEN 1 AND :maxBytes AND dlFailed < 3 AND id NOT LIKE 'local-%' " +
            "ORDER BY createdAt DESC LIMIT :n"
    )
    abstract suspend fun nextDownloads(maxBytes: Long, allowVideo: Int, n: Int): List<MessageEntity>

    // conversations
    @Query("SELECT * FROM convs ORDER BY updatedAt DESC")
    abstract fun observeConvs(): Flow<List<ConvEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putConvs(list: List<ConvEntity>)

    @Query("DELETE FROM convs WHERE id NOT IN (:ids)")
    abstract suspend fun pruneConvs(ids: List<String>)

    @Query("SELECT id FROM convs ORDER BY updatedAt DESC")
    abstract suspend fun convIds(): List<String>

    @Query("SELECT * FROM convs WHERE id = :id")
    abstract suspend fun getConv(id: String): ConvEntity?

    @Query(
        "UPDATE convs SET lastSenderId = :sender, lastKind = :kind, lastPreview = :preview, lastAt = :at, " +
            "lastDeleted = :deleted, updatedAt = :at, unread = unread + :inc WHERE id = :cid AND updatedAt <= :at"
    )
    abstract suspend fun touchConv(cid: String, sender: String, kind: String, preview: String?, at: Long, deleted: Boolean, inc: Int)

    @Query("UPDATE convs SET unread = 0, myRead = :upTo WHERE id = :cid")
    abstract suspend fun markRead(cid: String, upTo: String)

    @Query("UPDATE convs SET peerRead = :upTo WHERE id = :cid AND peerRead < :upTo")
    abstract suspend fun setPeerRead(cid: String, upTo: String)

    @Query("UPDATE convs SET muted = :on WHERE id = :cid")
    abstract suspend fun setMuted(cid: String, on: Boolean)

    // outbox
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putOutbox(o: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC LIMIT 1")
    abstract suspend fun nextOutbox(): OutboxEntity?

    @Query("DELETE FROM outbox WHERE clientId = :clientId")
    abstract suspend fun deleteOutbox(clientId: String)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE clientId = :clientId")
    abstract suspend fun bumpAttempts(clientId: String)

    @Query("SELECT COUNT(*) FROM outbox")
    abstract suspend fun outboxCount(): Int

    // profiles (blocking variants: used from runIo { } lambdas that are not suspend)
    @Query("SELECT * FROM profiles WHERE userId = :uid")
    abstract fun profileNow(uid: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun putProfileNow(p: ProfileEntity)

    // meta
    @Query("SELECT v FROM meta WHERE k = :k")
    abstract suspend fun meta(k: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putMeta(m: MetaEntity)
}

@Database(
    entities = [MessageEntity::class, ReactionEntity::class, ConvEntity::class, OutboxEntity::class, ProfileEntity::class, MetaEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao
}

object Db {
    @Volatile
    private var inst: AppDb? = null

    fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
        inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "encore.db")
            .fallbackToDestructiveMigration()
            .build()
            .also { inst = it }
    }

    fun dao(ctx: Context): AppDao = get(ctx).dao()
}

/* ---------- mapping between the database rows and the models the UI already uses ---------- */

fun pathToUri(p: String): String = if (p.startsWith("/")) Uri.fromFile(File(p)).toString() else p

fun MessageEntity.toItem(): MsgItem = MsgItem(
    msg = Msg(id, conversationId, senderId, kind, body, createdAt, deleted, clientId),
    editedAt = editedAt,
    fwdFrom = fwdFrom,
    replyTo = replyTo,
    reply = if (replyTo != null) ReplyInfo(replyTo, replySender, replyBody, replyDeleted, replyKind) else null,
    media = if (hasMedia && !deleted) {
        Media(mediaName ?: "file", mediaSize, mediaType ?: "", mediaW, mediaH, mediaDur, localPath?.let { pathToUri(it) })
    } else null,
    ttl = ttl,
    openedAt = openedAt
)

fun MsgItem.toEntity(): MessageEntity {
    val m = msg
    val md = media
    val local = m.id.startsWith("local-")
    return MessageEntity(
        id = m.id,
        conversationId = m.conversationId,
        senderId = m.senderId,
        kind = m.kind,
        body = m.body,
        createdAt = m.createdAt,
        deleted = m.deleted,
        clientId = m.clientId,
        editedAt = editedAt,
        fwdFrom = fwdFrom,
        replyTo = replyTo,
        replyBody = reply?.body,
        replySender = reply?.senderId,
        replyDeleted = reply?.deleted ?: false,
        replyKind = reply?.kind,
        hasMedia = md != null,
        mediaName = md?.name,
        mediaSize = md?.size ?: 0L,
        mediaType = md?.type,
        mediaW = md?.w ?: 0,
        mediaH = md?.h ?: 0,
        mediaDur = md?.dur ?: 0L,
        ttl = ttl,
        openedAt = openedAt,
        sortKey = if (local) "zzzz" + m.createdAt.toString().padStart(15, '0') else m.id,
        localPath = if (local) md?.localUri else null
    )
}

fun ConvEntity.toConv(): Conv {
    val peer: Peer? = when {
        type == "group" -> Peer(id, "", title ?: "Group", null, avatarKey)
        peerId != null -> Peer(peerId, peerUsername ?: "", peerName ?: "", peerSeen, peerAvatar)
        else -> null
    }
    val last = if (lastKind != null) LastMsg(lastSenderId ?: "", lastKind, lastPreview, lastAt, lastDeleted) else null
    return Conv(id, peer, last, unread, myRead, peerRead, updatedAt, type, memberCount, muted)
}

fun Conv.toEntity(): ConvEntity {
    val p = peer
    val group = type == "group"
    return ConvEntity(
        id = id,
        type = type,
        title = if (group) p?.displayName else null,
        avatarKey = if (group) p?.avatarKey else null,
        peerId = if (group) null else p?.id,
        peerUsername = if (group) null else p?.username,
        peerName = if (group) null else p?.displayName,
        peerAvatar = if (group) null else p?.avatarKey,
        peerSeen = if (group) null else p?.lastSeen,
        lastSenderId = last?.senderId,
        lastKind = last?.kind,
        lastPreview = last?.preview,
        lastAt = last?.createdAt ?: 0L,
        lastDeleted = last?.deleted ?: false,
        unread = unread,
        myRead = myRead,
        peerRead = peerRead,
        updatedAt = updatedAt,
        memberCount = memberCount,
        muted = muted
    )
}

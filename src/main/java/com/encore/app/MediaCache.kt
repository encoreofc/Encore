package com.encore.app

import android.content.Context
import android.net.ConnectivityManager
import android.webkit.MimeTypeMap
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** photos and videos live as plain files in app storage; the database only remembers where */
object MediaFiles {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    const val CACHE_LIMIT = 800L * 1024 * 1024

    fun dir(ctx: Context): File = File(ctx.filesDir, "media").apply { mkdirs() }

    private fun ext(type: String, name: String): String {
        val fromName = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5)
        if (fromName.isNotEmpty()) return fromName
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "bin"
    }

    /** move a file we uploaded into the media folder */
    fun adopt(ctx: Context, mid: String, src: File, type: String, name: String): File {
        val dest = File(dir(ctx), mid + "." + ext(type, name))
        if (!src.renameTo(dest)) {
            src.copyTo(dest, overwrite = true)
            src.delete()
        }
        return dest
    }

    fun download(ctx: Context, cid: String, mid: String, type: String, name: String): File {
        val dest = File(dir(ctx), mid + "." + ext(type, name))
        if (dest.exists() && dest.length() > 0) return dest
        val tmp = File(dir(ctx), "$mid.part")
        val req = Request.Builder()
            .url(Uploads.url(cid, mid))
            .header("Authorization", "Bearer ${Session.token}")
            .build()
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw ApiException("http_${r.code}", "Download failed")
                val body = r.body ?: throw ApiException("bad_file", "Empty file")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
        } catch (e: java.io.IOException) {
            tmp.delete()
            throw ApiException("network", "Download failed")
        } catch (e: ApiException) {
            tmp.delete()
            throw e
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        return dest
    }

    /** when the media folder gets too big, remove the oldest files (they stay on the server; tapping loads them again) */
    suspend fun trim(ctx: Context, dao: AppDao) {
        val files = dir(ctx).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= CACHE_LIMIT) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= CACHE_LIMIT * 8 / 10) break
            total -= f.length()
            dao.evicted(f.absolutePath)
            f.delete()
        }
    }
}

object Prefetch {
    fun kick() {
        val req = OneTimeWorkRequestBuilder<PrefetchWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(EncoreApp.instance).enqueueUniqueWork("prefetch", ExistingWorkPolicy.KEEP, req)
    }
}

/**
 * Background download, one file at a time and gently:
 *  - Wi-Fi / unmetered: photos and videos up to 5 MB
 *  - mobile data: photos up to 1 MB only
 * Newest first. View-once media is never downloaded.
 */
class PrefetchWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        Session.load(c)
        if (Session.token == null) return Result.success()
        val dao = Db.dao(c)
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val metered = cm.isActiveNetworkMetered
        val maxBytes = if (metered) 1L * 1024 * 1024 else 5L * 1024 * 1024
        val allowVideo = if (metered) 0 else 1
        var done = 0
        while (!isStopped && done < 60) {
            val batch = dao.nextDownloads(maxBytes, allowVideo, 10)
            if (batch.isEmpty()) break
            for (m in batch) {
                if (isStopped) break
                try {
                    val f = MediaFiles.download(c, m.conversationId, m.id, m.mediaType ?: "", m.mediaName ?: "")
                    dao.setLocalPath(m.id, f.absolutePath)
                } catch (e: ApiException) {
                    if (e.code == "network") return Result.retry()
                    dao.bumpFailed(m.id)
                }
                done++
                delay(250)
            }
        }
        MediaFiles.trim(c, dao)
        return if (done >= 60) Result.retry() else Result.success()
    }
}

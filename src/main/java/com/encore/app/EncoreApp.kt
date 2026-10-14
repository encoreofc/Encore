package com.encore.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache

class EncoreApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Session.load(this)
        if (Session.token != null) Sync.start()
    }

    // one big on-disk image cache so avatars, stories and chat photos are not fetched twice;
    // coil-video lets a downloaded video show its first frame as the thumbnail
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("img"))
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
        }
        .components { add(VideoFrameDecoder.Factory()) }
        .respectCacheHeaders(true)
        .build()

    companion object {
        lateinit var instance: EncoreApp
            private set
    }
}

package org.moontechlab.gdrivetv

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.util.DebugLogger
import org.moontechlab.gdrivetv.data.api.HttpClientProvider

class GDriveApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Set singleton image loader explicitly so all components (Activities, Compose, DreamService) use it
        Coil.setImageLoader(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(HttpClientProvider.client)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512L * 1024 * 1024) // 512 MB disk cache for high-res photos
                    .build()
            }
            .crossfade(true)
            .build()
    }

    companion object {
        lateinit var instance: GDriveApp
            private set
    }
}

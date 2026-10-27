package org.moontechlab.gdrivetv.data.api

import okhttp3.OkHttpClient
import org.moontechlab.gdrivetv.GDriveApp
import org.moontechlab.gdrivetv.data.store.PreferenceStore
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object HttpClientProvider {
    val client: OkHttpClient by lazy {
        val clashProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7890))
        OkHttpClient.Builder()
            .proxy(clashProxy)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                    .header("User-Agent", "GDriveTV/1.0 (Android TV; Sony BRAVIA)")

                // Auto-attach OAuth token to Google Drive API requests lacking Authorization (never attach to oauth2 endpoint)
                if (original.url.host.contains("googleapis.com") && !original.url.host.contains("oauth2") && original.header("Authorization") == null) {
                    try {
                        val prefs = PreferenceStore(GDriveApp.instance)
                        val token = prefs.accessToken
                        if (token.isNotEmpty()) {
                            builder.header("Authorization", "Bearer $token")
                        }
                    } catch (_: Exception) {}
                }

                chain.proceed(builder.build())
            }
            .build()
    }
}


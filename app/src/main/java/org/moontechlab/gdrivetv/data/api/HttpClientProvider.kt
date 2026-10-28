package org.moontechlab.gdrivetv.data.api

import okhttp3.OkHttpClient
import org.moontechlab.gdrivetv.GDriveApp
import org.moontechlab.gdrivetv.data.store.PreferenceStore
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

object HttpClientProvider {
    val client: OkHttpClient by lazy {
        val clashProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7890))
        val adaptiveProxySelector = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                // If 127.0.0.1:7890 is not ready upon cold boot, gracefully fallback to direct / TUN mode
                return listOf(clashProxy, Proxy.NO_PROXY)
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                android.util.Log.w("HttpClientProvider", "Proxy failed for $uri via $sa: ${ioe?.message}")
            }
        }

        OkHttpClient.Builder()
            .proxySelector(adaptiveProxySelector)
            .connectTimeout(10, TimeUnit.SECONDS)
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

package org.moontechlab.gdrivetv.data.store

import android.content.Context
import android.content.SharedPreferences

class PreferenceStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gdrive_tv_prefs", Context.MODE_PRIVATE)

    var clientId: String
        get() = prefs.getString("client_id", "") ?: ""
        set(value) = prefs.edit().putString("client_id", value.trim()).apply()

    var clientSecret: String
        get() = prefs.getString("client_secret", "") ?: ""
        set(value) = prefs.edit().putString("client_secret", value.trim()).apply()

    var accessToken: String
        get() = prefs.getString("access_token", "") ?: ""
        set(value) = prefs.edit().putString("access_token", value).apply()

    var refreshToken: String
        get() = prefs.getString("refresh_token", "") ?: ""
        set(value) = prefs.edit().putString("refresh_token", value).apply()

    var tokenExpiryTime: Long
        get() = prefs.getLong("token_expiry", 0L)
        set(value) = prefs.edit().putLong("token_expiry", value).apply()

    // Google Photos shared album URL (phone camera roll / shared albums)
    var photosAlbumUrl: String
        get() = prefs.getString(
            "photos_album_url",
            "https://photos.google.com/share/AF1QipOIWscmv4UkFojkto9Nmy2iQMrG45in5nAcMXC_uUGi2SeQDYjK98ZYqnMvO2tJUA?key=RWtZVlVSNENyeUxpanpPNTVXZEViVEpOU2R4NzRB"
        ) ?: "https://photos.google.com/share/AF1QipOIWscmv4UkFojkto9Nmy2iQMrG45in5nAcMXC_uUGi2SeQDYjK98ZYqnMvO2tJUA?key=RWtZVlVSNENyeUxpanpPNTVXZEViVEpOU2R4NzRB"
        set(value) = prefs.edit().putString("photos_album_url", value.trim()).apply()

    var photosAlbumName: String
        get() = prefs.getString("photos_album_name", "电视相册") ?: "电视相册"
        set(value) = prefs.edit().putString("photos_album_name", value.trim()).apply()

    // Screensaver source: "photos" (Google Photos shared album) or "drive" (Google Drive folder)
    var screensaverSource: String
        get() = prefs.getString("screensaver_source", "photos") ?: "photos"
        set(value) = prefs.edit().putString("screensaver_source", value).apply()

    var screensaverFolderId: String
        get() = prefs.getString("screensaver_folder_id", "root") ?: "root"
        set(value) = prefs.edit().putString("screensaver_folder_id", value).apply()

    var screensaverFolderName: String
        get() = prefs.getString("screensaver_folder_name", "全部相册") ?: "全部相册"
        set(value) = prefs.edit().putString("screensaver_folder_name", value).apply()

    var screensaverMonth: Int?
        get() {
            val v = prefs.getInt("screensaver_month", -1)
            return if (v in 1..12) v else null
        }
        set(value) = prefs.edit().putInt("screensaver_month", value ?: -1).apply()

    var screensaverDay: Int?
        get() {
            val v = prefs.getInt("screensaver_day", -1)
            return if (v in 1..31) v else null
        }
        set(value) = prefs.edit().putInt("screensaver_day", value ?: -1).apply()

    var slideshowIntervalSeconds: Int
        get() = prefs.getInt("slideshow_interval", 6)
        set(value) = prefs.edit().putInt("slideshow_interval", value).apply()

    // Last visited folder breadcrumbs JSON for instant resume
    var lastFolderBreadcrumbs: String
        get() = prefs.getString("last_folder_breadcrumbs", "") ?: ""
        set(value) = prefs.edit().putString("last_folder_breadcrumbs", value).apply()

    fun isLoggedIn(): Boolean = refreshToken.isNotEmpty() || (accessToken.isNotEmpty() && System.currentTimeMillis() < tokenExpiryTime)

    fun clearTokens() {
        prefs.edit()
            .remove("access_token")
            .remove("refresh_token")
            .remove("token_expiry")
            .remove("last_folder_breadcrumbs")
            .apply()
    }
}

package org.moontechlab.gdrivetv.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.moontechlab.gdrivetv.GDriveApp
import org.moontechlab.gdrivetv.data.auth.GoogleOAuthManager
import org.moontechlab.gdrivetv.data.model.DriveFileList
import org.moontechlab.gdrivetv.data.model.DriveItem
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class GoogleDriveApi(
    private val authManager: GoogleOAuthManager,
    private val httpClient: OkHttpClient = HttpClientProvider.client
) {
    companion object {
        private const val TAG = "GoogleDriveApi"
        private const val BASE_URL = "https://www.googleapis.com/drive/v3/files"
        private val SAFE_NAME_REGEX = Regex("[^a-zA-Z0-9_-]")
    }

    private val memoryCache = ConcurrentHashMap<String, DriveFileList>()
    private val _deletedItemEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val deletedItemEvents = _deletedItemEvents.asSharedFlow()

    private val diskCacheDir by lazy {
        try {
            GDriveApp.instance.cacheDir.resolve("drive_folder_cache").apply { mkdirs() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Clear both memory and disk folder cache (used upon logout / account switch).
     */
    fun clearCache() {
        memoryCache.clear()
        try {
            diskCacheDir?.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    /**
     * Get folder contents synchronously from memory or disk cache for instant display.
     */
    fun getCachedFolder(folderId: String): DriveFileList? {
        memoryCache[folderId]?.let { return it }
        val dir = diskCacheDir ?: return null
        return try {
            val safeName = folderId.replace(SAFE_NAME_REGEX, "_")
            val cacheFile = File(dir, "$safeName.json")
            if (cacheFile.exists()) {
                val jsonStr = cacheFile.readText()
                val json = JSONObject(jsonStr)
                val filesArray = json.optJSONArray("files") ?: return null
                val list = mutableListOf<DriveItem>()
                for (i in 0 until filesArray.length()) {
                    val obj = filesArray.getJSONObject(i)
                    list.add(
                        DriveItem(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            mimeType = obj.getString("mimeType"),
                            size = obj.optLong("size", 0L),
                            modifiedTime = obj.optString("modifiedTime"),
                            thumbnailLink = obj.optString("thumbnailLink").takeIf { it.isNotEmpty() },
                            width = if (obj.has("width")) obj.optInt("width") else null,
                            height = if (obj.has("height")) obj.optInt("height") else null,
                            takenTime = obj.optString("takenTime").takeIf { it.isNotEmpty() }
                        )
                    )
                }
                val cached = DriveFileList(list, json.optString("nextPageToken").takeIf { it.isNotEmpty() })
                memoryCache[folderId] = cached
                cached
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cache for folder $folderId: ${e.message}")
            null
        }
    }

    private fun saveFolderCache(folderId: String, data: DriveFileList) {
        memoryCache[folderId] = data
        val dir = diskCacheDir ?: return
        try {
            val json = JSONObject()
            val filesArray = JSONArray()
            for (item in data.files) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("mimeType", item.mimeType)
                    put("size", item.size)
                    put("modifiedTime", item.modifiedTime)
                    if (item.thumbnailLink != null) put("thumbnailLink", item.thumbnailLink)
                    if (item.width != null) put("width", item.width)
                    if (item.height != null) put("height", item.height)
                    if (item.takenTime != null) put("takenTime", item.takenTime)
                }
                filesArray.put(obj)
            }
            json.put("files", filesArray)
            if (data.nextPageToken != null) {
                json.put("nextPageToken", data.nextPageToken)
            }
            val safeName = folderId.replace(SAFE_NAME_REGEX, "_")
            val cacheFile = File(dir, "$safeName.json")
            cacheFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write cache for folder $folderId: ${e.message}")
        }
    }

    /**
     * List files and folders inside a given folder.
     */
    suspend fun listFolder(
        folderId: String = "root",
        pageToken: String? = null,
        onlyImages: Boolean = false,
        fetchAllPages: Boolean = true
    ): Result<DriveFileList> = withContext(Dispatchers.IO) {
        val allItems = mutableListOf<DriveItem>()
        var currentToken: String? = pageToken
        var lastNextToken: String? = null

        do {
            val token = authManager.getValidAccessToken()
                ?: return@withContext Result.failure(Exception("登录已失效，请重新授权"))

            val query = buildString {
                append("'$folderId' in parents and trashed = false")
                if (onlyImages) {
                    append(" and mimeType contains 'image/'")
                }
            }

            val urlBuilder = BASE_URL.toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime,thumbnailLink,imageMediaMetadata,videoMediaMetadata)")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("orderBy", "folder,modifiedTime desc")

            if (!currentToken.isNullOrEmpty()) {
                urlBuilder.addQueryParameter("pageToken", currentToken)
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .header("Authorization", "Bearer $token")
                .build()

            try {
                var response = httpClient.newCall(request).execute()
                var body = response.body?.string().orEmpty()

                if (response.code == 401) {
                    Log.w(TAG, "Access token expired (401), force refreshing token...")
                    val newToken = authManager.getValidAccessToken(forceRefresh = true)
                    if (newToken != null) {
                        val retryRequest = request.newBuilder()
                            .header("Authorization", "Bearer $newToken")
                            .build()
                        response = httpClient.newCall(retryRequest).execute()
                        body = response.body?.string().orEmpty()
                    }
                }

                if (!response.isSuccessful) {
                    Log.e(TAG, "List files failed: HTTP ${response.code} $body")
                    if (allItems.isNotEmpty()) {
                        break
                    }
                    return@withContext Result.failure(Exception("读取目录失败: HTTP ${response.code}"))
                }

                val json = JSONObject(body)
                val filesArray = json.optJSONArray("files")
                lastNextToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }

                if (filesArray != null) {
                    for (i in 0 until filesArray.length()) {
                        val obj = filesArray.getJSONObject(i)
                        val imgMeta = obj.optJSONObject("imageMediaMetadata")
                        val videoMeta = obj.optJSONObject("videoMediaMetadata")
                        val takenTime = imgMeta?.optString("time")?.takeIf { it.isNotEmpty() }
                        val width = if (imgMeta != null && imgMeta.has("width")) imgMeta.optInt("width")
                            else if (videoMeta != null && videoMeta.has("width")) videoMeta.optInt("width")
                            else null
                        val height = if (imgMeta != null && imgMeta.has("height")) imgMeta.optInt("height")
                            else if (videoMeta != null && videoMeta.has("height")) videoMeta.optInt("height")
                            else null
                        val item = DriveItem(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            mimeType = obj.getString("mimeType"),
                            size = obj.optLong("size", 0L),
                            modifiedTime = obj.optString("modifiedTime"),
                            thumbnailLink = obj.optString("thumbnailLink").takeIf { it.isNotEmpty() },
                            width = width,
                            height = height,
                            takenTime = takenTime
                        )
                        allItems.add(item)
                    }
                }

                currentToken = lastNextToken
            } catch (e: Exception) {
                Log.e(TAG, "Exception on listFolder", e)
                if (allItems.isNotEmpty()) {
                    break
                }
                return@withContext Result.failure(e)
            }
        } while (fetchAllPages && !currentToken.isNullOrEmpty() && allItems.size < 20000)

        val result = DriveFileList(allItems, lastNextToken)
        saveFolderCache(folderId, result)
        Result.success(result)
    }

    /**
     * Recursively collect all image items from a folder (used for screensaver).
     */
    suspend fun collectImages(folderId: String, maxCount: Int = 1000): List<DriveItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<DriveItem>()
        suspend fun scan(fId: String, depth: Int = 0) {
            if (result.size >= maxCount || depth > 3) return
            val res = listFolder(fId, null, onlyImages = false).getOrNull() ?: return
            for (item in res.files) {
                if (result.size >= maxCount) break
                if (item.isImage) {
                    result.add(item)
                }
            }
            // Then scan subfolders if we still need more images
            if (result.size < maxCount) {
                for (item in res.files) {
                    if (result.size >= maxCount) break
                    if (item.isFolder) {
                        scan(item.id, depth + 1)
                    }
                }
            }
        }
        scan(folderId, 0)
        result
    }

    /**
     * Get high-resolution image URL (using Google Drive alt=media).
     */
    fun getHighResMediaUrl(fileId: String): String {
        return "$BASE_URL/$fileId?alt=media"
    }

    /**
     * Move an item to Google Drive trash (soft delete).
     */
    suspend fun moveToTrash(fileId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken()
            ?: return@withContext Result.failure(Exception("未登录或凭据失效"))

        val url = "$BASE_URL/$fileId?supportsAllDrives=true"
        val json = JSONObject().put("trashed", true).toString()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .patch(body)
            .header("Authorization", "Bearer $token")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                // Clear local memory and disk caches so deleted item disappears
                memoryCache.clear()
                try {
                    diskCacheDir?.listFiles()?.forEach { it.delete() }
                } catch (_: Exception) {}
                _deletedItemEvents.tryEmit(fileId)
                Result.success(true)
            } else {
                val err = response.body?.string().orEmpty()
                Log.e(TAG, "moveToTrash failed: code=${response.code}, body=$err")
                val msg = if (response.code == 403) "权限不足：当前凭据为只读模式，请重新授权带写入权限的凭据" else "移入回收站失败 (${response.code})"
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "moveToTrash error", e)
            Result.failure(e)
        }
    }
}

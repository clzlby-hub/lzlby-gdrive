package org.moontechlab.gdrivetv.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.moontechlab.gdrivetv.data.model.DriveItem
import java.util.regex.Pattern

object GooglePhotosParser {
    private const val TAG = "GooglePhotosParser"
    private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val BATCH_URL = "https://photos.google.com/_/PhotosUi/data/batchexecute?rpcids=snAcKc&soc-app=1&soc-platform=1&soc-device=1"

    suspend fun parseAlbum(albumUrl: String): Result<Pair<String, List<DriveItem>>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(albumUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = HttpClientProvider.client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("无法访问相簿页面: HTTP ${response.code}"))
            }

            val html = response.body?.string().orEmpty()

            // 1. Extract album title
            val titlePattern = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]+)\"")
            val titleMatcher = titlePattern.matcher(html)
            val title = if (titleMatcher.find()) {
                titleMatcher.group(1)?.trim()?.substringBefore(" ·")?.takeIf { it.isNotEmpty() } ?: "Google 相册"
            } else "Google 相册"

            // 2. Extract album ID and share key from URL or HTML
            val albumId = albumUrl.substringAfter("/share/").substringBefore("?")
            val shareKey = if (albumUrl.contains("key=")) {
                albumUrl.substringAfter("key=").substringBefore("&")
            } else null

            val seen = HashSet<String>()
            val items = ArrayList<DriveItem>()
            var nextToken: String? = null

            // 3. Try parsing structured data from AF_initDataCallback ds:1
            try {
                val ds1Pattern = Pattern.compile("AF_initDataCallback\\(\\{key:\\s*'ds:1'.*?data:(.*?)(?:,\\s*sideChannel:|\\}\\s*\\);)", Pattern.DOTALL)
                val ds1Matcher = ds1Pattern.matcher(html)
                if (ds1Matcher.find()) {
                    val dataJson = ds1Matcher.group(1)?.trim().orEmpty()
                    val dataArray = JSONArray(dataJson)
                    if (dataArray.length() > 1 && !dataArray.isNull(1)) {
                        val mediaArray = dataArray.getJSONArray(1)
                        for (i in 0 until mediaArray.length()) {
                            val photoEntry = mediaArray.getJSONArray(i)
                            val urlArray = photoEntry.getJSONArray(1)
                            val fullUrl = urlArray.getString(0)
                            val base = fullUrl.substringBefore("=")
                            val isVideo = photoEntry.toString().contains("76647426")
                            if (seen.add(base)) {
                                val idx = items.size + 1
                                items.add(createDriveItem(idx, base, isVideo))
                            }
                        }
                    }
                    if (dataArray.length() > 2 && !dataArray.isNull(2)) {
                        val t = dataArray.optString(2)
                        if (t.isNotBlank()) {
                            nextToken = t
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing ds:1 JSON callback, will fallback to regex: ${e.message}")
            }

            // Fallback: If structured parsing found 0 items, use regex pattern
            if (items.isEmpty()) {
                val pwPattern = Pattern.compile("\"(https://lh3\\.googleusercontent\\.com/pw/[a-zA-Z0-9\\-_]+)\"")
                val matcher = pwPattern.matcher(html)
                while (matcher.find()) {
                    val fullUrl = matcher.group(1) ?: continue
                    val base = fullUrl.substringBefore("=")
                    if (seen.add(base)) {
                        val idx = items.size + 1
                        items.add(createDriveItem(idx, base))
                    }
                }
            }

            // 4. Paginate via snAcKc batchexecute if nextToken is present
            var page = 1
            while (!nextToken.isNullOrEmpty() && page <= 25) {
                Log.i(TAG, "Fetching next batch (page $page, token: ${nextToken.take(15)}...)")
                val batchResult = fetchBatch(albumId, shareKey, nextToken) ?: break
                nextToken = batchResult.second

                for (batchItem in batchResult.first) {
                    val base = batchItem.first
                    val isVideo = batchItem.second
                    if (seen.add(base)) {
                        val idx = items.size + 1
                        items.add(createDriveItem(idx, base, isVideo))
                    }
                }
                page++
            }

            Log.i(TAG, "Parsed total ${items.size} photos for album: $title")
            Result.success(Pair(title, items))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Google Photos album", e)
            Result.failure(e)
        }
    }

    private fun createDriveItem(index: Int, baseUrl: String, isVideo: Boolean = false): DriveItem {
        return DriveItem(
            id = "gphotos_$index",
            name = if (isVideo) "视频 $index" else "照片 $index",
            mimeType = if (isVideo) "video/mp4" else "image/jpeg",
            size = 0L,
            modifiedTime = null,
            thumbnailLink = "$baseUrl=w500-h500-c"
        )
    }

    private fun fetchBatch(albumId: String, shareKey: String?, token: String): Pair<List<Pair<String, Boolean>>, String?>? {
        return try {
            val reqPayload = JSONArray().apply {
                put(albumId)
                put(token)
                put(JSONObject.NULL)
                if (!shareKey.isNullOrEmpty()) {
                    put(shareKey)
                }
            }

            val envelope = JSONArray().apply {
                put(JSONArray().apply {
                    put("snAcKc")
                    put(reqPayload.toString())
                    put(JSONObject.NULL)
                    put("generic")
                })
            }

            val fReq = JSONArray().apply {
                put(envelope)
            }.toString()

            val form = FormBody.Builder()
                .add("f.req", fReq)
                .build()

            val request = Request.Builder()
                .url(BATCH_URL)
                .header("User-Agent", USER_AGENT)
                .post(form)
                .build()

            val response = HttpClientProvider.client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string().orEmpty()
            val lines = body.split("\n")
            val newItems = mutableListOf<Pair<String, Boolean>>()
            var nextContinuationToken: String? = null

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.all { it.isDigit() }) continue
                try {
                    val lineArray = JSONArray(trimmed)
                    if (lineArray.length() > 0) {
                        val firstElem = lineArray.getJSONArray(0)
                        if (firstElem.length() > 2 && firstElem.getString(0) == "wrb.fr") {
                            val payloadStr = firstElem.getString(2)
                            val payload = JSONArray(payloadStr)
                            if (payload.length() > 1 && !payload.isNull(1)) {
                                val mediaList = payload.getJSONArray(1)
                                for (i in 0 until mediaList.length()) {
                                    val photoEntry = mediaList.getJSONArray(i)
                                    val urlArray = photoEntry.getJSONArray(1)
                                    val fullUrl = urlArray.getString(0)
                                    val isVideo = photoEntry.toString().contains("76647426")
                                    newItems.add(Pair(fullUrl.substringBefore("="), isVideo))
                                }
                            }
                            if (payload.length() > 2 && !payload.isNull(2)) {
                                val tok = payload.optString(2)
                                if (tok.isNotBlank()) {
                                    nextContinuationToken = tok
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            Pair(newItems, nextContinuationToken)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch batch with token", e)
            null
        }
    }
}

package org.moontechlab.gdrivetv.data.auth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.moontechlab.gdrivetv.data.api.HttpClientProvider
import org.moontechlab.gdrivetv.data.store.PreferenceStore
import java.io.IOException

sealed class AuthState {
    data object Idle : AuthState()
    data class DeviceCodeReceived(
        val userCode: String,
        val verificationUrl: String,
        val expiresIn: Int
    ) : AuthState()
    data object Authorized : AuthState()
    data class Error(val message: String) : AuthState()
}

class GoogleOAuthManager(
    private val prefs: PreferenceStore,
    private val httpClient: OkHttpClient = HttpClientProvider.client
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    fun resetState() {
        _authState.value = AuthState.Idle
    }

    companion object {
        private const val TAG = "GoogleOAuthManager"
        private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val SCOPE = "https://www.googleapis.com/auth/drive"
    }

    /**
     * Start the Device Code flow.
     */
    suspend fun startDeviceAuthorization(): Result<AuthState.DeviceCodeReceived> = withContext(Dispatchers.IO) {
        val clientId = prefs.clientId
        if (clientId.isEmpty()) {
            val error = "请先配置 Google Client ID（可通过手机扫码在网页中输入）"
            _authState.value = AuthState.Error(error)
            return@withContext Result.failure(Exception(error))
        }

        try {
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("scope", SCOPE)
                .build()

            val request = Request.Builder()
                .url(DEVICE_CODE_URL)
                .post(form)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            Log.i(TAG, "Device code response: code=${response.code}, body=$body")

            if (!response.isSuccessful) {
                val errorMsg = parseErrorMessage(body)
                _authState.value = AuthState.Error(errorMsg)
                return@withContext Result.failure(Exception(errorMsg))
            }

            val json = JSONObject(body)
            val deviceCode = json.getString("device_code")
            val userCode = json.getString("user_code")
            val verificationUrl = json.optString("verification_url", "https://www.google.com/device")
            val expiresIn = json.optInt("expires_in", 1800)
            val interval = json.optInt("interval", 5).coerceAtLeast(3)

            val state = AuthState.DeviceCodeReceived(userCode, verificationUrl, expiresIn)
            _authState.value = state

            // Start polling in background
            startPolling(deviceCode, clientId, prefs.clientSecret, interval, expiresIn)

            Result.success(state)
        } catch (e: Exception) {
            Log.e(TAG, "Device code request failed", e)
            val msg = "连接 Google 授权服务失败: ${e.message}"
            _authState.value = AuthState.Error(msg)
            Result.failure(e)
        }
    }

    private suspend fun startPolling(
        deviceCode: String,
        clientId: String,
        clientSecret: String,
        initialInterval: Int,
        expiresIn: Int
    ) = withContext(Dispatchers.IO) {
        var interval = initialInterval
        val startTime = System.currentTimeMillis()
        val maxDuration = expiresIn * 1000L

        while (System.currentTimeMillis() - startTime < maxDuration) {
            delay(interval * 1000L)

            val formBuilder = FormBody.Builder()
                .add("client_id", clientId)
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")

            if (clientSecret.isNotEmpty()) {
                formBuilder.add("client_secret", clientSecret)
            }

            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(formBuilder.build())
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)

                if (response.isSuccessful && json.has("access_token")) {
                    val accessToken = json.getString("access_token")
                    val refreshToken = json.optString("refresh_token")
                    val tokenExpiresIn = json.optInt("expires_in", 3600)

                    prefs.accessToken = accessToken
                    if (refreshToken.isNotEmpty()) {
                        prefs.refreshToken = refreshToken
                    }
                    prefs.tokenExpiryTime = System.currentTimeMillis() + (tokenExpiresIn * 1000L)

                    Log.i(TAG, "OAuth authorization success!")
                    _authState.value = AuthState.Authorized
                    return@withContext
                } else {
                    val error = json.optString("error")
                    when (error) {
                        "authorization_pending" -> {
                            // User has not approved yet, continue polling
                        }
                        "slow_down" -> {
                            interval += 5
                        }
                        "expired_token" -> {
                            _authState.value = AuthState.Error("授权码已过期，请重新获取")
                            return@withContext
                        }
                        "access_denied" -> {
                            _authState.value = AuthState.Error("用户拒绝了授权")
                            return@withContext
                        }
                        else -> {
                            Log.w(TAG, "Poll error: $error")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Poll network error: ${e.message}")
            }
        }

        _authState.value = AuthState.Error("授权超时，请重试")
    }

    /**
     * Get a valid access token, auto-refreshing if near expiration or forced.
     */
    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) {
        val currentToken = prefs.accessToken
        val expiryTime = prefs.tokenExpiryTime

        // If token has more than 5 minutes left and not forcing refresh, return it
        if (!forceRefresh && currentToken.isNotEmpty() && System.currentTimeMillis() < (expiryTime - 300_000L)) {
            return@withContext currentToken
        }

        val refreshToken = prefs.refreshToken
        if (refreshToken.isEmpty()) return@withContext null

        val clientId = prefs.clientId
        val clientSecret = prefs.clientSecret

        val formBuilder = FormBody.Builder()
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")

        if (clientSecret.isNotEmpty()) {
            formBuilder.add("client_secret", clientSecret)
        }

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(formBuilder.build())
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val json = JSONObject(body)
                val newAccessToken = json.getString("access_token")
                val expiresIn = json.optInt("expires_in", 3600)
                prefs.accessToken = newAccessToken
                prefs.tokenExpiryTime = System.currentTimeMillis() + (expiresIn * 1000L)
                Log.i(TAG, "OAuth access token refreshed successfully!")
                return@withContext newAccessToken
            } else {
                Log.e(TAG, "Refresh token failed: $body")
                return@withContext null
            }
        } catch (e: IOException) {
            Log.e(TAG, "Refresh token network exception", e)
            return@withContext null
        }
    }

    private fun parseErrorMessage(jsonBody: String): String {
        return try {
            val json = JSONObject(jsonBody)
            val errObj = json.optJSONObject("error")
            errObj?.optString("message") ?: json.optString("error_description", jsonBody)
        } catch (e: Exception) {
            jsonBody
        }
    }
}

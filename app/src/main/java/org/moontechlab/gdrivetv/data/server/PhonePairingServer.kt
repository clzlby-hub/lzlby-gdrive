package org.moontechlab.gdrivetv.data.server

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import org.moontechlab.gdrivetv.data.api.HttpClientProvider
import org.moontechlab.gdrivetv.data.store.PreferenceStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder

class PhonePairingServer(
    private val context: Context,
    private val prefs: PreferenceStore,
    private val onConfigUpdated: () -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    val port = 28258

    fun start() {
        if (serverJob != null) return
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Pairing server started on port $port")
                while (true) {
                    val socket = serverSocket?.accept() ?: break
                    handleClient(socket)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server loop ended: ${e.message}")
            }
        }
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    fun getLocalIpAddress(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            var fallbackIp = "127.0.0.1"
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (!host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
            fallbackIp
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    private fun handleClient(socket: Socket) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val out = PrintWriter(socket.getOutputStream(), true)

                val line = reader.readLine() ?: return@launch
                val parts = line.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1) ?: "/"

                var contentLength = 0
                var headerLine = reader.readLine()
                while (!headerLine.isNullOrEmpty()) {
                    if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    headerLine = reader.readLine()
                }

                if (method == "POST" && path == "/save") {
                    val bodyChars = CharArray(contentLength)
                    reader.read(bodyChars, 0, contentLength)
                    val body = String(bodyChars)
                    handlePostSave(body)

                    out.println("HTTP/1.1 200 OK")
                    out.println("Content-Type: text/html; charset=utf-8")
                    out.println("Connection: close")
                    out.println()
                    out.println(SUCCESS_HTML)
                } else if (path.startsWith("/callback")) {
                    val query = path.substringAfter("?", "")
                    var authCode = ""
                    for (param in query.split("&")) {
                        val kv = param.split("=")
                        if (kv.getOrNull(0) == "code") {
                            authCode = URLDecoder.decode(kv.getOrNull(1) ?: "", "UTF-8")
                        }
                    }

                    if (authCode.isNotEmpty()) {
                        val ok = exchangeAuthCode(authCode)
                        out.println("HTTP/1.1 200 OK")
                        out.println("Content-Type: text/html; charset=utf-8")
                        out.println("Connection: close")
                        out.println()
                        if (ok) {
                            out.println(AUTH_SUCCESS_HTML)
                        } else {
                            out.println(AUTH_ERROR_HTML)
                        }
                    } else {
                        out.println("HTTP/1.1 400 Bad Request")
                        out.println("Content-Type: text/html; charset=utf-8")
                        out.println("Connection: close")
                        out.println()
                        out.println(AUTH_ERROR_HTML)
                    }
                } else {
                    out.println("HTTP/1.1 200 OK")
                    out.println("Content-Type: text/html; charset=utf-8")
                    out.println("Connection: close")
                    out.println()
                    out.println(renderHtml(prefs.clientId, prefs.clientSecret))
                }
                socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "Client handle error", e)
            }
        }
    }

    private suspend fun exchangeAuthCode(code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val redirectUri = "http://${getLocalIpAddress()}:$port/callback"
            val formBuilder = FormBody.Builder()
                .add("client_id", prefs.clientId)
                .add("code", code)
                .add("grant_type", "authorization_code")
                .add("redirect_uri", redirectUri)

            if (prefs.clientSecret.isNotEmpty()) {
                formBuilder.add("client_secret", prefs.clientSecret)
            }

            val request = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(formBuilder.build())
                .build()

            val response = HttpClientProvider.client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)

            if (response.isSuccessful && json.has("access_token")) {
                val accessToken = json.getString("access_token")
                val refreshToken = json.optString("refresh_token")
                val expiresIn = json.optInt("expires_in", 3600)

                prefs.accessToken = accessToken
                if (refreshToken.isNotEmpty()) {
                    prefs.refreshToken = refreshToken
                }
                prefs.tokenExpiryTime = System.currentTimeMillis() + (expiresIn * 1000L)
                Log.i(TAG, "OAuth authorization code exchanged successfully!")
                onConfigUpdated()
                true
            } else {
                Log.e(TAG, "OAuth exchange error: code=${response.code}, body=$body")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "OAuth exchange exception", e)
            false
        }
    }

    private fun handlePostSave(formData: String) {
        val pairs = formData.split("&")
        var cId = ""
        var cSecret = ""
        var rToken = ""
        for (pair in pairs) {
            val kv = pair.split("=")
            val k = URLDecoder.decode(kv.getOrNull(0) ?: "", "UTF-8")
            val v = URLDecoder.decode(kv.getOrNull(1) ?: "", "UTF-8")
            if (k == "client_id") cId = v.trim()
            if (k == "client_secret") cSecret = v.trim()
            if (k == "refresh_token") rToken = v.trim()
        }

        if (cId.isNotEmpty()) prefs.clientId = cId
        if (cSecret.isNotEmpty()) prefs.clientSecret = cSecret
        
        if (rToken.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                val ok = fetchAccessTokenWithRefreshToken(rToken)
                if (ok) {
                    onConfigUpdated()
                }
            }
        } else if (cId.isNotEmpty() && (cId != prefs.clientId || cSecret != prefs.clientSecret)) {
            prefs.clearTokens()
            onConfigUpdated()
        }
    }

    private suspend fun fetchAccessTokenWithRefreshToken(refreshToken: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val formBuilder = FormBody.Builder()
                .add("client_id", prefs.clientId)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")

            if (prefs.clientSecret.isNotEmpty()) {
                formBuilder.add("client_secret", prefs.clientSecret)
            }

            val request = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(formBuilder.build())
                .build()

            val response = HttpClientProvider.client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)

            if (response.isSuccessful && json.has("access_token")) {
                prefs.accessToken = json.getString("access_token")
                prefs.refreshToken = refreshToken
                val expiresIn = json.optInt("expires_in", 3600)
                prefs.tokenExpiryTime = System.currentTimeMillis() + (expiresIn * 1000L)
                Log.i(TAG, "Refreshed access token via direct refresh_token input successfully!")
                true
            } else {
                Log.e(TAG, "Refresh token exchange error: ${response.code}, body=$body")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Refresh token exception", e)
            false
        }
    }

    private fun renderHtml(currentId: String, currentSecret: String): String {
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <title>Google Drive TV · 账号凭据配置</title>
                <style>
                    :root {
                        --system-bg: #000000;
                        --card-bg: #1c1c1e;
                        --card-border: rgba(255, 255, 255, 0.12);
                        --accent-blue: #007aff;
                        --text-primary: #f5f5f7;
                        --text-secondary: #8e8e93;
                        --input-bg: #2c2c2e;
                    }
                    * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "PingFang SC", "Helvetica Neue", sans-serif;
                        background-color: var(--system-bg);
                        color: var(--text-primary);
                        margin: 0;
                        padding: 32px 16px;
                        display: flex;
                        justify-content: center;
                    }
                    .container {
                        width: 100%;
                        max-width: 440px;
                    }
                    .header {
                        text-align: center;
                        margin-bottom: 28px;
                    }
                    .app-badge {
                        display: inline-flex;
                        align-items: center;
                        gap: 6px;
                        background: rgba(0, 122, 255, 0.15);
                        border: 1px solid rgba(0, 122, 255, 0.3);
                        padding: 5px 14px;
                        border-radius: 999px;
                        font-size: 12px;
                        font-weight: 600;
                        color: var(--accent-blue);
                        letter-spacing: 0.5px;
                        margin-bottom: 14px;
                    }
                    h1 {
                        font-size: 24px;
                        font-weight: 700;
                        letter-spacing: -0.5px;
                        margin: 0 0 8px;
                    }
                    .subtitle {
                        font-size: 14px;
                        color: var(--text-secondary);
                        margin: 0;
                        line-height: 1.5;
                    }
                    .card {
                        background: var(--card-bg);
                        border-radius: 20px;
                        border: 1px solid var(--card-border);
                        padding: 24px;
                        box-shadow: 0 10px 30px rgba(0, 0, 0, 0.5);
                    }
                    .form-group {
                        margin: 16px 0;
                    }
                    label {
                        display: block;
                        font-size: 13px;
                        font-weight: 600;
                        color: var(--text-secondary);
                        margin-bottom: 8px;
                    }
                    input[type="text"] {
                        width: 100%;
                        background: var(--input-bg);
                        border: 1px solid rgba(255, 255, 255, 0.08);
                        border-radius: 12px;
                        padding: 14px;
                        color: #ffffff;
                        font-size: 14px;
                        outline: none;
                    }
                    .btn-save {
                        width: 100%;
                        background: var(--accent-blue);
                        color: #ffffff;
                        border: none;
                        border-radius: 12px;
                        padding: 14px;
                        font-size: 15px;
                        font-weight: 600;
                        cursor: pointer;
                        margin-top: 8px;
                    }
                    .btn-save:active {
                        opacity: 0.85;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <div class="app-badge">☁️ GOOGLE DRIVE TV</div>
                        <h1>配置 Google 账号凭据</h1>
                        <p class="subtitle">在下方填入账号凭据并保存，电视将自动登录并呈现您的相册。</p>
                    </div>

                    <div class="card">
                        <form method="POST" action="/save">
                            <div class="form-group">
                                <label>Refresh Token (刷新令牌 · 填入直接登录)</label>
                                <input type="text" name="refresh_token" placeholder="粘贴 1//04... 开头的令牌" style="font-family: monospace;">
                            </div>
                            <div class="form-group">
                                <label>Google Client ID</label>
                                <input type="text" name="client_id" value="$currentId" placeholder="xxx.apps.googleusercontent.com" required>
                            </div>
                            <div class="form-group">
                                <label>Google Client Secret</label>
                                <input type="text" name="client_secret" value="$currentSecret" placeholder="Client Secret (可选)">
                            </div>
                            <button type="submit" class="btn-save">保存凭据并同步到电视</button>
                        </form>

                        <div style="margin-top: 20px; padding: 14px; background: rgba(255, 255, 255, 0.04); border-radius: 12px; font-size: 12px; color: #8e8e93; line-height: 1.6;">
                            💡 <strong>使用说明</strong>：填入 Google Client ID、Secret 与 Refresh Token，点击保存后电视端将自动完成登录并显示相册。
                        </div>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    companion object {
        private const val TAG = "PhonePairingServer"

        private const val AUTH_SUCCESS_HTML = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>登录成功</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif;
                        background: #000000;
                        color: #f5f5f7;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        min-height: 100vh;
                        margin: 0;
                        text-align: center;
                        padding: 24px;
                    }
                    .box {
                        background: #1c1c1e;
                        border: 1px solid rgba(255,255,255,0.12);
                        border-radius: 24px;
                        padding: 40px 32px;
                        max-width: 380px;
                        box-shadow: 0 10px 40px rgba(0,0,0,0.6);
                    }
                    .icon {
                        width: 60px;
                        height: 60px;
                        background: rgba(48, 209, 88, 0.2);
                        border: 1.5px solid rgba(48, 209, 88, 0.5);
                        border-radius: 50%;
                        display: inline-flex;
                        align-items: center;
                        justify-content: center;
                        font-size: 28px;
                        color: #30d158;
                        margin-bottom: 18px;
                    }
                    h2 { font-size: 24px; font-weight: 700; margin: 0 0 10px; }
                    p { font-size: 15px; color: #8e8e93; line-height: 1.5; margin: 0; }
                </style>
            </head>
            <body>
                <div class="box">
                    <div class="icon">✓</div>
                    <h2>登录授权成功！</h2>
                    <p>电视已自动同步登录并进入相册，您可以关闭此页面直接在电视上观赏照片了。</p>
                </div>
            </body>
            </html>
        """

        private const val AUTH_ERROR_HTML = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>授权失败</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif;
                        background: #000000;
                        color: #f5f5f7;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        min-height: 100vh;
                        margin: 0;
                        text-align: center;
                        padding: 24px;
                    }
                    .box {
                        background: #1c1c1e;
                        border: 1px solid rgba(255,69,58,0.3);
                        border-radius: 24px;
                        padding: 40px 32px;
                        max-width: 380px;
                    }
                    .icon {
                        width: 60px;
                        height: 60px;
                        background: rgba(255, 69, 58, 0.2);
                        border: 1.5px solid rgba(255, 69, 58, 0.5);
                        border-radius: 50%;
                        display: inline-flex;
                        align-items: center;
                        justify-content: center;
                        font-size: 28px;
                        color: #ff453a;
                        margin-bottom: 18px;
                    }
                    h2 { font-size: 22px; font-weight: 700; margin: 0 0 10px; color: #ff453a; }
                    p { font-size: 14px; color: #8e8e93; line-height: 1.5; margin: 0; }
                </style>
            </head>
            <body>
                <div class="box">
                    <div class="icon">✕</div>
                    <h2>授权遇到问题</h2>
                    <p>请返回上一页重试，或检查电视端网络与代理连接状态。</p>
                </div>
            </body>
            </html>
        """

        private const val SUCCESS_HTML = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>保存成功</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif;
                        background: #000000;
                        color: #f5f5f7;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        min-height: 100vh;
                        margin: 0;
                        text-align: center;
                        padding: 24px;
                    }
                    .box {
                        background: #1c1c1e;
                        border: 1px solid rgba(255,255,255,0.12);
                        border-radius: 20px;
                        padding: 36px 28px;
                        max-width: 380px;
                    }
                    .icon {
                        width: 56px;
                        height: 56px;
                        background: rgba(48, 209, 88, 0.2);
                        border: 1px solid rgba(48, 209, 88, 0.4);
                        border-radius: 50%;
                        display: inline-flex;
                        align-items: center;
                        justify-content: center;
                        font-size: 26px;
                        margin-bottom: 16px;
                    }
                    h2 { font-size: 22px; font-weight: 700; margin: 0 0 10px; }
                    p { font-size: 14px; color: #8e8e93; line-height: 1.5; margin: 0; }
                </style>
            </head>
            <body>
                <div class="box">
                    <div class="icon">✓</div>
                    <h2>配置已同步到电视</h2>
                    <p>电视屏幕已自动更新，请直接查看电视完成一键扫码登录！</p>
                </div>
            </body>
            </html>
        """
    }
}

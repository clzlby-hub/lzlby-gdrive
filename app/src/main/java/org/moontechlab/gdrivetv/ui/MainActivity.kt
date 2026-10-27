package org.moontechlab.gdrivetv.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.moontechlab.gdrivetv.data.api.GoogleDriveApi
import org.moontechlab.gdrivetv.data.auth.GoogleOAuthManager
import org.moontechlab.gdrivetv.data.model.DriveItem
import org.moontechlab.gdrivetv.data.server.PhonePairingServer
import org.moontechlab.gdrivetv.data.store.PreferenceStore
import org.moontechlab.gdrivetv.ui.screens.BrowseScreen
import org.moontechlab.gdrivetv.ui.screens.LoginScreen
import org.moontechlab.gdrivetv.ui.screens.PhotoViewerScreen
import org.moontechlab.gdrivetv.ui.screens.VideoPlayerScreen
import org.moontechlab.gdrivetv.ui.theme.GDriveTvTheme

data class ViewerParams(
    val photos: List<DriveItem>,
    val initialIndex: Int,
    val isSlideshow: Boolean
)

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PreferenceStore
    private lateinit var authManager: GoogleOAuthManager
    private lateinit var api: GoogleDriveApi
    private lateinit var pairingServer: PhonePairingServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = PreferenceStore(applicationContext)
        handleAuthIntent(intent)
        authManager = GoogleOAuthManager(prefs)
        api = GoogleDriveApi(authManager)

        // Proactively refresh access token in background so user encounters zero auth latency
        if (prefs.isLoggedIn()) {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                authManager.getValidAccessToken()
            }
        }

        pairingServer = PhonePairingServer(applicationContext, prefs) {
            // When phone saves new config, start device auth flow if on login screen
            lifecycleScope.launch {
                authManager.startDeviceAuthorization()
            }
        }
        pairingServer.start()

        setContent {
            GDriveTvTheme {
                var isLoggedIn by remember { mutableStateOf(prefs.isLoggedIn()) }
                var viewerParams by remember { mutableStateOf<ViewerParams?>(null) }
                var playingVideoItem by remember { mutableStateOf<DriveItem?>(null) }

                if (!isLoggedIn) {
                    LoginScreen(
                        prefs = prefs,
                        authManager = authManager,
                        pairingServer = pairingServer,
                        onLoginSuccess = {
                            isLoggedIn = true
                        }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // BrowseScreen stays alive to keep folder stack, scroll, and preloaded state
                        BrowseScreen(
                            prefs = prefs,
                            api = api,
                            onPhotoClick = { photos, idx ->
                                viewerParams = ViewerParams(photos, idx, isSlideshow = false)
                            },
                            onVideoClick = { videoItem ->
                                playingVideoItem = videoItem
                            },
                            onStartSlideshow = { photos ->
                                viewerParams = ViewerParams(photos, 0, isSlideshow = true)
                            },
                            onLogout = {
                                authManager.resetState()
                                isLoggedIn = false
                            }
                        )

                        // Full-screen viewer overlay when photo or slideshow is active
                        viewerParams?.let { params ->
                            PhotoViewerScreen(
                                photos = params.photos,
                                initialIndex = params.initialIndex,
                                isSlideshowInitially = params.isSlideshow,
                                prefs = prefs,
                                api = api,
                                onClose = {
                                    viewerParams = null
                                }
                            )
                        }

                        // Full-screen video player overlay
                        playingVideoItem?.let { videoItem ->
                            VideoPlayerScreen(
                                item = videoItem,
                                api = api,
                                authManager = authManager,
                                onClose = {
                                    playingVideoItem = null
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthIntent(intent)
        recreate()
    }

    private fun handleAuthIntent(intent: Intent?) {
        val at = intent?.getStringExtra("access_token")
        val rt = intent?.getStringExtra("refresh_token")
        if (!rt.isNullOrEmpty() || !at.isNullOrEmpty()) {
            if (!at.isNullOrEmpty()) prefs.accessToken = at
            if (!rt.isNullOrEmpty()) prefs.refreshToken = rt
            val expiry = intent.getLongExtra("token_expiry", 0L)
            if (expiry > 0) prefs.tokenExpiryTime = expiry
            val cid = intent.getStringExtra("client_id")
            if (!cid.isNullOrEmpty()) prefs.clientId = cid
            val csec = intent.getStringExtra("client_secret")
            if (!csec.isNullOrEmpty()) prefs.clientSecret = csec
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pairingServer.stop()
    }
}

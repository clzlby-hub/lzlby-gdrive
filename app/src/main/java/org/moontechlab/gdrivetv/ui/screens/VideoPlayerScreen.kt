package org.moontechlab.gdrivetv.ui.screens

import android.content.Context
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import org.moontechlab.gdrivetv.data.api.GoogleDriveApi
import org.moontechlab.gdrivetv.data.api.HttpClientProvider
import org.moontechlab.gdrivetv.data.auth.GoogleOAuthManager
import org.moontechlab.gdrivetv.data.model.DriveItem
import org.moontechlab.gdrivetv.ui.theme.AccentBlue
import java.util.Locale

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    videos: List<DriveItem>,
    initialIndex: Int,
    api: GoogleDriveApi,
    authManager: GoogleOAuthManager,
    onClose: () -> Unit
) {
    if (videos.isEmpty()) {
        onClose()
        return
    }

    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    var currentIndex by remember {
        mutableIntStateOf(initialIndex.coerceIn(0, (videos.size - 1).coerceAtLeast(0)))
    }
    val currentItem = videos.getOrNull(currentIndex) ?: run {
        onClose()
        return
    }

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var bufferedPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }
    var isAutoAdvancing by remember { mutableStateOf(false) }

    val player = remember {
        // Plan A: Lightweight adaptive buffer (quick 1s start, 1.5s rebuffer recovery, dynamic memory allocation)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = (playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_READY) {
                    duration = player.duration.coerceAtLeast(0L)
                    errorMessage = null
                    isAutoAdvancing = false
                } else if (playbackState == Player.STATE_ENDED) {
                    isPlaying = false
                    if (currentIndex < videos.size - 1) {
                        isAutoAdvancing = true
                    } else {
                        showControls = true
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                isAutoAdvancing = false
                errorMessage = "视频播放失败: ${error.localizedMessage ?: "网络或解码错误"}"
            }
        }
        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
            player.stop()
            player.release()
        }
    }

    // Auto update position & buffer every 400ms
    LaunchedEffect(player) {
        while (true) {
            currentPosition = player.currentPosition.coerceAtLeast(0L)
            bufferedPosition = player.bufferedPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(400L)
        }
    }

    // Auto hide controls after 4 seconds if playing
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(4000L)
            showControls = false
        }
    }

    // Auto-advance loop when video reaches the end
    LaunchedEffect(isAutoAdvancing, currentIndex) {
        if (isAutoAdvancing) {
            delay(1200L)
            if (isAutoAdvancing && currentIndex < videos.size - 1) {
                currentIndex++
            }
            isAutoAdvancing = false
        }
    }

    // Prepare media source with OkHttp proxy stream whenever currentItem changes
    LaunchedEffect(currentItem.id) {
        isAutoAdvancing = false
        isBuffering = true
        errorMessage = null
        currentPosition = 0L
        bufferedPosition = 0L
        duration = 0L
        showControls = true
        try {
            val (url, headers) = if (currentItem.thumbnailLink?.contains("/pw/") == true) {
                val base = currentItem.thumbnailLink.substringBefore("=")
                Pair("$base=m37", emptyMap<String, String>())
            } else {
                val token = authManager.getValidAccessToken() ?: ""
                Pair(api.getHighResMediaUrl(currentItem.id), mapOf("Authorization" to "Bearer $token"))
            }

            val dataSourceFactory = OkHttpDataSource.Factory(HttpClientProvider.client)
                .setUserAgent("Mozilla/5.0 (Android TV; GDriveTV/1.0)")
                .setDefaultRequestProperties(headers)

            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(url))

            player.stop()
            player.setMediaSource(mediaSource, /* resetPosition = */ true)
            player.prepare()
            player.play()
        } catch (e: Exception) {
            errorMessage = "加载视频出错: ${e.message}"
            isBuffering = false
        }
    }

    LaunchedEffect(Unit) {
        delay(150)
        focusRequester.requestFocus()
    }

    BackHandler {
        onClose()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    showControls = true
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (isAutoAdvancing) {
                                isAutoAdvancing = false
                                player.seekTo(0L)
                                player.play()
                            } else if (player.isPlaying) {
                                player.pause()
                            } else {
                                if (player.playbackState == Player.STATE_ENDED) {
                                    player.seekTo(0L)
                                }
                                player.play()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            isAutoAdvancing = false
                            val target = (player.currentPosition - 10000L).coerceAtLeast(0L)
                            player.seekTo(target)
                            currentPosition = target
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            isAutoAdvancing = false
                            val max = if (player.duration > 0) player.duration else Long.MAX_VALUE
                            val target = (player.currentPosition + 10000L).coerceAtMost(max)
                            player.seekTo(target)
                            currentPosition = target
                            true
                        }
                        // Next video: DPAD_DOWN or dedicated remote next buttons
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_MEDIA_NEXT,
                        KeyEvent.KEYCODE_CHANNEL_DOWN,
                        KeyEvent.KEYCODE_PAGE_DOWN -> {
                            isAutoAdvancing = false
                            if (currentIndex < videos.size - 1) {
                                currentIndex++
                                true
                            } else {
                                true
                            }
                        }
                        // Previous video: DPAD_UP or dedicated remote previous buttons
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                        KeyEvent.KEYCODE_CHANNEL_UP,
                        KeyEvent.KEYCODE_PAGE_UP -> {
                            isAutoAdvancing = false
                            if (currentIndex > 0) {
                                currentIndex--
                                true
                            } else {
                                true
                            }
                        }
                        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_STOP -> {
                            onClose()
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        // Video Surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Buffering spinner
        if (isBuffering && errorMessage == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = AccentBlue,
                    modifier = Modifier.size(54.dp),
                    strokeWidth = 3.dp
                )
            }
        }

        // Floating Auto-Advance Next Toast Banner
        AnimatedVisibility(
            visible = isAutoAdvancing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color(0xD90B1120),
                border = BorderStroke(0.8.dp, Color(0x4DFFFFFF))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "即将播放下一段 (${currentIndex + 2}/${videos.size}): ${videos.getOrNull(currentIndex + 1)?.name ?: ""}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Error message
        errorMessage?.let { err ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .background(Color(0xCC1E293B), RoundedCornerShape(12.dp))
                        .padding(24.dp)
                ) {
                    Text(text = "⚠️ 无法播放该视频", color = Color(0xFFF87171), fontSize = 20.sp)
                    Text(text = err, color = Color.LightGray, fontSize = 14.sp)
                    Text(text = "按遥控器【返回键】退出，或按【▼】尝试播放下一段", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }

        // Apple TV Style Compact Media HUD Overlay
        AnimatedVisibility(
            visible = showControls || !isPlaying,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 520.dp, max = 740.dp)
                    .fillMaxWidth(0.56f)
                    .background(Color(0xD90B1120), RoundedCornerShape(14.dp))
                    .border(BorderStroke(1.dp, Color(0x33FFFFFF)), RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Title and Apple-style frosted status pill + Index badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Text(
                                text = currentItem.name,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (videos.size > 1) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0x26FFFFFF)
                                ) {
                                    Text(
                                        text = "${currentIndex + 1}/${videos.size}",
                                        color = Color(0xFFCBD5E1),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0x26FFFFFF),
                            border = BorderStroke(0.8.dp, Color(0x4DFFFFFF))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "暂停" else "播放",
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = if (isPlaying) "播放中" else "已暂停",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Progress bar with buffered track
                    val progress = if (duration > 0) {
                        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    val bufferedProgress = if (duration > 0) {
                        (bufferedPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0x26FFFFFF), RoundedCornerShape(2.dp))
                    ) {
                        // Buffered progress (subtle translucent white ahead)
                        if (bufferedProgress > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction = bufferedProgress)
                                    .fillMaxHeight()
                                    .background(Color(0x59FFFFFF), RoundedCornerShape(2.dp))
                            )
                        }
                        // Played progress (pure bright white)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = progress)
                                .fillMaxHeight()
                                .background(Color.White, RoundedCornerShape(2.dp))
                        )
                    }

                    // Time display & Remote hints
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatDuration(currentPosition)} / ${formatDuration(duration)}",
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        val navHint = if (videos.size > 1) " • ▼/▲ 切集" else ""
                        Text(
                            text = "OK 暂停 • ◀/▶ 快退进$navHint • 返回 退出",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
}

package org.moontechlab.gdrivetv.ui.screens

import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.Coil
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.moontechlab.gdrivetv.data.api.GoogleDriveApi
import org.moontechlab.gdrivetv.data.model.DriveItem
import org.moontechlab.gdrivetv.data.store.PreferenceStore
import org.moontechlab.gdrivetv.ui.theme.AccentBlue

@Composable
fun PhotoViewerScreen(
    photos: List<DriveItem>,
    initialIndex: Int,
    isSlideshowInitially: Boolean,
    prefs: PreferenceStore,
    api: GoogleDriveApi,
    onClose: () -> Unit
) {
    val photoList = remember(photos) { photos.toMutableStateList() }
    if (photoList.isEmpty()) {
        onClose()
        return
    }

    var currentIndex by remember { mutableIntStateOf(initialIndex.coerceIn(0, photoList.size - 1)) }
    var isPlaying by remember { mutableStateOf(isSlideshowInitially) }
    var showOverlay by remember { mutableStateOf(true) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    BackHandler {
        onClose()
    }

    // Auto-advance loop for slideshow
    LaunchedEffect(isPlaying, currentIndex, photoList.size) {
        if (isPlaying && photoList.isNotEmpty()) {
            delay(prefs.slideshowIntervalSeconds * 1000L)
            currentIndex = (currentIndex + 1) % photoList.size
        }
    }

    // Auto-hide overlay after 4 seconds of inactivity
    LaunchedEffect(showOverlay, currentIndex, isPlaying) {
        if (showOverlay) {
            delay(4000L)
            showOverlay = false
        }
    }

    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
    }

    val currentPhoto = photoList.getOrNull(currentIndex) ?: run {
        onClose()
        return
    }

    // Google Drive / Google Photos high-res 4K JPEG URL
    val highResUrl = remember(currentPhoto.id) { getPhotoHighResUrl(currentPhoto, api) }

    // Pipelined sequential background preloading up to N+3 ahead (and N-1 behind)
    // 1. N+1 is loaded first (full bandwidth, cached to both RAM & disk for 0ms flip)
    // 2. N-1 is loaded second (cached to RAM & disk)
    // 3. N+2 and N+3 are loaded sequentially strictly to Disk Cache only (0 RAM heap waste, protecting TV memory)
    val imageLoader = remember { Coil.imageLoader(context) }
    LaunchedEffect(currentIndex, photoList.size) {
        if (photoList.isNotEmpty()) {
            val total = photoList.size

            // Step 1: Preload N+1 (RAM + Disk)
            val nextIdx1 = (currentIndex + 1) % total
            if (nextIdx1 != currentIndex) {
                val nextUrl1 = getPhotoHighResUrl(photoList[nextIdx1], api)
                imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(nextUrl1)
                        .build()
                )
            }

            // Step 2: Preload N-1 (RAM + Disk)
            val prevIdx = if (currentIndex > 0) currentIndex - 1 else total - 1
            if (prevIdx != nextIdx1 && prevIdx != currentIndex) {
                val prevUrl = getPhotoHighResUrl(photoList[prevIdx], api)
                imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(prevUrl)
                        .build()
                )
            }

            // Step 3: Preload N+2 (Disk Cache only - 0 RAM heap consumption)
            if (total > 2) {
                val nextIdx2 = (currentIndex + 2) % total
                if (nextIdx2 != currentIndex && nextIdx2 != prevIdx) {
                    val nextUrl2 = getPhotoHighResUrl(photoList[nextIdx2], api)
                    imageLoader.execute(
                        ImageRequest.Builder(context)
                            .data(nextUrl2)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .build()
                    )
                }
            }

            // Step 4: Preload N+3 (Disk Cache only - 0 RAM heap consumption)
            if (total > 3) {
                val nextIdx3 = (currentIndex + 3) % total
                if (nextIdx3 != currentIndex && nextIdx3 != prevIdx) {
                    val nextUrl3 = getPhotoHighResUrl(photoList[nextIdx3], api)
                    imageLoader.execute(
                        ImageRequest.Builder(context)
                            .data(nextUrl3)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .build()
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    showOverlay = true
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            currentIndex = if (currentIndex > 0) currentIndex - 1 else photoList.size - 1
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            currentIndex = (currentIndex + 1) % photoList.size
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                            isPlaying = false
                            showDeleteConfirmDialog = true
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            isPlaying = !isPlaying
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        // High-res photo display with loading and error states
        SubcomposeAsyncImage(
            model = highResUrl,
            contentDescription = currentPhoto.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            loading = {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = AccentBlue,
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 3.dp
                    )
                }
            },
            error = {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "⚠️ 图片无法加载", color = Color(0xFFF87171), fontSize = 18.sp)
                        Text(text = currentPhoto.name, color = Color.Gray, fontSize = 14.sp)
                    }
                }
            }
        )

        // Overlay HUD with photo info, slideshow status and remote hints
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart)
        ) {
            Box(
                modifier = Modifier
                    .padding(32.dp)
                    .background(Color(0xD90F172A), shape = RoundedCornerShape(12.dp))
                    .border(BorderStroke(1.dp, Color(0x33FFFFFF)), shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Color(0x33FFFFFF),
                        border = BorderStroke(1.dp, Color(0x66FFFFFF))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (isPlaying) "轮播中 (${prefs.slideshowIntervalSeconds}s)" else "已暂停",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Text(
                        text = "${currentIndex + 1} / ${photoList.size}",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = currentPhoto.name,
                        color = Color(0xFFE2E8F0),
                        fontSize = 15.sp,
                        maxLines = 1
                    )
                    Text(
                        text = "•  ◀ / ▶ 翻页  •  OK 播放/暂停  •  ▼ 移入回收站  •  返回 退出",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                }
            }
        }

        // Apple TV Style Delete Confirm Dialog
        if (showDeleteConfirmDialog) {
            val cancelRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                delay(150)
                cancelRequester.requestFocus()
            }
            Dialog(onDismissRequest = { if (!isDeleting) showDeleteConfirmDialog = false }) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                    modifier = Modifier.width(460.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "移入 Google 硬盘回收站",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "确定将《${currentPhoto.name}》移入回收站吗？\n(可在电脑或手机 Google 硬盘回收站保留 30 天并随时恢复)",
                            color = Color(0xFFCBD5E1),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            DeleteDialogActionButton(
                                text = "取消",
                                isDestructive = false,
                                focusRequester = cancelRequester,
                                onClick = { showDeleteConfirmDialog = false },
                                modifier = Modifier.weight(1f)
                            )

                            DeleteDialogActionButton(
                                text = if (isDeleting) "正在处理…" else "移入回收站",
                                isDestructive = true,
                                enabled = !isDeleting,
                                onClick = {
                                    isDeleting = true
                                    scope.launch {
                                        val res = api.moveToTrash(currentPhoto.id)
                                        isDeleting = false
                                        showDeleteConfirmDialog = false
                                        if (res.isSuccess) {
                                            Toast.makeText(context, "已移入云端硬盘回收站", Toast.LENGTH_SHORT).show()
                                            photoList.removeAt(currentIndex)
                                            if (photoList.isEmpty()) {
                                                onClose()
                                            } else {
                                                currentIndex = currentIndex.coerceIn(0, photoList.size - 1)
                                            }
                                        } else {
                                            val err = res.exceptionOrNull()?.message ?: "移入回收站失败"
                                            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteDialogActionButton(
    text: String,
    isDestructive: Boolean,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        label = "btnScale"
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = when {
            isFocused -> BorderStroke(2.5.dp, Color.White)
            isDestructive -> BorderStroke(1.dp, Color(0x44EF4444))
            else -> BorderStroke(1.dp, Color(0x33FFFFFF))
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                isFocused && isDestructive -> Color(0xFFEF4444)
                isFocused -> Color.White
                isDestructive -> Color(0x2BEE4444)
                else -> Color(0x26FFFFFF)
            },
            contentColor = when {
                isFocused && isDestructive -> Color.White
                isFocused -> Color.Black
                isDestructive -> Color(0xFFFCA5A5)
                else -> Color.White
            },
            disabledContainerColor = Color(0x1AFFFFFF),
            disabledContentColor = Color(0x4DFFFFFF)
        ),
        contentPadding = PaddingValues(vertical = 12.dp),
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold
        )
    }
}

private fun getPhotoHighResUrl(item: DriveItem, api: GoogleDriveApi): String {
    return when {
        item.thumbnailLink?.contains("/pw/") == true -> {
            val base = item.thumbnailLink.substringBefore("=")
            "$base=w2560-h1440"
        }
        item.thumbnailLink?.contains("=s220") == true -> {
            item.thumbnailLink.replace("=s220", "=s2560")
        }
        else -> item.thumbnailLink ?: api.getHighResMediaUrl(item.id)
    }
}

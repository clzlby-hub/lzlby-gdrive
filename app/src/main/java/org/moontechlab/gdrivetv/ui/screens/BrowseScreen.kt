package org.moontechlab.gdrivetv.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.moontechlab.gdrivetv.data.api.GoogleDriveApi
import org.moontechlab.gdrivetv.data.model.DriveItem
import org.moontechlab.gdrivetv.data.store.PreferenceStore
import org.moontechlab.gdrivetv.ui.theme.*

data class FolderBreadcrumb(val id: String, val name: String)

enum class FolderSortOption(val label: String) {
    YEAR_DESC("年份最新"),
    YEAR_ASC("年份最早"),
    NAME_ASC("按名称 A-Z"),
    TIME_DESC("最近修改时间")
}

enum class PhotoSortOption(val label: String) {
    DATE_DESC("拍摄时间最新"),
    DATE_ASC("拍摄时间最早")
}

private fun parseBreadcrumbs(jsonStr: String): List<FolderBreadcrumb>? {
    if (jsonStr.isBlank()) return null
    return try {
        val arr = JSONArray(jsonStr)
        val list = mutableListOf<FolderBreadcrumb>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(FolderBreadcrumb(obj.getString("id"), obj.getString("name")))
        }
        if (list.isNotEmpty()) list else null
    } catch (_: Exception) {
        null
    }
}

private fun serializeBreadcrumbs(breadcrumbs: List<FolderBreadcrumb>): String {
    val arr = JSONArray()
    for (b in breadcrumbs) {
        val obj = JSONObject()
        obj.put("id", b.id)
        obj.put("name", b.name)
        arr.put(obj)
    }
    return arr.toString()
}

@Composable
fun BrowseScreen(
    prefs: PreferenceStore,
    api: GoogleDriveApi,
    onPhotoClick: (items: List<DriveItem>, startIndex: Int) -> Unit,
    onVideoClick: (videos: List<DriveItem>, initialIndex: Int) -> Unit,
    onStartSlideshow: (items: List<DriveItem>) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Restore last visited folder breadcrumbs or start at root
    val initialBreadcrumbs = remember {
        val saved = prefs.lastFolderBreadcrumbs
        val parsed = parseBreadcrumbs(saved)
        if (parsed != null && parsed.isNotEmpty()) {
            parsed
        } else {
            listOf(FolderBreadcrumb("root", "全部文件"))
        }
    }
    val folderStack = remember { mutableStateListOf<FolderBreadcrumb>().apply { addAll(initialBreadcrumbs) } }
    val currentFolder = folderStack.last()

    // Memory & disk cached folder items for 0ms instant display without spinning indicator
    val cachedInitial = remember(currentFolder.id) { api.getCachedFolder(currentFolder.id) }
    var driveItems by remember(currentFolder.id) { mutableStateOf<List<DriveItem>>(cachedInitial?.files ?: emptyList()) }
    var isDriveLoading by remember(currentFolder.id) { mutableStateOf(driveItems.isEmpty()) }
    var driveError by remember(currentFolder.id) { mutableStateOf<String?>(null) }

    // Instant real-time sync when a photo is deleted from the viewer
    LaunchedEffect(Unit) {
        api.deletedItemEvents.collect { deletedId ->
            driveItems = driveItems.filter { it.id != deletedId }
        }
    }

    val firstItemFocusRequester = remember { FocusRequester() }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }

    var folderSort by remember { mutableStateOf(FolderSortOption.YEAR_DESC) }
    var photoSort by remember { mutableStateOf(PhotoSortOption.DATE_DESC) }

    // Month & Day filter states for photos (reset when folder changes)
    var selectedMonth by remember(currentFolder.id) { mutableStateOf<Int?>(null) }
    var selectedDay by remember(currentFolder.id, selectedMonth) { mutableStateOf<Int?>(null) }

    val allMedia = remember(driveItems) { driveItems.filter { it.isMedia } }
    val isMediaFolder = remember(driveItems) { allMedia.isNotEmpty() }

    // Fast O(N) grouping computed once
    val mediaByMonth = remember(allMedia) {
        allMedia.groupBy { it.dateInfo?.month }
    }
    val availableMonths = remember(mediaByMonth) {
        mediaByMonth.keys.filterNotNull().sorted()
    }

    val currentMonthMedia = remember(allMedia, selectedMonth, mediaByMonth) {
        if (selectedMonth != null) {
            mediaByMonth[selectedMonth] ?: emptyList()
        } else {
            allMedia
        }
    }

    val mediaByDay = remember(currentMonthMedia) {
        currentMonthMedia.groupBy { it.dateInfo?.day }
    }
    val availableDays = remember(mediaByDay) {
        mediaByDay.keys.filterNotNull().sorted()
    }

    val displayedItems = remember(
        driveItems,
        isMediaFolder,
        currentMonthMedia,
        selectedDay,
        folderSort,
        photoSort
    ) {
        if (isMediaFolder) {
            val media = if (selectedDay != null) {
                mediaByDay[selectedDay] ?: emptyList()
            } else {
                currentMonthMedia
            }
            val sortedMedia = when (photoSort) {
                PhotoSortOption.DATE_DESC -> media.sortedWith(compareByDescending { it.sortKey })
                PhotoSortOption.DATE_ASC -> media.sortedWith(compareBy { it.sortKey })
            }
            val folders = driveItems.filter { it.isFolder }
            folders + sortedMedia
        } else {
            when (folderSort) {
                FolderSortOption.YEAR_DESC -> {
                    driveItems.sortedWith(
                        compareByDescending<DriveItem> { it.folderYear ?: -1 }
                            .thenBy { it.name }
                    )
                }
                FolderSortOption.YEAR_ASC -> {
                    driveItems.sortedWith(
                        compareBy<DriveItem> { it.folderYear ?: 9999 }
                            .thenBy { it.name }
                    )
                }
                FolderSortOption.NAME_ASC -> driveItems.sortedBy { it.name }
                FolderSortOption.TIME_DESC -> driveItems.sortedByDescending { it.modifiedTime ?: "" }
            }
        }
    }

    fun loadCurrentDriveFolder() {
        val cached = api.getCachedFolder(currentFolder.id)
        if (cached != null && cached.files.isNotEmpty()) {
            driveItems = cached.files
            isDriveLoading = false
        } else {
            isDriveLoading = true
        }
        driveError = null
        scope.launch {
            val res = api.listFolder(currentFolder.id)
            isDriveLoading = false
            res.onSuccess {
                driveItems = it.files
            }.onFailure {
                if (driveItems.isEmpty()) {
                    driveError = it.message ?: "读取文件夹失败"
                }
            }
        }
    }

    LaunchedEffect(currentFolder.id) {
        loadCurrentDriveFolder()
    }

    // Auto-focus the first album item ONLY ONCE when entering folder or items load, preventing focus jumping
    var hasInitiallyFocused by remember(currentFolder.id) { mutableStateOf(false) }
    LaunchedEffect(currentFolder.id, displayedItems.isNotEmpty()) {
        if (!hasInitiallyFocused && displayedItems.isNotEmpty()) {
            hasInitiallyFocused = true
            delay(80)
            try {
                firstItemFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    // Smart Back Button: Navigates up directories, and prevents accidental launcher exit
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    BackHandler {
        if (folderStack.size > 1) {
            folderStack.removeAt(folderStack.size - 1)
            prefs.lastFolderBreadcrumbs = serializeBreadcrumbs(folderStack)
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBackPressTime < 2000L) {
                (context as? Activity)?.finish()
            } else {
                lastBackPressTime = now
                Toast.makeText(context, "再按一次返回键退出相册应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark)
                .padding(horizontal = 36.dp, vertical = 22.dp)
        ) {
            // Top Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Title with cloud icon and folder breadcrumbs
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Cloud,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "Google 硬盘",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                    Text(text = "›", color = TextSecondary, fontSize = 20.sp)
                    Text(
                        text = if (currentFolder.id == "root") "全部文件" else currentFolder.name,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )
                }

                // Push action buttons to the right
                Spacer(modifier = Modifier.weight(1f))

                // Right Action Buttons: Pure, minimal, and exquisite
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Button 1: Sort Option
                    TopActionButton(
                        icon = Icons.Outlined.SwapVert,
                        label = if (isMediaFolder) {
                            when (photoSort) {
                                PhotoSortOption.DATE_DESC -> "时间最新"
                                PhotoSortOption.DATE_ASC -> "时间最早"
                            }
                        } else {
                            when (folderSort) {
                                FolderSortOption.YEAR_DESC -> "年份最新"
                                FolderSortOption.YEAR_ASC -> "年份最早"
                                FolderSortOption.NAME_ASC -> "名称 A-Z"
                                FolderSortOption.TIME_DESC -> "最近修改"
                            }
                        },
                        canFocus = !isDriveLoading,
                        onClick = { showSortDialog = true }
                    )

                    // Button 2: Set as Screensaver
                    val currentImages = remember(displayedItems) { displayedItems.filter { it.isImage } }
                    val screensaverLabel = remember(selectedMonth, selectedDay) {
                        when {
                            selectedMonth != null && selectedDay != null -> "设为屏保 (${selectedMonth}月${selectedDay}日)"
                            selectedMonth != null -> "设为屏保 (${selectedMonth}月)"
                            else -> "设为屏保"
                        }
                    }
                    TopActionButton(
                        icon = Icons.Outlined.Tv,
                        label = screensaverLabel,
                        canFocus = !isDriveLoading,
                        onClick = {
                            prefs.screensaverSource = "drive"
                            prefs.screensaverFolderId = currentFolder.id
                            prefs.screensaverFolderName = currentFolder.name
                            prefs.screensaverMonth = selectedMonth
                            prefs.screensaverDay = selectedDay
                            val toastMsg = when {
                                selectedMonth != null && selectedDay != null ->
                                    "已将【${currentFolder.name} ${selectedMonth}月${selectedDay}日 (${currentImages.size}张)】设为电视屏保！"
                                selectedMonth != null ->
                                    "已将【${currentFolder.name} ${selectedMonth}月 (${currentImages.size}张)】设为电视屏保！"
                                else ->
                                    "已将【${currentFolder.name}】设为电视屏保！"
                            }
                            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                        }
                    )

                    // Button 3: Slideshow
                    if (currentImages.isNotEmpty()) {
                        TopActionButton(
                            icon = Icons.Outlined.Slideshow,
                            label = if (selectedMonth != null || selectedDay != null) "播放筛选 (${currentImages.size})" else "幻灯片播放",
                            canFocus = !isDriveLoading,
                            onClick = { onStartSlideshow(currentImages) }
                        )
                    }

                    // Button 4: Logout / Switch Account
                    TopActionButton(
                        icon = Icons.Outlined.Logout,
                        label = "退出登录",
                        canFocus = !isDriveLoading,
                        onClick = { showLogoutDialog = true }
                    )
                }
            }

            // Apple TV Style Month & Day Filter Bar (shown when inside media folders with months or days)
            if (!isDriveLoading && isMediaFolder && (availableMonths.size > 1 || availableDays.size > 1)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Month selector row
                    if (availableMonths.size > 1 || (availableMonths.size == 1 && selectedMonth != null)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "月份",
                                color = Color(0xFF8E8E93),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 4.dp, end = 2.dp)
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                item {
                                    AppleFilterPill(
                                        label = "全部",
                                        count = allMedia.size,
                                        isSelected = selectedMonth == null,
                                        onClick = {
                                            selectedMonth = null
                                            selectedDay = null
                                        }
                                    )
                                }
                                items(availableMonths) { m ->
                                    val count = mediaByMonth[m]?.size ?: 0
                                    AppleFilterPill(
                                        label = "${m}月",
                                        count = count,
                                        isSelected = selectedMonth == m,
                                        onClick = {
                                            selectedMonth = if (selectedMonth == m) null else m
                                            selectedDay = null
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Day selector row
                    if (availableDays.size > 1) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "日期",
                                color = Color(0xFF8E8E93),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 4.dp, end = 2.dp)
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                item {
                                    AppleFilterPill(
                                        label = "全部日期",
                                        count = currentMonthMedia.size,
                                        isSelected = selectedDay == null,
                                        onClick = { selectedDay = null }
                                    )
                                }
                                items(availableDays) { d ->
                                    val count = mediaByDay[d]?.size ?: 0
                                    AppleFilterPill(
                                        label = "${d}日",
                                        count = count,
                                        isSelected = selectedDay == d,
                                        onClick = {
                                            selectedDay = if (selectedDay == d) null else d
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Main Content Area
            if (isDriveLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentGreen)
                }
            } else if (driveError != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(text = "⚠️ $driveError", color = Color(0xFFF87171), fontSize = 18.sp)
                        Button(
                            onClick = { loadCurrentDriveFolder() },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            Text("重试", color = Color.Black)
                        }
                    }
                }
            } else if (driveItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            tint = Color.DarkGray,
                            modifier = Modifier.size(56.dp)
                        )
                        Text(text = "此文件夹暂无文件", color = TextSecondary, fontSize = 18.sp)
                    }
                }
            } else if (displayedItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Outlined.FilterList,
                            contentDescription = null,
                            tint = Color.DarkGray,
                            modifier = Modifier.size(56.dp)
                        )
                        Text(text = "当前筛选日期无照片，请切换其他月份或日期", color = TextSecondary, fontSize = 18.sp)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 36.dp, start = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(displayedItems, key = { _, item -> item.id }) { index, item ->
                        DriveItemCard(
                            item = item,
                            modifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier,
                            onClick = {
                                if (item.isFolder) {
                                    folderStack.add(FolderBreadcrumb(item.id, item.name))
                                    prefs.lastFolderBreadcrumbs = serializeBreadcrumbs(folderStack)
                                } else if (item.isImage) {
                                    val images = displayedItems.filter { it.isImage }
                                    val idx = images.indexOf(item).coerceAtLeast(0)
                                    onPhotoClick(images, idx)
                                } else if (item.isVideo) {
                                    val videos = displayedItems.filter { it.isVideo }
                                    val idx = videos.indexOf(item).coerceAtLeast(0)
                                    onVideoClick(videos, idx)
                                }
                            }
                        )
                    }
                }
            }
        }

        // Apple TV Style Sort Dialog
        if (showSortDialog) {
            AppleTvSortDialog(
                isPhotoFolder = isMediaFolder,
                currentFolderSort = folderSort,
                currentPhotoSort = photoSort,
                onSelectFolderSort = { folderSort = it },
                onSelectPhotoSort = { photoSort = it },
                onDismiss = { showSortDialog = false }
            )
        }

        // Apple TV Style Logout Confirmation Dialog
        if (showLogoutDialog) {
            AppleTvLogoutDialog(
                onConfirm = {
                    showLogoutDialog = false
                    prefs.clearTokens()
                    api.clearCache()
                    onLogout()
                },
                onDismiss = {
                    showLogoutDialog = false
                }
            )
        }
    }
}

@Composable
fun TopActionButton(
    icon: ImageVector?,
    label: String,
    canFocus: Boolean = true,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .height(38.dp)
            .defaultMinSize(minWidth = 120.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) AccentGreen else CardDark)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) Color.White else Color(0x33FFFFFF),
                shape = RoundedCornerShape(8.dp)
            )
            .focusProperties {
                this.canFocus = canFocus
            }
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(enabled = canFocus) { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isFocused) Color.Black else Color.White
                )
            }
            Text(
                text = label,
                color = if (isFocused) Color.Black else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun DriveItemCard(
    item: DriveItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.06f else 1.0f, label = "card_scale")

    Card(
        modifier = modifier
            .zIndex(if (isFocused) 5f else 0f)
            .fillMaxWidth()
            .aspectRatio(1.2f)
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .border(
                width = if (isFocused) 3.dp else 1.dp,
                color = if (isFocused) AccentGreen else Color(0x22FFFFFF),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) CardFocused else CardDark
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (item.isFolder) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = "Folder",
                        tint = if (isFocused) AccentGreen else Color(0xFFE2E8F0),
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = item.name,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else if (item.isImage) {
                val thumbUrl = item.thumbnailLink ?: ""
                SubcomposeAsyncImage(
                    model = thumbUrl,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(
                            modifier = Modifier.fillMaxSize().background(CardDark),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = AccentGreen,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    },
                    error = {
                        Box(
                            modifier = Modifier.fillMaxSize().background(CardDark),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Image,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                )
                // Bottom label overlay on focus
                if (isFocused) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color(0xCC000000))
                            .padding(6.dp)
                    ) {
                        Text(
                            text = item.name,
                            color = Color.White,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else if (item.isVideo) {
                val thumbUrl = item.thumbnailLink ?: ""
                SubcomposeAsyncImage(
                    model = thumbUrl,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(
                            modifier = Modifier.fillMaxSize().background(CardDark),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = AccentGreen,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    },
                    error = {
                        Box(
                            modifier = Modifier.fillMaxSize().background(CardDark),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Outlined.Movie,
                                    contentDescription = null,
                                    tint = Color(0xFF8E8E93),
                                    modifier = Modifier.size(36.dp)
                                )
                                if (thumbUrl.isEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "云端转码中",
                                        color = Color(0xFFAAAAAA),
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                )
                // Center circular play badge
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color(0x99000000), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "播放视频",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                if (isFocused) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color(0xCC000000))
                            .padding(6.dp)
                    ) {
                        Text(
                            text = item.name,
                            color = Color.White,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppleTvLogoutDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }

    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        try {
            cancelFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xE61C1C1E))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xE61C1C1E))
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Circular Apple-style badge
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color(0x22FF453A), CircleShape)
                        .border(1.dp, Color(0x44FF453A), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Logout,
                        contentDescription = null,
                        tint = Color(0xFFFF453A),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Text(
                    text = "退出登录",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    text = "确定退出当前 Google 账号吗？退出后可使用手机扫码更换新账号登录。",
                    fontSize = 14.sp,
                    color = Color(0xFFA1A1A6),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Cancel Button
                    AppleTvDialogButton(
                        text = "取消",
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(cancelFocusRequester),
                        isDestructive = false,
                        onClick = onDismiss
                    )

                    // Confirm Logout Button
                    AppleTvDialogButton(
                        text = "退出登录",
                        modifier = Modifier.weight(1f),
                        isDestructive = true,
                        onClick = onConfirm
                    )
                }
            }
        }
    }
}

@Composable
fun AppleTvDialogButton(
    text: String,
    modifier: Modifier = Modifier,
    isDestructive: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    isFocused && isDestructive -> Color(0xFFFF453A)
                    isFocused -> Color.White
                    isDestructive -> Color(0x22FF453A)
                    else -> Color(0x22FFFFFF)
                }
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = when {
                    isFocused && isDestructive -> Color.White
                    isFocused -> Color.White
                    isDestructive -> Color(0x44FF453A)
                    else -> Color(0x26FFFFFF)
                },
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = when {
                isFocused && isDestructive -> Color.White
                isFocused -> Color.Black
                isDestructive -> Color(0xFFFF6961)
                else -> Color.White
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun AppleFilterPill(
    label: String,
    count: Int? = null,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val bgColor = when {
        isFocused -> Color.White
        isSelected -> Color(0xFF2997FF)
        else -> Color(0x18FFFFFF)
    }

    val textColor = when {
        isFocused -> Color.Black
        isSelected -> Color.White
        else -> Color(0xFFA1A1A6)
    }

    val borderColor = when {
        isFocused -> Color.White
        isSelected -> Color(0xFF2997FF)
        else -> Color(0x28FFFFFF)
    }

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = bgColor,
        border = BorderStroke(if (isFocused) 2.dp else 1.dp, borderColor),
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .height(34.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                color = textColor,
                fontSize = 13.sp,
                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium
            )
            if (count != null && count > 0) {
                Text(
                    text = "$count",
                    color = if (isFocused) Color(0xFF475569) else if (isSelected) Color(0xCCFFFFFF) else Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun AppleTvSortDialog(
    isPhotoFolder: Boolean,
    currentFolderSort: FolderSortOption,
    currentPhotoSort: PhotoSortOption,
    onSelectFolderSort: (FolderSortOption) -> Unit,
    onSelectPhotoSort: (PhotoSortOption) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    val firstOptionRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        try {
            firstOptionRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xFF1C1C1E))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(26.dp)),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E))
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SwapVert,
                        contentDescription = null,
                        tint = Color(0xFF2997FF),
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = if (isPhotoFolder) "照片排序方式" else "文件夹排序方式",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                HorizontalDivider(color = Color(0x18FFFFFF))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isPhotoFolder) {
                        PhotoSortOption.values().forEachIndexed { index, option ->
                            val isSelected = option == currentPhotoSort
                            SortOptionItem(
                                title = option.label,
                                isSelected = isSelected,
                                modifier = if (index == 0) Modifier.focusRequester(firstOptionRequester) else Modifier,
                                onClick = {
                                    onSelectPhotoSort(option)
                                    onDismiss()
                                }
                            )
                        }
                    } else {
                        FolderSortOption.values().forEachIndexed { index, option ->
                            val isSelected = option == currentFolderSort
                            SortOptionItem(
                                title = option.label,
                                isSelected = isSelected,
                                modifier = if (index == 0) Modifier.focusRequester(firstOptionRequester) else Modifier,
                                onClick = {
                                    onSelectFolderSort(option)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortOptionItem(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = when {
            isFocused -> Color.White
            isSelected -> Color(0x222997FF)
            else -> Color(0x0EFFFFFF)
        },
        border = BorderStroke(
            if (isFocused) 2.dp else 1.dp,
            when {
                isFocused -> Color.White
                isSelected -> Color(0xFF2997FF)
                else -> Color(0x14FFFFFF)
            }
        ),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .height(50.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                color = if (isFocused) Color.Black else if (isSelected) Color(0xFF2997FF) else Color.White,
                fontSize = 15.sp,
                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal
            )
            if (isSelected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = if (isFocused) Color.Black else Color(0xFF2997FF),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

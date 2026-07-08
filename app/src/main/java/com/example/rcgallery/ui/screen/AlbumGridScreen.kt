package com.example.rcgallery.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.rcgallery.model.Album
import com.example.rcgallery.model.MediaItem
import com.example.rcgallery.data.db.TagEntity
import com.example.rcgallery.ui.component.DevOverlay
import com.example.rcgallery.ui.component.FastScrollerView
import com.example.rcgallery.ui.component.InertiaSettingsPanel
import com.example.rcgallery.ui.screen.TrashScreen
import com.example.rcgallery.ui.component.FloatingJumpButton
import com.example.rcgallery.ui.component.FpsMonitor
import com.example.rcgallery.ui.component.FpsMonitorEnabled
import com.example.rcgallery.ui.component.TagManageDialog
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.util.FormatUtil
import com.example.rcgallery.viewmodel.GalleryViewModel

/**
 * 相册显示模式。
 */
private sealed class AlbumDisplayMode {
    data class Grid(val columns: Int) : AlbumDisplayMode()
    data object List : AlbumDisplayMode()
}

private const val DEFAULT_GRID_COLUMNS = 3
private const val ITEM_CORNER_RADIUS_DP = 8  // 网格/列表项目中缩略图的圆角半径（dp），可调

/**
 * 给 ImageView 设置圆角裁剪（通过 GradientDrawable + clipToOutline）。
 */
private fun ImageView.setRoundedCorner(radiusDp: Int) {
    val r = resources.displayMetrics.density * radiusDp
    val bg = android.graphics.drawable.GradientDrawable().apply {
        setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
        setCornerRadius(r)
        setColor(android.graphics.Color.TRANSPARENT)
    }
    background = bg
    clipToOutline = true
}


/** 星标缩放因子：根据 Grid 列数调整，列数越多星标越小 */
private fun getAlbumStarScale(columns: Int): Float = when (columns) {
    2 -> 1.15f
    3 -> 1.0f
    4 -> 0.85f
    5 -> 0.7f
    else -> 1.0f
}

private infix fun AlbumDisplayMode.isSameAs(other: AlbumDisplayMode): Boolean = when {
    this is AlbumDisplayMode.Grid && other is AlbumDisplayMode.Grid -> this.columns == other.columns
    this is AlbumDisplayMode.List && other is AlbumDisplayMode.List -> true
    else -> false
}

/** 相册排序模式 */
private enum class AlbumSortMode(val label: String) {
    DATE("创建时间"),
    NAME("相册名"),
    SIZE("相册大小"),
    IMAGE_COUNT("图片数量"),
    VIDEO_COUNT("视频数量")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumGridScreen(
    onSearchClick: () -> Unit = {},
    onAlbumActiveChanged: (Boolean) -> Unit = {}  // true=相册打开, false=退出相册
) {
    val context = LocalContext.current
    val viewModel: GalleryViewModel = viewModel(context as ComponentActivity)
    val rawAlbums by viewModel.albums.collectAsStateWithLifecycle()
    val starredIds by viewModel.starredBucketIds.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val albumTags by viewModel.albumTags.collectAsStateWithLifecycle()
    val persistentRules by viewModel.persistentRules.collectAsStateWithLifecycle()

    // ── 相册显示模式 & 排序模式（持久化，需在 remember(albums) 之前声明）──
    val prefs = context.getSharedPreferences("rcgallery_prefs", android.content.Context.MODE_PRIVATE)
    var displayMode by remember {
        val saved = prefs.getString("album_display_mode", "") ?: ""
        mutableStateOf(when {
            saved == "list" -> AlbumDisplayMode.List
            saved.startsWith("grid_") -> {
                val cols = saved.removePrefix("grid_").toIntOrNull() ?: DEFAULT_GRID_COLUMNS
                AlbumDisplayMode.Grid(cols)
            }
            else -> AlbumDisplayMode.Grid(DEFAULT_GRID_COLUMNS)
        })
    }
    var albumSortMode by remember {
        val saved = prefs.getString("album_sort_mode", "") ?: ""
        mutableStateOf(when (saved) {
            "name" -> AlbumSortMode.NAME
            "size" -> AlbumSortMode.SIZE
            "image_count" -> AlbumSortMode.IMAGE_COUNT
            "video_count" -> AlbumSortMode.VIDEO_COUNT
            else -> AlbumSortMode.DATE
        })
    }

    // 本地排序：星标相册永久置顶，再按当前排序模式排列
    val albums = remember(rawAlbums, starredIds, albumSortMode) {
        rawAlbums.sortedWith(
            compareByDescending<Album> { it.bucketId in starredIds }
                .then(when (albumSortMode) {
                    AlbumSortMode.DATE -> compareByDescending { it.dateAdded }
                    AlbumSortMode.NAME -> compareBy { it.bucketName }
                    AlbumSortMode.SIZE -> compareByDescending { it.totalSize }
                    AlbumSortMode.IMAGE_COUNT -> compareByDescending { it.imageCount }
                    AlbumSortMode.VIDEO_COUNT -> compareByDescending { it.videoCount }
                })
        )
    }

    // ── 日期分组视图状态（持久化）──
    var isDateView by remember {
        mutableStateOf(prefs.getBoolean("date_view", false))
    }
    val allMediaItems by viewModel.allMediaItems.collectAsStateWithLifecycle()
    val tempFilter by viewModel.tempFilter.collectAsStateWithLifecycle()
    // 日期视图：按相册筛选规则过滤（被隐藏的相册中的内容也不在日期模式出现）
    val dateMediaItems = remember(allMediaItems, albums, persistentRules, albumTags, tempFilter) {
        val hasActiveAlbumRule = persistentRules.any {
            it.enabled && (it.scope == com.example.rcgallery.model.FilterScope.ALBUM || it.scope == com.example.rcgallery.model.FilterScope.BOTH)
        }
        val hasTemp = tempFilter.isActive
        if (!hasActiveAlbumRule && !hasTemp) {
            allMediaItems
        } else {
            val hiddenBucketIds = albums.filter { album ->
                val tagNames = albumTags[album.directoryPath]?.map { it.name } ?: emptyList()
                val byPersistent = if (hasActiveAlbumRule) viewModel.shouldHideAlbum(album.directoryPath, tagNames) else false
                val byTemp = if (hasTemp) {
                    val tagSet = tagNames.toSet()
                    val tempTags = tempFilter.tagNames.toSet()
                    val matches = if (tempFilter.logic == com.example.rcgallery.model.FilterLogic.AND) {
                        tempTags.all { it in tagSet }
                    } else {
                        tempTags.any { it in tagSet }
                    }
                    if (tempFilter.mode == com.example.rcgallery.model.FilterMode.HIDE) matches else !matches
                } else false
                byPersistent || byTemp
            }.map { it.bucketId }.toSet()
            allMediaItems.filter { it.albumId !in hiddenBucketIds }
        }
    }
    var selectedDatePhotoIndex by remember { mutableIntStateOf(-1) }
    // 持久化恢复或切换标签回来时，日期视图要加载全量数据
    LaunchedEffect(isDateView) {
        if (isDateView) {
            viewModel.loadAllMedia()
        } else if (selectedDatePhotoIndex >= 0) {
            // 退出日期视图时若有 Preview 开着，恢复底部导航栏
            selectedDatePhotoIndex = -1
            onAlbumActiveChanged(false)
        }
    }

    // ── MediaGrid overlay 状态（代替 navigation push，LazyVerticalGrid 保持存活）──
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    var selectedAlbumName by remember { mutableStateOf("") }
    var selectedAlbumDirectoryPath by remember { mutableStateOf("") }
    // albums（来自 ViewModel）变化时联动更新相册名
    // 优先按 bucketId 匹配；如果 bucketId 变了（如改名后 MediaStore 重新扫描），按相册名回退
    LaunchedEffect(albums, selectedAlbumId) {
        val id = selectedAlbumId
        if (id != null) {
            val found = albums.find { it.bucketId == id }
            if (found != null) {
                selectedAlbumName = found.bucketName
                selectedAlbumDirectoryPath = found.directoryPath
            } else {
                // bucketId 变了：尝试用当前名字匹配（改名后 MediaStore 分配新 bucketId）
                val byName = albums.find { it.bucketName == selectedAlbumName }
                if (byName != null) {
                    selectedAlbumName = byName.bucketName
                    selectedAlbumId = byName.bucketId
                    selectedAlbumDirectoryPath = byName.directoryPath
                }
                // 都找不到则保持现状
            }
        }
    }
    if (selectedAlbumId != null) {
        BackHandler {
            selectedAlbumId = null
            onAlbumActiveChanged(false)
        }
    }

    // ── 设置面板 / 日志（复用组件 SettingsOverlay）──
    // ── 回收站状态 ──
    val trashCount by viewModel.trashCount.collectAsStateWithLifecycle()
    var showTrash by remember { mutableStateOf(false) }
    if (showTrash) {
        BackHandler { showTrash = false; onAlbumActiveChanged(false) }
    }
    // ── 日志面板 ──
    var showLogDialog by remember { mutableStateOf(false) }
    if (showLogDialog) {
        Box(Modifier.fillMaxSize().clickable { showLogDialog = false }) {
            DevOverlay(initialShow = true)
        }
    }
    // ── 设置面板 ──
    var showInertiaSettings by remember { mutableStateOf(false) }
    if (showInertiaSettings) InertiaSettingsPanel(
        onDismiss = { showInertiaSettings = false },
        onOpenLog = { showInertiaSettings = false; showLogDialog = true }
    )

    // ── 相册重命名状态 ──
    var showAlbumRenameDialog by remember { mutableStateOf(false) }
    var renameTargetAlbum by remember { mutableStateOf<Album?>(null) }
    val renameLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        Toast.makeText(context, "授权成功，请重新长按相册进行改名", Toast.LENGTH_SHORT).show()
    }
    if (showAlbumRenameDialog && renameTargetAlbum != null) {
        val target = renameTargetAlbum!!
        val currentName = target.bucketName.ifEmpty { "未知" }
        var editText by remember { mutableStateOf(currentName) }
        AlertDialog(
            onDismissRequest = { showAlbumRenameDialog = false },
            title = { Text("重命名相册") },
            text = {
                Column {
                    val hasManageStorage = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
                    val hint = if (hasManageStorage) "相册名立即更改，文件夹同时重命名。"
                               else "首次改名需授权，将跳转至系统设置开启权限。"
                    Text(hint, color = Color(0xFF999999), fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        singleLine = true,
                        label = { Text("新相册名") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newName = editText.trim()
                    if (newName.isEmpty()) return@Button
                    showAlbumRenameDialog = false
                    renameTargetAlbum = null
                    if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
                        viewModel.renameNow(target.bucketId, newName) { ok ->
                            if (ok) {
                                Toast.makeText(context, "相册已重命名", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "重命名失败，请重试", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        try {
                            renameLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            )
                        } catch (e: Exception) {
                            Toast.makeText(context, "无法跳转授权页面", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showAlbumRenameDialog = false; renameTargetAlbum = null }) { Text("取消") } }
        )
    }

    // ── 权限状态 ──
    var hasPermission by remember {
        mutableStateOf(checkPermission(context))
    }

    // ── RecyclerView 引用（给 FloatingJumpButton 用）──
    val albumRvRef = remember { mutableStateOf<RecyclerView?>(null) }

    // ── 筛选规则过滤后的相册列表（持久规则 + 临时筛选叠加）──
    val filteredAlbums = remember(albums, persistentRules, albumTags, tempFilter) {
        val hasActiveAlbumRule = persistentRules.any {
            it.enabled && (it.scope == com.example.rcgallery.model.FilterScope.ALBUM || it.scope == com.example.rcgallery.model.FilterScope.BOTH)
        }
        val hasTemp = tempFilter.isActive
        AppLogger.d("Filter", "filteredAlbums recalc persistent=$hasActiveAlbumRule temp=$hasTemp")
        if (!hasActiveAlbumRule && !hasTemp) albums
        else {
            albums.filter { album ->
                val tagNames = albumTags[album.directoryPath]?.map { it.name } ?: emptyList()
                // 先过持久规则
                val byPersistent = if (hasActiveAlbumRule) viewModel.shouldHideAlbum(album.directoryPath, tagNames) else false
                val byTemp = if (hasTemp) {
                    val tagSet = tagNames.toSet()
                    val tempTags = tempFilter.tagNames.toSet()
                    val matches = if (tempFilter.logic == com.example.rcgallery.model.FilterLogic.AND) {
                        tempTags.all { it in tagSet }
                    } else {
                        tempTags.any { it in tagSet }
                    }
                    if (tempFilter.mode == com.example.rcgallery.model.FilterMode.HIDE) matches else !matches
                } else false
                val hide = byPersistent || byTemp
                AppLogger.d("Filter", "  album=${album.bucketName} tags=$tagNames hide=$hide (persistent=$byPersistent temp=$byTemp)")
                !hide
            }.also { result ->
                AppLogger.d("Filter", "  result: ${result.size}/${albums.size} albums kept")
            }
        }
    }

    var showFilterPage by remember { mutableStateOf(false) }

    // ── 权限请求 launcher ──
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        hasPermission = granted.values.all { it }
        if (hasPermission) {
            viewModel.loadAlbums()
        }
    }

    // ── 首次加载 ──
    LaunchedEffect(hasPermission) {
        AppLogger.d("AlbumGrid", "LaunchedEffect hasPermission=$hasPermission albums.size=${albums.size}")
        if (hasPermission && albums.isEmpty()) {
            viewModel.loadAlbums()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            var showFilterPage by remember { mutableStateOf(false) }

            if (!hasPermission) {
                NoPermissionContent(onRequestPermission = {
                    val permissions = getRequiredPermissions()
                    permissionLauncher.launch(permissions)
                })
            } else if (isLoading && albums.isEmpty()) {
                LoadingContent()
            } else if (albums.isEmpty()) {
                LoadingContent()
            } else {
                // ── TAG 管理对话框状态 ──
                var tagDialogAlbum by remember { mutableStateOf<Album?>(null) }
                val allTags by viewModel.allTags.collectAsStateWithLifecycle()

                AlbumGridContent(
                    albums = filteredAlbums,
                    starredIds = starredIds,
                    albumTags = albumTags,
                    onAlbumClick = { album ->
                        AppLogger.d("AlbumGrid", "click album=${album.bucketName} id=${album.bucketId} count=${album.count}")
                        selectedAlbumId = album.bucketId
                        selectedAlbumName = album.bucketName
                        selectedAlbumDirectoryPath = album.directoryPath
                        // 同步通知上层隐藏底部栏（不用 LaunchedEffect，防延迟一帧）
                        onAlbumActiveChanged(true)
                    },
                    onAlbumLongClick = { album ->
                        renameTargetAlbum = album
                        showAlbumRenameDialog = true
                    },
                    onToggleStar = { bucketId -> viewModel.toggleStar(bucketId) },
                    onRefresh = { viewModel.loadAlbums() },
                    displayMode = displayMode,
                    onSelectMode = { mode ->
                        displayMode = mode
                        prefs.edit().putString("album_display_mode", when (mode) {
                            is AlbumDisplayMode.List -> "list"
                            is AlbumDisplayMode.Grid -> "grid_${mode.columns}"
                        }).apply()
                    },
                    albumRvRef = albumRvRef,
                    albumSortMode = albumSortMode,
                    onSelectSort = { mode ->
                        isDateView = false
                        albumSortMode = mode
                        prefs.edit().putString("album_sort_mode", when (mode) {
                            AlbumSortMode.NAME -> "name"
                            AlbumSortMode.DATE -> "date"
                            AlbumSortMode.SIZE -> "size"
                            AlbumSortMode.IMAGE_COUNT -> "image_count"
                            AlbumSortMode.VIDEO_COUNT -> "video_count"
                        }).apply()
                    },
                    isDateView = isDateView,
                    onToggleDateView = {
                        isDateView = !isDateView
                        prefs.edit().putBoolean("date_view", isDateView).apply()
                        if (isDateView) viewModel.loadAllMedia()
                    },
                    allDateMediaItems = dateMediaItems,
                    selectedDatePhotoIndex = selectedDatePhotoIndex,
                    onDatePhotoClick = { idx -> selectedDatePhotoIndex = idx; onAlbumActiveChanged(true) },
                    onDatePhotoBack = { selectedDatePhotoIndex = -1 },
                    onOpenSettings = { showInertiaSettings = true },
                    onOpenLog = { showLogDialog = true },
                    onOpenTrash = {
                        showTrash = true
                        onAlbumActiveChanged(true)
                        viewModel.loadTrashEntries()
                    },
                    onManageAlbumTags = { album -> tagDialogAlbum = album },
                    allTags = allTags,
                    onBatchAddTagsToAlbums = { dirPath, tagName -> viewModel.addAlbumTag(dirPath, tagName) },
                    onBatchAddTagsToMedia = { filePath, tagName -> viewModel.addMediaTag(filePath, tagName) },
                    onDeleteDateMedia = { paths ->
                        val toDelete = dateMediaItems.filter { it.filePath in paths }
                        toDelete.forEach { viewModel.moveToTrash(it) }
                    },
                    hasActiveFilter = persistentRules.any { it.enabled } || tempFilter.isActive,
                    onOpenFilter = { showFilterPage = true }
                )
                // ── TAG 管理对话框 ──
                val currentTagAlbum = tagDialogAlbum
                if (currentTagAlbum != null) {
                    val scope = rememberCoroutineScope()
                    var recentTagList by remember { mutableStateOf<List<TagEntity>>(emptyList()) }
                    LaunchedEffect(Unit) {
                        recentTagList = viewModel.getRecentTags()
                    }
                    val existingTags: List<TagEntity> = currentTagAlbum.directoryPath.let { albumTags[it] } ?: emptyList()
                    val allTagsList: List<TagEntity> = allTags
                    TagManageDialog(
                        title = "管理相册标签 - ${currentTagAlbum.bucketName}",
                        existingTags = existingTags,
                        allTags = allTagsList,
                        recentTags = recentTagList,
                        onAddTag = { name -> viewModel.addAlbumTag(currentTagAlbum.directoryPath, name) },
                        onRemoveTag = { tagId -> viewModel.removeAlbumTag(currentTagAlbum.directoryPath, tagId) },
                        onDismiss = { tagDialogAlbum = null }
                    )
                }
            }
            FpsMonitor(enabled = FpsMonitorEnabled, modifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 8.dp))
            FloatingJumpButton(recyclerView = albumRvRef.value, modifier = Modifier.align(Alignment.BottomStart))

            // ── MediaGrid 全屏覆盖层（不通过 navigation，LazyVerticalGrid 保持存活）──
            if (selectedAlbumId != null) {
                MediaGridScreen(
                    albumId = selectedAlbumId!!,
                    albumName = selectedAlbumName,
                    albumDirectoryPath = selectedAlbumDirectoryPath,
                    onBackClick = {
                        selectedAlbumId = null
                        onAlbumActiveChanged(false)  // 同步通知上层恢复底部栏
                    },
                    onGoHome = {
                        selectedAlbumId = null
                        onAlbumActiveChanged(false)
                    }
                )
            }
            // ── 回收站全屏覆盖层 ──
            if (showTrash) {
                TrashScreen(
                    onBackClick = { showTrash = false; onAlbumActiveChanged(false) }
                )
            }
            // ── 日期视图 Preview 覆盖层 ──
            if (isDateView && selectedDatePhotoIndex >= 0 && selectedDatePhotoIndex < dateMediaItems.size) {
                BackHandler { selectedDatePhotoIndex = -1; onAlbumActiveChanged(false) }
                PreviewScreen(
                    initialIndex = selectedDatePhotoIndex,
                    onBackClick = { selectedDatePhotoIndex = -1; onAlbumActiveChanged(false) },
                    items = dateMediaItems
                )
            }
            // ── 筛选规则页面（全屏覆盖层）──
            if (showFilterPage) {
                val allTags by viewModel.allTags.collectAsStateWithLifecycle()
                FilterPage(
                    allTags = allTags,
                    persistentRules = persistentRules,
                    tempFilter = tempFilter,
                    onBack = { showFilterPage = false },
                    onToggleRule = { viewModel.toggleRule(it) },
                    onSaveRule = { rule ->
                        if (persistentRules.any { it.id == rule.id }) {
                            viewModel.updateRule(rule)
                        } else {
                            viewModel.addRule(rule)
                        }
                    },
                    onDeleteRule = { viewModel.deleteRule(it) },
                    onSetTempFilter = { viewModel.setTempFilter(it) },
                    onReset = {
                        viewModel.resetTempFilter()
                        persistentRules.forEach { if (it.enabled) viewModel.toggleRule(it.id) }
                    },
                    ignoredFolderPaths = viewModel.ignoredFolderPaths.collectAsState().value,
                    allAlbums = rawAlbums,
                    onToggleIgnoredFolder = { viewModel.toggleIgnoredFolder(it) }
                )
            }
        }
    }
}

// ══════════════════════════════════════
//  子组件
// ══════════════════════════════════════

@Composable
private fun NoPermissionContent(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "需要访问您的媒体文件",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "RCGallery 需要读取照片和视频权限才能展示相册内容。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRequestPermission) {
                Text("授予权限")
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyContent(onRefresh: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "没有找到相册",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "请确认手机中有照片或视频文件。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = onRefresh) {
                Text("刷新")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumGridContent(
    albums: List<Album>,
    starredIds: Set<String>,
    onAlbumClick: (Album) -> Unit,
    onAlbumLongClick: (Album) -> Unit,
    onToggleStar: (String) -> Unit,
    onRefresh: () -> Unit,
    displayMode: AlbumDisplayMode,
    onSelectMode: (AlbumDisplayMode) -> Unit,
    albumSortMode: AlbumSortMode,
    onSelectSort: (AlbumSortMode) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenLog: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onManageAlbumTags: (Album) -> Unit = {},
    albumRvRef: MutableState<RecyclerView?>,
    albumTags: Map<String, List<TagEntity>> = emptyMap(),
    allTags: List<TagEntity> = emptyList(),
    onBatchAddTagsToAlbums: (String, String) -> Unit = { _, _ -> },
    onBatchAddTagsToMedia: (String, String) -> Unit = { _, _ -> },
    onDeleteDateMedia: (List<String>) -> Unit = {},
    // ── 日期分组视图参数 ──
    isDateView: Boolean = false,
    onToggleDateView: () -> Unit = {},
    allDateMediaItems: List<MediaItem> = emptyList(),
    selectedDatePhotoIndex: Int = -1,
    onDatePhotoClick: (Int) -> Unit = {},
    onDatePhotoBack: () -> Unit = {},
    // ── 筛选参数 ──
    hasActiveFilter: Boolean = false,
    onOpenFilter: () -> Unit = {}
) {
    // ── Grid 模式多选状态 ──
    var isAlbumMultiSelect by remember { mutableStateOf(false) }
    var selectedAlbumIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun exitAlbumMultiSelect() {
        isAlbumMultiSelect = false
        selectedAlbumIds = emptySet()
    }

    fun toggleAlbumSelection(album: Album) {
        selectedAlbumIds = if (album.bucketId in selectedAlbumIds) selectedAlbumIds - album.bucketId
                           else selectedAlbumIds + album.bucketId
        if (selectedAlbumIds.isEmpty()) isAlbumMultiSelect = false
    }

    // ── 日期视图多选状态 ──
    var isDateMultiSelect by remember { mutableStateOf(false) }
    var selectedDateMediaPaths by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun exitDateMultiSelect() {
        isDateMultiSelect = false
        selectedDateMediaPaths = emptySet()
    }

    fun toggleDateMediaSelection(filePath: String) {
        selectedDateMediaPaths = if (filePath in selectedDateMediaPaths)
            selectedDateMediaPaths - filePath
        else
            selectedDateMediaPaths + filePath
        if (selectedDateMediaPaths.isEmpty()) isDateMultiSelect = false
    }

    val onGridLongClick: (Album) -> Unit = remember {{
        album ->
        if (!isAlbumMultiSelect) isAlbumMultiSelect = true
        toggleAlbumSelection(album)
    }}

    // ── 批量 TAG 对话框 ──
    var showBatchTagDialog by remember { mutableStateOf(false) }
    var showDateBatchTagDialog by remember { mutableStateOf(false) }

    // 多选模式：短按切换选中而非打开相册
    fun wrappedAlbumClick(album: Album) {
        if (isAlbumMultiSelect) {
            toggleAlbumSelection(album)
        } else {
            onAlbumClick(album)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isAlbumMultiSelect) "已选 ${selectedAlbumIds.size} 项"
                        else if (isDateMultiSelect) "已选 ${selectedDateMediaPaths.size} 项"
                        else "RCGallery"
                    )
                },
                navigationIcon = {
                    if (isAlbumMultiSelect) {
                        TextButton(onClick = { exitAlbumMultiSelect() }) {
                            Text("取消", color = MaterialTheme.colorScheme.onSurface)
                        }
                    } else if (isDateMultiSelect) {
                        TextButton(onClick = { exitDateMultiSelect() }) {
                            Text("取消", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),  // 外层 Scaffold 已处理状态栏 insets
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    if (!isAlbumMultiSelect && !isDateMultiSelect) {
                    // ── 齿轮菜单按钮 ──
                    var showGearMenu by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.padding(end = 4.dp)) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(0xCCFF9800))
                                .clickable { showGearMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(com.example.rcgallery.R.drawable.ic_settings),
                                contentDescription = "设置",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showGearMenu,
                            onDismissRequest = { showGearMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = { showGearMenu = false; onOpenSettings() }
                            )
                            DropdownMenuItem(
                                text = { Text("日志") },
                                onClick = { showGearMenu = false; onOpenLog() }
                            )
                        }
                    }

                    // ── 回收站按钮 ──
                    Icon(
                        painter = painterResource(com.example.rcgallery.R.drawable.ic_trash),
                        contentDescription = "回收站",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable { onOpenTrash() }
                    )
                    }   // ← if (!isAlbumMultiSelect)
                }
            )
        },
        bottomBar = {
            if (isAlbumMultiSelect && selectedAlbumIds.isNotEmpty()) {
                BottomAppBar(
                    containerColor = Color(0xFF1A1A1A),
                    tonalElevation = 8.dp
                ) {
                    Text(
                        "已选 ${selectedAlbumIds.size} 项",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { showBatchTagDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("批量加标签 (${selectedAlbumIds.size})", fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }
            } else if (isDateMultiSelect && selectedDateMediaPaths.isNotEmpty()) {
                BottomAppBar(
                    containerColor = Color(0xFF1A1A1A),
                    tonalElevation = 8.dp
                ) {
                    Text(
                        "已选 ${selectedDateMediaPaths.size} 项",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { showDateBatchTagDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("批量加标签 (${selectedDateMediaPaths.size})", fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onDeleteDateMedia(selectedDateMediaPaths.toList())
                            exitDateMultiSelect()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("删除到回收站 (${selectedDateMediaPaths.size})", fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // ── 多选 BackHandler ──
            if (isAlbumMultiSelect) {
                BackHandler {
                    exitAlbumMultiSelect()
                }
            }
            if (isDateMultiSelect) {
                BackHandler {
                    exitDateMultiSelect()
                }
            }
            // ── RecyclerView 内容区域（填满全屏）──
            if (!isDateView) {
            AndroidView(
                factory = { ctx ->
                    val adapter = AlbumGridAdapter(
                        items = albums,
                        onClick = { album -> wrappedAlbumClick(album) },
                        onLongClick = onAlbumLongClick,
                        onToggleStar = onToggleStar,
                        onManageTags = onManageAlbumTags,
                        onGridLongClick = onGridLongClick
                    )
                    val rv = RecyclerView(ctx).apply {
                        layoutManager = when (displayMode) {
                            is AlbumDisplayMode.Grid -> GridLayoutManager(ctx, displayMode.columns)
                            is AlbumDisplayMode.List -> GridLayoutManager(ctx, 1)
                        }
                        this.adapter = adapter
                        addItemDecoration(AlbumGridSpacing(ctx))
                        clipToPadding = false
                        setPadding(0, (44 * ctx.resources.displayMetrics.density).toInt(), 0, 0)
                    }
                    albumRvRef.value = rv
                    val scroller = FastScrollerView(ctx, rv)
                    FrameLayout(ctx).apply {
                        addView(rv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        addView(scroller, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                },
                update = { container ->
                    val rv = (container as FrameLayout).getChildAt(0) as RecyclerView
                    val scroller = container.getChildAt(1) as FastScrollerView
                    val adapter = rv.adapter as AlbumGridAdapter
                    val prevMode = adapter.currentMode
                    adapter.items = albums
                    adapter.starredIds = starredIds
                    adapter.albumTagsMap = albumTags
                    adapter.selectedIds = selectedAlbumIds
                    adapter.notifyDataSetChanged()
                    // 之后不再重复调用 notifyDataSetChanged — 上面的调用已是全量刷新
                    if (prevMode != displayMode) {
                        adapter.currentMode = displayMode
                        val spanCount = when (displayMode) {
                            is AlbumDisplayMode.Grid -> displayMode.columns
                            is AlbumDisplayMode.List -> 1
                        }
                        val lm = rv.layoutManager as? GridLayoutManager
                        if (lm == null || lm.spanCount != spanCount) {
                            rv.layoutManager = GridLayoutManager(rv.context, spanCount)
                        }
                        adapter.notifyDataSetChanged()
                        scroller.refresh()
                    } else {
                        adapter.notifyDataSetChanged()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            }  // end if (!isDateView)
            // ── 日期分组视图（始终渲染，避免首次加载时 RecyclerView 布局异常）
            if (isDateView) {
                val columns = when (val mode = displayMode) {
                    is AlbumDisplayMode.Grid -> mode.columns
                    is AlbumDisplayMode.List -> 1
                }
                val dateViewItems = remember(allDateMediaItems, displayMode) {
                    buildDateViewItems(allDateMediaItems)
                }
                DateGroupRecyclerView(
                    items = dateViewItems,
                    columns = columns,
                    onClick = { mediaItem, allMediaIdx ->
                        if (isDateMultiSelect) {
                            toggleDateMediaSelection(mediaItem.filePath)
                        } else {
                            onDatePhotoClick(allMediaIdx)
                        }
                    },
                    onLongClick = { mediaItem ->
                        if (!isDateMultiSelect) isDateMultiSelect = true
                        toggleDateMediaSelection(mediaItem.filePath)
                    },
                    selectedPaths = selectedDateMediaPaths,
                    onDragSelectRange = { start, end ->
                        val minIdx = minOf(start, end)
                        val maxIdx = maxOf(start, end)
                        val paths = (minIdx..maxIdx).mapNotNull { i ->
                            (dateViewItems.getOrNull(i) as? DateViewItem.Media)?.item?.filePath
                        }.toSet()
                        if (paths.isNotEmpty()) isDateMultiSelect = true
                        selectedDateMediaPaths = paths
                    }
                )
            }
            // ── 显示模式 + 排序 悬浮工具栏（在 RecyclerView 上方）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp)
                    .align(Alignment.TopStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DisplayModeSelector(
                    currentMode = displayMode,
                    onSelectMode = onSelectMode,
                    hasActiveFilter = hasActiveFilter,
                    onOpenFilter = onOpenFilter
                )
                Spacer(Modifier.weight(1f))
                DateButton(
                    isActive = isDateView,
                    onClick = onToggleDateView
                )
                AlbumSortSelector(
                    currentSort = albumSortMode,
                    onSelectSort = { mode ->
                        if (isDateView) onToggleDateView()
                        onSelectSort(mode)
                    }
                )
            }  // end Row
        }  // end Box

        // ── 批量 TAG 对话框 ──
        if (showBatchTagDialog) {
            val batchAlbumIds = albums.filter { it.bucketId in selectedAlbumIds }
            BatchTagDialog(
                title = "批量加标签 - 已选 ${batchAlbumIds.size} 个相册",
                allTags = allTags,
                onAddTag = { tagName ->
                    batchAlbumIds.forEach { album ->
                        onBatchAddTagsToAlbums(album.directoryPath, tagName)
                    }
                    showBatchTagDialog = false
                    exitAlbumMultiSelect()
                },
                onDismiss = { showBatchTagDialog = false }
            )
        }
        // ── 日期视图批量 TAG 对话框 ──
        if (showDateBatchTagDialog) {
            BatchTagDialog(
                title = "批量加标签 - 已选 ${selectedDateMediaPaths.size} 个媒体文件",
                allTags = allTags,
                onAddTag = { tagName ->
                    selectedDateMediaPaths.forEach { filePath ->
                        onBatchAddTagsToMedia(filePath, tagName)
                    }
                    showDateBatchTagDialog = false
                    exitDateMultiSelect()
                },
                onDismiss = { showDateBatchTagDialog = false }
            )
        }
        }
    }

    // ── 批量 TAG 对话框（复用 TagManageDialog，隐藏现有标签和移除功能）──
@Composable
private fun BatchTagDialog(
    title: String,
    allTags: List<TagEntity>,
    onAddTag: (String) -> Unit,
    onDismiss: () -> Unit
) {
    TagManageDialog(
        title = title,
        existingTags = emptyList(),
        allTags = allTags,
        onAddTag = onAddTag,
        onRemoveTag = { _ -> },
        onDismiss = onDismiss
    )
}

// ══════════════════════════════════════
//  RecyclerView Adapter — Grid / List 模式
// ══════════════════════════════════════


private class AlbumGridAdapter(
    var items: List<Album>,
    private val onClick: (Album) -> Unit,
    private val onLongClick: (Album) -> Unit,
    private val onToggleStar: (String) -> Unit,
    private val onManageTags: (Album) -> Unit = {},
    private val onGridLongClick: ((Album) -> Unit)? = null  // Grid 模式长按（多选），不传则回退到 onLongClick
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var currentMode: AlbumDisplayMode = AlbumDisplayMode.Grid(3)
    var starredIds: Set<String> = emptySet()
    var albumTagsMap: Map<String, List<TagEntity>> = emptyMap()
    var selectedIds: Set<String> = emptySet()  // 多选模式选中项 bucketId 集合

    init { setHasStableIds(true) }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.bucketId?.hashCode()?.toLong() ?: 0L

    override fun getItemViewType(position: Int): Int {
        return if (currentMode is AlbumDisplayMode.List) VIEW_TYPE_LIST else VIEW_TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_LIST) {
            ListVH.create(parent, onClick, onLongClick, onToggleStar, onManageTags, albumTagsMap)
        } else {
            GridVH.create(parent, onClick, onGridLongClick ?: onLongClick, onToggleStar)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val columns = when (val mode = currentMode) {
            is AlbumDisplayMode.Grid -> mode.columns
            is AlbumDisplayMode.List -> 1
        }
        when (holder) {
            is GridVH -> holder.bind(item, position, starredIds, selectedIds, columns)
            is ListVH -> holder.bind(item, position, starredIds, albumTagsMap)
        }
    }

}

private const val VIEW_TYPE_GRID = 0
private const val VIEW_TYPE_LIST = 1

/**
 * RecyclerView item spacing — Grid 模式均匀分布，List 模式仅垂直间距。
 */
private class AlbumGridSpacing(context: android.content.Context) : RecyclerView.ItemDecoration() {
    private val gapPx = (4 * context.resources.displayMetrics.density).toInt()

    override fun getItemOffsets(outRect: android.graphics.Rect, view: android.view.View, parent: RecyclerView, state: RecyclerView.State) {
        val lm = parent.layoutManager as? GridLayoutManager ?: return
        val spanCount = lm.spanCount
        if (spanCount <= 1) {
            // List 模式：垂直间隙
            outRect.set(0, 0, 0, gapPx / 2)
            return
        }
        val lp = view.layoutParams as? GridLayoutManager.LayoutParams ?: return
        val spanIndex = lp.spanIndex
        outRect.left = gapPx * spanIndex / spanCount
        outRect.right = gapPx * (spanCount - 1 - spanIndex) / spanCount
        outRect.top = gapPx / 2
        outRect.bottom = gapPx / 2
    }
}

// ── Grid ViewHolder ──
// ┌──────────────────┐
// │   封面缩略图      │  ImageView (正方形)
// ├──────────────────┤
// │ 相册名称          │  TextView
// │ N 项             │  TextView
// └──────────────────┘

private class GridVH private constructor(
    itemView: android.view.View,
    private val starContainer: FrameLayout,
    private val starWrapper: FrameLayout,
    private val starIv: ImageView
) : RecyclerView.ViewHolder(itemView) {

    companion object {
        fun create(parent: ViewGroup, onClick: (Album) -> Unit, onLongClick: (Album) -> Unit, onToggleStar: (String) -> Unit): GridVH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val margin = (6 * density).toInt()

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            // 正方形封面 FrameLayout（代替原来的 ImageView，用于叠加星标）
            val coverFrame = object : FrameLayout(ctx) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    super.onMeasure(widthSpec, widthSpec)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0
                )
            }
            val iv = ImageView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                id = android.R.id.icon
                setRoundedCorner(ITEM_CORNER_RADIUS_DP)
            }
            coverFrame.addView(iv)

            // ── 星标：左上（半透明黑底圆形 + 星标正居中）──
            val starContainer = FrameLayout(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (48 * density).toInt(),
                    (48 * density).toInt(),
                    android.view.Gravity.TOP or android.view.Gravity.START
                ).apply {
                    setMargins((4 * density).toInt(), (4 * density).toInt(), 0, 0)
                }
                isClickable = true
                focusable = View.FOCUSABLE
            }
            // 小 FrameLayout 包裹圆形背景 + 星标图标（25dp，定位在容器左上角）
            val starWrapper = FrameLayout(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (25 * density).toInt(),
                    (25 * density).toInt(),
                    android.view.Gravity.TOP or android.view.Gravity.START
                ).apply {
                    setMargins((3 * density).toInt(), (3 * density).toInt(), 0, 0)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    setShape(android.graphics.drawable.GradientDrawable.OVAL)
                    setColor(android.graphics.Color.argb(120, 0, 0, 0))
                }
            }
            val starIv = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (18 * density).toInt(),
                    (18 * density).toInt(),
                    android.view.Gravity.CENTER
                )
                scaleType = ImageView.ScaleType.FIT_XY
                setImageResource(com.example.rcgallery.R.drawable.ic_star)
            }
            starWrapper.addView(starIv)
            starContainer.addView(starWrapper)
            coverFrame.addView(starContainer)

            // ── 多选对号：居中半透明绿色圆形 + 白色 ✓ ──
            val checkmarkContainer = FrameLayout(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (36 * density).toInt(),
                    (36 * density).toInt(),
                    android.view.Gravity.CENTER
                )
                visibility = android.view.View.GONE
                id = android.R.id.checkbox
                background = android.graphics.drawable.GradientDrawable().apply {
                    setShape(android.graphics.drawable.GradientDrawable.OVAL)
                    setColor(android.graphics.Color.argb(200, 76, 175, 80))
                }
            }
            val checkmarkTv = TextView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER
                )
                text = "✓"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 20f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            checkmarkContainer.addView(checkmarkTv)
            coverFrame.addView(checkmarkContainer)

            root.addView(coverFrame)

            // ── 星标点击：使用 starContainer 自身的 tag（bucketId），
            //    不依赖 root.tag，防止 RecyclerView 重绑时 position 被覆盖 ──
            starContainer.setOnClickListener {
                val bucketId = starContainer.tag as? String ?: return@setOnClickListener
                onToggleStar(bucketId)
            }

            val nameTv = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(margin, margin, margin, 0) }
                setTextSize(12f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            root.addView(nameTv)

            val countTv = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(margin, (2 * density).toInt(), margin, margin) }
                setTextSize(11f)
                setTextColor(android.graphics.Color.GRAY)
            }
            root.addView(countTv)

            root.setOnClickListener {
                val pos = root.tag as? Int ?: return@setOnClickListener
                if (pos >= 0) {
                    val rv = root.parent as? RecyclerView ?: return@setOnClickListener
                    (rv.adapter as? AlbumGridAdapter)?.items?.getOrNull(pos)?.let { onClick(it) }
                }
            }
            root.setOnLongClickListener {
                val pos = root.tag as? Int ?: return@setOnLongClickListener true
                if (pos >= 0) {
                    val rv = root.parent as? RecyclerView ?: return@setOnLongClickListener true
                    (rv.adapter as? AlbumGridAdapter)?.items?.getOrNull(pos)?.let { onLongClick(it) }
                }
                true
            }
            return GridVH(root, starContainer, starWrapper, starIv)
        }
    }

    fun bind(item: Album, pos: Int, starredIds: Set<String>, selectedIds: Set<String> = emptySet(), columns: Int = 3) {
        itemView.tag = pos
        starContainer.tag = item.bucketId
        val iv = itemView.findViewById<ImageView>(android.R.id.icon)
        iv.load(item.coverUri) { size(180); crossfade(false) }
        val root = itemView as LinearLayout
        (root.getChildAt(1) as TextView).text = item.bucketName
        (root.getChildAt(2) as TextView).text = "${item.count} 项"
        val isStarred = item.bucketId in starredIds
        starIv.colorFilter = android.graphics.PorterDuffColorFilter(
            if (isStarred) android.graphics.Color.rgb(255, 193, 7) else android.graphics.Color.rgb(160, 160, 160),
            android.graphics.PorterDuff.Mode.SRC_IN
        )
        // 多选模式：隐藏星标，显示对号
        val inMultiSelect = selectedIds.isNotEmpty()
        val isSelected = item.bucketId in selectedIds
        starContainer.visibility = if (inMultiSelect) android.view.View.GONE else android.view.View.VISIBLE
        val checkmark = itemView.findViewById<FrameLayout>(android.R.id.checkbox)
        if (checkmark != null) {
            checkmark.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.GONE
        }
        updateStarSize(columns)
    }

    /** 动态调整星标三个层级的尺寸，与 Grid 列数适配 */
    private fun updateStarSize(columns: Int) {
        val density = itemView.resources.displayMetrics.density
        val scale = getAlbumStarScale(columns)
        starContainer.layoutParams = (starContainer.layoutParams as FrameLayout.LayoutParams).apply {
            width = (48 * density * scale).toInt()
            height = (48 * density * scale).toInt()
        }
        starWrapper.layoutParams = (starWrapper.layoutParams as FrameLayout.LayoutParams).apply {
            width = (25 * density * scale).toInt()
            height = (25 * density * scale).toInt()
            setMargins((3 * density * scale).toInt(), (3 * density * scale).toInt(), 0, 0)
        }
        starIv.layoutParams = (starIv.layoutParams as FrameLayout.LayoutParams).apply {
            width = (18 * density * scale).toInt()
            height = (18 * density * scale).toInt()
        }
    }
}

// ── List ViewHolder ──
// ┌─────────────────────────────────────────────────┐
// │ ┌──────┐  相册名称                              │
// │ │ 封面  │  123.4 MB · N 图片 · M 视频 · K GIF   │
// │ │ 缩略图│  /path/to/album                       │
// │ └──────┘                                        │
// ├─────────────────────────────────────────────────┤
// │                   分隔线                         │
// └─────────────────────────────────────────────────┘

private class ListVH private constructor(
    itemView: android.view.View,
    private val starContainer: FrameLayout,
    private val starIv: ImageView,
    private val onManageTags: (Album) -> Unit = {}
) : RecyclerView.ViewHolder(itemView) {

    private val tagRow: LinearLayout = itemView.findViewWithTag("album_tag_row")
    private val tagChips: Array<TextView> = Array(8) { idx ->
        val ctx = itemView.context
        val density = ctx.resources.displayMetrics.density
        TextView(ctx).apply {
            textSize = 10f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundDrawable(
                android.graphics.drawable.GradientDrawable().apply {
                    setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
                    setCornerRadius(6 * density)
                    setColor(android.graphics.Color.argb(180, 100, 140, 255))
                }
            )
            setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
            maxLines = 1
            visibility = android.view.View.GONE
            tagRow.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, (4 * density).toInt(), 0) })
        }
    }

    companion object {
        fun create(parent: ViewGroup, onClick: (Album) -> Unit, onLongClick: (Album) -> Unit, onToggleStar: (String) -> Unit,
                   onManageTags: (Album) -> Unit = {}, albumTagsMap: Map<String, List<TagEntity>> = emptyMap()): ListVH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            // ── 内容行（封面 + 文字 + 星标）──
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt()) }
            }
            val thumbSize = (64 * density).toInt()
            val iv = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize)
                scaleType = ImageView.ScaleType.CENTER_CROP
                id = android.R.id.icon
                setRoundedCorner(ITEM_CORNER_RADIUS_DP)
            }
            row.addView(iv)

            val textColumn = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    weight = 1f
                    setMargins((12 * density).toInt(), 0, 0, 0)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
            }

            val nameTv = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextSize(14f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            textColumn.addView(nameTv)

            // ── TAG 行（纯 LinearLayout，不拦截点击，点 TAG 区域进入相册）──
            val tagRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, (3 * density).toInt(), 0, 0) }
                tag = "album_tag_row"
                visibility = android.view.View.GONE
            }
            // + 按钮（放在 TAG 行内，唯一可点击的交互元素）
            val tagAddChip = TextView(ctx).apply {
                text = "+"
                textSize = 12f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundDrawable(
                    android.graphics.drawable.GradientDrawable().apply {
                        setShape(android.graphics.drawable.GradientDrawable.OVAL)
                        setColor(android.graphics.Color.argb(180, 100, 180, 100))
                    }
                )
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    (20 * density).toInt(),
                    (20 * density).toInt()
                ).apply { setMargins(0, 0, (4 * density).toInt(), 0) }
                isClickable = true
                focusable = android.view.View.FOCUSABLE
            }
            tagRow.addView(tagAddChip)
            textColumn.addView(tagRow)

            val infoTv = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextSize(12f)
                setTextColor(android.graphics.Color.GRAY)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                id = android.R.id.text1
            }
            textColumn.addView(infoTv)

            val pathTv = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextSize(11f)
                setTextColor(android.graphics.Color.DKGRAY)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                id = android.R.id.text2
            }
            textColumn.addView(pathTv)

            row.addView(textColumn)

            // ── 星标：方形大触控区域（48dp，图标 24dp 居中）──
            val starContainer = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (48 * density).toInt(),
                    (48 * density).toInt()
                ).apply {
                    setMargins((8 * density).toInt(), 0, (3 * density).toInt(), 0)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                isClickable = true
                focusable = View.FOCUSABLE
            }
            val starIv = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (24 * density).toInt(),
                    (24 * density).toInt(),
                    android.view.Gravity.CENTER
                )
                scaleType = ImageView.ScaleType.FIT_XY
                setImageResource(com.example.rcgallery.R.drawable.ic_star)
            }
            starContainer.addView(starIv)
            row.addView(starContainer)

            root.addView(row)

            // ── 分隔线 ──
            val divider = android.view.View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (1 * density).toInt()
                ).apply { setMargins((12 * density).toInt(), 0, (12 * density).toInt(), 0) }
                setBackgroundColor(android.graphics.Color.argb(25, 255, 255, 255))
            }
            root.addView(divider)

            // ── 星标点击：使用 starContainer 自身的 tag（bucketId），不依赖 root.tag ──
            starContainer.setOnClickListener {
                val bucketId = starContainer.tag as? String ?: return@setOnClickListener
                onToggleStar(bucketId)
            }

            root.setOnClickListener {
                val pos = root.tag as? Int ?: return@setOnClickListener
                if (pos >= 0) {
                    val rv = root.parent as? RecyclerView ?: return@setOnClickListener
                    (rv.adapter as? AlbumGridAdapter)?.items?.getOrNull(pos)?.let { onClick(it) }
                }
            }
            root.setOnLongClickListener {
                val pos = root.tag as? Int ?: return@setOnLongClickListener true
                if (pos >= 0) {
                    val rv = root.parent as? RecyclerView ?: return@setOnLongClickListener true
                    (rv.adapter as? AlbumGridAdapter)?.items?.getOrNull(pos)?.let { onLongClick(it) }
                }
                true
            }
            return ListVH(root, starContainer, starIv, onManageTags)
        }
    }

    fun bind(item: Album, pos: Int, starredIds: Set<String>, albumTagsMap: Map<String, List<TagEntity>> = emptyMap()) {
        itemView.tag = pos
        starContainer.tag = item.bucketId
        val row = (itemView as LinearLayout).getChildAt(0) as LinearLayout
        val iv = row.getChildAt(0) as ImageView
        val textColumn = row.getChildAt(1) as LinearLayout
        val nameTv = textColumn.getChildAt(0) as TextView
        // index 1 = tagRow (LinearLayout, 跳过)
        val infoTv = textColumn.getChildAt(2) as TextView
        val pathTv = textColumn.getChildAt(3) as TextView

        iv.load(item.coverUri) { size(160); crossfade(false) }
        nameTv.text = item.bucketName
        infoTv.text = buildString {
            append(FormatUtil.formatFileSize(item.totalSize))
            append(" · ${item.imageCount} 图片")
            if (item.videoCount > 0) append(" · ${item.videoCount} 视频")
            if (item.gifCount > 0) append(" · ${item.gifCount} GIF")
        }
        pathTv.text = item.directoryPath
        pathTv.visibility = if (item.directoryPath.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        val isStarred = item.bucketId in starredIds
        starIv.colorFilter = android.graphics.PorterDuffColorFilter(
            if (isStarred) android.graphics.Color.rgb(255, 193, 7) else android.graphics.Color.rgb(160, 160, 160),
            android.graphics.PorterDuff.Mode.SRC_IN
        )
        // ── TAG 行 ──
        val tags = albumTagsMap[item.directoryPath] ?: emptyList()
        // + 按钮点击
        (tagRow.getChildAt(0) as? TextView)?.setOnClickListener { onManageTags(item) }

        // 预建 chip：只改 text 和 visibility，不新建 View
        tags.forEachIndexed { i, tag ->
            val chip = tagChips.getOrNull(i) ?: return@forEachIndexed
            chip.text = tag.name
            chip.visibility = android.view.View.VISIBLE
        }
        // 隐藏多余的 chip
        for (i in tags.size until tagChips.size) {
            tagChips[i].visibility = android.view.View.GONE
        }
        tagRow.visibility = android.view.View.VISIBLE
    }
}

// ── 显示模式选择器

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayModeSelector(
    currentMode: AlbumDisplayMode,
    onSelectMode: (AlbumDisplayMode) -> Unit,
    hasActiveFilter: Boolean = false,
    onOpenFilter: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val gridOptions = listOf(
        AlbumDisplayMode.Grid(2) to "2列",
        AlbumDisplayMode.Grid(3) to "3列",
        AlbumDisplayMode.Grid(4) to "4列",
        AlbumDisplayMode.Grid(5) to "5列",
    )
    val isListMode = currentMode is AlbumDisplayMode.List
    val isSelected = { mode: AlbumDisplayMode -> mode isSameAs currentMode }
    var expanded by remember { mutableStateOf(false) }

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // ── 筛选按钮 ──
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (hasActiveFilter) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
            tonalElevation = if (hasActiveFilter) 0.dp else 3.dp,
            shadowElevation = if (hasActiveFilter) 0.dp else 4.dp,
            onClick = onOpenFilter
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("☰ 筛选",
                    color = if (hasActiveFilter) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        // 列数选择
        Box {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (!isListMode) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                tonalElevation = if (!isListMode) 0.dp else 3.dp,
                shadowElevation = if (!isListMode) 0.dp else 4.dp,
                onClick = { expanded = true }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isListMode) "列数" else gridOptions.first { isSelected(it.first) }.second,
                        color = if (!isListMode) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "展开",
                        modifier = Modifier.size(16.dp),
                        tint = if (!isListMode) MaterialTheme.colorScheme.onPrimaryContainer
                               else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                gridOptions.forEach { (mode, label) ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected(mode)) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        },
                        onClick = {
                            onSelectMode(mode)
                            expanded = false
                        }
                    )
                }
            }
        }
        // 列表模式单独保留为按钮
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isListMode) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
            tonalElevation = if (isListMode) 0.dp else 3.dp,
            shadowElevation = if (isListMode) 0.dp else 4.dp,
            onClick = { onSelectMode(AlbumDisplayMode.List) }
        ) {
            Text(
                text = "☰ 列表",
                color = if (isListMode) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

// ── 排序方式选择器（同款风格下拉框）──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumSortSelector(
    currentSort: AlbumSortMode,
    onSelectSort: (AlbumSortMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = AlbumSortMode.entries.toList()
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
            onClick = { expanded = true }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${currentSort.label}",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "排序方式",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { sortMode ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = sortMode.label,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (sortMode == currentSort) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelectSort(sortMode)
                        expanded = false
                    }
                )
            }
        }
    }
}

// ══════════════════════════════════════
//  工具函数
// ══════════════════════════════════════

private fun checkPermission(context: android.content.Context): Boolean {
    val permissions = getRequiredPermissions()
    return permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

private fun getRequiredPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

// ══════════════════════════════════════
//  日期按钮（同排序 Surface chip 风格）
// ══════════════════════════════════════

@Composable
private fun DateButton(
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isActive) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
            tonalElevation = if (isActive) 0.dp else 3.dp,
            shadowElevation = if (isActive) 0.dp else 4.dp,
            onClick = onClick
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "日期",
                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

// ══════════════════════════════════════
//  日期分组视图数据与适配器
// ══════════════════════════════════════

/** 日期分组视图的单个条目 */
private sealed class DateViewItem {
    data class Header(val label: String) : DateViewItem()
    data class Media(val item: MediaItem, val allMediaIndex: Int = -1) : DateViewItem()
}

/** 将全部媒体项按 dateAdded 日期分组，生成 [DateViewItem] 扁平列表 */
private fun buildDateViewItems(allItems: List<MediaItem>): List<DateViewItem> {
    if (allItems.isEmpty()) return emptyList()
    val cal = java.util.Calendar.getInstance()
    val grouped = allItems.withIndex().groupBy { (_, item) ->
        cal.timeInMillis = item.dateAdded * 1000L
        val y = cal.get(java.util.Calendar.YEAR)
        val m = cal.get(java.util.Calendar.MONTH)
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        "$y-${m + 1}-$d"
    }
    return grouped.entries
        .sortedByDescending { it.key }
        .flatMap { (dateKey, entries) ->
            listOf(DateViewItem.Header(formatDateLabel(dateKey))) + entries.map { (idx, item) ->
                DateViewItem.Media(item, allMediaIndex = idx)
            }
        }
}

/** 将 "YYYY-M-D" 转为自然语言日期标签 */
private fun formatDateLabel(dateKey: String): String {
    val parts = dateKey.split("-")
    if (parts.size < 3) return dateKey
    val y = parts[0].toIntOrNull() ?: return dateKey
    val m = parts[1].toIntOrNull() ?: return dateKey
    val d = parts[2].toIntOrNull() ?: return dateKey
    val now = java.util.Calendar.getInstance()
    val target = java.util.Calendar.getInstance().apply {
        set(y, m - 1, d, 0, 0, 0)
    }
    now.set(now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH), now.get(java.util.Calendar.DAY_OF_MONTH), 0, 0, 0)
    val diffDays = ((now.timeInMillis - target.timeInMillis) / (1000L * 60 * 60 * 24)).toInt()
    return when {
        diffDays == 0 -> "今天"
        diffDays == 1 -> "昨天"
        diffDays in 2..6 -> {
            val names = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
            names[target.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        }
        y == now.get(java.util.Calendar.YEAR) -> "${m}月${d}日"
        else -> "${y}年${m}月${d}日"
    }
}

/** 日期分组视图的 Composable RecyclerView 包装 */
@Composable
private fun DateGroupRecyclerView(
    items: List<DateViewItem>,
    columns: Int,
    onClick: (MediaItem, allMediaIndex: Int) -> Unit,
    onLongClick: (MediaItem) -> Unit = {},
    selectedPaths: Set<String> = emptySet(),
    onDragSelectRange: (startPos: Int, endPos: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            val density = ctx.resources.displayMetrics.density
            val topPad = (44 * density).toInt()
            val adapter = DateGroupAdapter(emptyList(), columns, onClick).apply {
                this.onLongClick = onLongClick
                this.selectedPaths = selectedPaths
            }
            val rv = RecyclerView(ctx).apply {
                layoutManager = GridLayoutManager(ctx, columns).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int {
                            return if (adapter.getItemViewType(position) == DateGroupAdapter.VIEW_TYPE_HEADER) columns else 1
                        }
                    }
                }
                this.adapter = adapter
                clipToPadding = false
                setPadding(0, topPad, 0, 0)
            }

            // ── 滑动手势多选 ──
            val dragState = object {
                var dragStartIdx = -1
                var isDragging = false
            }
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val longPressMs = android.view.ViewConfiguration.getLongPressTimeout().toLong()
            var downX = 0f; var downY = 0f

            rv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            dragState.isDragging = false
                            dragState.dragStartIdx = -1
                            downX = e.x; downY = e.y
                            handler.removeCallbacksAndMessages(null)
                            handler.postDelayed({
                                val child = rv.findChildViewUnder(downX, downY) ?: return@postDelayed
                                val pos = rv.getChildAdapterPosition(child)
                                if (pos < 0) return@postDelayed
                                val item = items.getOrNull(pos) as? DateViewItem.Media ?: return@postDelayed
                                dragState.dragStartIdx = pos
                                dragState.isDragging = true
                                onLongClick(item.item)
                            }, longPressMs)
                            return false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val slop = android.view.ViewConfiguration.get(rv.context).scaledTouchSlop
                            if (kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop) {
                                handler.removeCallbacksAndMessages(null)
                            }
                            if (dragState.isDragging) return true
                            return false
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            handler.removeCallbacksAndMessages(null)
                            dragState.isDragging = false
                            dragState.dragStartIdx = -1
                            return false
                        }
                    }
                    return false
                }

                override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                    if (!dragState.isDragging) return
                    when (e.actionMasked) {
                        MotionEvent.ACTION_MOVE -> {
                            val child = rv.findChildViewUnder(e.x, e.y)
                            val pos = child?.let { rv.getChildAdapterPosition(it) } ?: return
                            onDragSelectRange(dragState.dragStartIdx, pos)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            dragState.isDragging = false
                            dragState.dragStartIdx = -1
                        }
                    }
                }
            })

            rv
        },
        update = { rv ->
            val adapter = rv.adapter as DateGroupAdapter
            val oldSelectedPaths = adapter.selectedPaths
            val oldColumns = adapter.columns
            adapter.columns = columns
            adapter.onLongClick = onLongClick
            adapter.selectedPaths = selectedPaths

            val oldItems = adapter.items
            val columnsChanged = oldColumns != columns
            if (oldItems !== items || columnsChanged) {
                adapter.items = items
                if (columnsChanged) {
                    // 列数变化：全量重建 VH，确保 onCreateViewHolder 用正确列数计算 side
                    adapter.notifyDataSetChanged()
                } else {
                    val diff = DiffUtil.calculateDiff(DateGroupDiffCallback(oldItems, items))
                    diff.dispatchUpdatesTo(adapter)
                    rv.requestLayout()
                }
            } else if (oldSelectedPaths != selectedPaths) {
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }

            (rv.layoutManager as GridLayoutManager).spanCount = columns
            (rv.layoutManager as GridLayoutManager).spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (adapter.getItemViewType(position) == DateGroupAdapter.VIEW_TYPE_HEADER) columns else 1
                }
            }
        },
        modifier = modifier.fillMaxSize()
    )
}

/** 日期分组 RecyclerView 适配器 */
private class DateGroupAdapter(
    var items: List<DateViewItem>,
    var columns: Int,
    private val onClick: (MediaItem, allMediaIndex: Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_MEDIA = 1
        const val VIEW_TYPE_LIST_MEDIA = 2
    }

    var onLongClick: (MediaItem) -> Unit = {}
    var selectedPaths: Set<String> = emptySet()

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long {
        return when (val item = items[position]) {
            is DateViewItem.Header -> "header_${item.label}".hashCode().toLong()
            is DateViewItem.Media -> item.item.id
        }
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is DateViewItem.Header -> VIEW_TYPE_HEADER
            is DateViewItem.Media -> if (columns == 1) VIEW_TYPE_LIST_MEDIA else VIEW_TYPE_MEDIA
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        val screenWidth = ctx.resources.displayMetrics.widthPixels

        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val tv = TextView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setTextSize(16f)
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
                }
                object : RecyclerView.ViewHolder(tv) {}
            }
            VIEW_TYPE_LIST_MEDIA -> {
                // 列表模式：缩略图（包对号覆盖层）+ 文件名 + 文件信息
                val gapPx = (2 * density).toInt()
                val thumbSize = (56 * density).toInt()
                val rv = parent as RecyclerView
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(gapPx, gapPx, gapPx, gapPx)
                }
                // 缩略图 FrameLayout（包裹对号覆盖层）
                val thumbFrame = FrameLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize)
                }
                val iv = ImageView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(thumbSize, thumbSize)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, (6f * density))
                        }
                    }
                    clipToOutline = true
                }
                thumbFrame.addView(iv)
                // 多选对号覆盖层
                val checkmark = FrameLayout(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        (36 * density).toInt(),
                        (36 * density).toInt(),
                        android.view.Gravity.CENTER
                    )
                    visibility = android.view.View.GONE
                    id = android.R.id.checkbox
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setShape(android.graphics.drawable.GradientDrawable.OVAL)
                        setColor(android.graphics.Color.argb(200, 76, 175, 80))
                    }
                    addView(TextView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.Gravity.CENTER
                        )
                        text = "✓"
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 20f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
                }
                thumbFrame.addView(checkmark)
                row.addView(thumbFrame)

                val textColumn = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        weight = 1f
                        setMargins((8 * density).toInt(), 0, 0, 0)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                }
                val nameTv = TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setTextSize(14f)
                    setTextColor(android.graphics.Color.WHITE)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                textColumn.addView(nameTv)
                val infoTv = TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setTextSize(12f)
                    setTextColor(android.graphics.Color.GRAY)
                    maxLines = 1
                }
                textColumn.addView(infoTv)
                row.addView(textColumn)

                row.setOnClickListener {
                    val pos = rv.getChildAdapterPosition(row)
                    if (pos < 0) return@setOnClickListener
                    val item = items.getOrNull(pos) as? DateViewItem.Media
                    if (item != null) onClick(item.item, item.allMediaIndex)
                }
                row.setOnLongClickListener {
                    val pos = rv.getChildAdapterPosition(row)
                    if (pos < 0) return@setOnLongClickListener true
                    val item = items.getOrNull(pos) as? DateViewItem.Media
                    if (item != null) onLongClick(item.item)
                    true
                }

                ListMediaViewHolder(row, iv, nameTv, infoTv)
            }
            else -> {
                // Grid 模式：正方形缩略图 + 多选对号覆盖层
                val gapPx = (2 * density).toInt()
                val side = (screenWidth / columns) - gapPx * 2
                val rv = parent as RecyclerView
                val frame = FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(side, side)
                    setPadding(gapPx, gapPx, gapPx, gapPx)
                }
                val iv = ImageView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, (6f * density))
                        }
                    }
                    clipToOutline = true
                }
                frame.addView(iv)
                // 多选对号（居中半透明绿色圆形 + 白色 ✓）
                val checkmark = FrameLayout(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        (36 * density).toInt(),
                        (36 * density).toInt(),
                        android.view.Gravity.CENTER
                    )
                    visibility = android.view.View.GONE
                    id = android.R.id.checkbox
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setShape(android.graphics.drawable.GradientDrawable.OVAL)
                        setColor(android.graphics.Color.argb(200, 76, 175, 80))
                    }
                    addView(TextView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.Gravity.CENTER
                        )
                        text = "✓"
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 20f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
                }
                frame.addView(checkmark)

                frame.setOnClickListener {
                    val pos = rv.getChildAdapterPosition(frame)
                    if (pos < 0) return@setOnClickListener
                    val item = items.getOrNull(pos) as? DateViewItem.Media
                    if (item != null) onClick(item.item, item.allMediaIndex)
                }
                frame.setOnLongClickListener {
                    val pos = rv.getChildAdapterPosition(frame)
                    if (pos < 0) return@setOnLongClickListener true
                    val item = items.getOrNull(pos) as? DateViewItem.Media
                    if (item != null) onLongClick(item.item)
                    true
                }
                object : RecyclerView.ViewHolder(frame) {}
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is DateViewItem.Header -> {
                (holder.itemView as TextView).text = item.label
            }
            is DateViewItem.Media -> {
                val isSelected = item.item.filePath in selectedPaths
                if (holder is ListMediaViewHolder) {
                    holder.itemView.tag = position
                    holder.imageView.load(item.item.uri) { crossfade(false) }
                    holder.nameView.text = item.item.fileName
                    holder.infoView.text = buildString {
                        if (item.item.isVideo && item.item.duration > 0) {
                            append("%d:%02d".format(item.item.duration / 1000 / 60, item.item.duration / 1000 % 60))
                        } else if (item.item.width > 0 && item.item.height > 0) {
                            append("${item.item.width}×${item.item.height}")
                        }
                        if (item.item.size > 0) {
                            append(" · ")
                            append(com.example.rcgallery.util.FormatUtil.formatFileSize(item.item.size))
                        }
                    }
                    val checkmark = holder.itemView.findViewById<FrameLayout>(android.R.id.checkbox)
                    if (checkmark != null) {
                        checkmark.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.GONE
                    }
                } else {
                    val frame = holder.itemView as FrameLayout
                    // 运行时按当前列数修正 VH 尺寸，防任何 VH 复用场景下图片拉伸
                    val density = frame.context.resources.displayMetrics.density
                    val screenWidth = frame.context.resources.displayMetrics.widthPixels
                    val gapPx = (2 * density).toInt()
                    val side = (screenWidth / columns) - gapPx * 2
                    val flp = frame.layoutParams
                    if (flp.width != side || flp.height != side) {
                        flp.width = side; flp.height = side
                        frame.layoutParams = flp
                    }
                    val iv = frame.getChildAt(0) as ImageView
                    // IV 用 MATCH_PARENT 自动适应 FrameLayout padding 后的区域
                    val ilp = iv.layoutParams
                    if (ilp.width != ViewGroup.LayoutParams.MATCH_PARENT || ilp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                        ilp.width = ViewGroup.LayoutParams.MATCH_PARENT
                        ilp.height = ViewGroup.LayoutParams.MATCH_PARENT
                        iv.layoutParams = ilp
                    }
                    // 尺寸变化后重设裁剪轮廓
                    val cornerRadiusPx = (6f * density)
                    iv.outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                        }
                    }
                    iv.load(item.item.uri) { crossfade(false) }
                    val checkmark = frame.findViewById<FrameLayout>(android.R.id.checkbox)
                    if (checkmark != null) {
                        checkmark.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.GONE
                    }
                }
            }
        }
    }

    private class ListMediaViewHolder(
        itemView: android.view.View,
        val imageView: ImageView,
        val nameView: TextView,
        val infoView: TextView
    ) : RecyclerView.ViewHolder(itemView) {}
}

private class DateGroupDiffCallback(
    private val old: List<DateViewItem>,
    private val new: List<DateViewItem>
) : DiffUtil.Callback() {
    override fun getOldListSize() = old.size
    override fun getNewListSize() = new.size

    override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
        return when {
            old[oldPos] is DateViewItem.Header && new[newPos] is DateViewItem.Header ->
                (old[oldPos] as DateViewItem.Header).label == (new[newPos] as DateViewItem.Header).label
            old[oldPos] is DateViewItem.Media && new[newPos] is DateViewItem.Media ->
                (old[oldPos] as DateViewItem.Media).item.id == (new[newPos] as DateViewItem.Media).item.id
            else -> false
        }
    }

    override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
        return old[oldPos] == new[newPos]
    }
}


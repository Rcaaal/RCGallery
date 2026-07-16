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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Precision
import com.example.rcgallery.model.Album
import com.example.rcgallery.model.MediaItem
import com.example.rcgallery.model.SystemTags
import com.example.rcgallery.data.db.TagEntity
import com.example.rcgallery.data.db.ParentAlbumEntity
import com.example.rcgallery.ui.component.DevOverlay
import com.example.rcgallery.ui.component.FastScrollerView
import com.example.rcgallery.ui.component.InertiaSettingsPanel
import com.example.rcgallery.ui.screen.TrashScreen
import com.example.rcgallery.ui.component.FloatingJumpButton
import com.example.rcgallery.ui.component.FloatingMultiSelectButtons
import com.example.rcgallery.ui.component.ClipboardBadge
import com.example.rcgallery.ui.component.AlbumPickDialog
import com.example.rcgallery.ui.component.AlbumStoragePickDialog
import com.example.rcgallery.ui.component.AutoFocusRenameTextField
import com.example.rcgallery.viewmodel.PasteMode
import com.example.rcgallery.ui.component.FpsMonitor
import com.example.rcgallery.ui.component.FpsMonitorEnabled
import com.example.rcgallery.ui.component.TagManageDialog
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.util.FormatUtil
import com.example.rcgallery.viewmodel.GalleryViewModel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

// 跨闭包信号：设为 true 时触发搜索建议关闭 + 输入框失焦
// 用模块级变量而非 composable 局部变量，解决 Kotlin 2.1 Compose 编译器对普通 lambda 捕获限制
private val _dismissSearchSuggestions = mutableStateOf(false)
// 跨闭包信号：失焦时仅隐藏建议，不收 IME（和 _dismissSearchSuggestions 分离，防止失焦抖动导致键盘被收回）
private val _hideSearchSuggestions = mutableStateOf(false)

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
    VIDEO_COUNT("视频数量"),
    RECENT_ACCESS("近期访问"),
    RECENTLY_MOVED("最近移动")
}

/** 根据排序模式生成 Comparator<Album> */
private fun albumSortComparator(mode: AlbumSortMode): Comparator<Album> = when (mode) {
    AlbumSortMode.DATE -> compareByDescending { it.dateAdded }
    AlbumSortMode.NAME -> compareBy { it.bucketName }
    AlbumSortMode.SIZE -> compareByDescending { it.totalSize }
    AlbumSortMode.IMAGE_COUNT -> compareByDescending { it.imageCount }
    AlbumSortMode.VIDEO_COUNT -> compareByDescending { it.videoCount }
    AlbumSortMode.RECENT_ACCESS -> compareByDescending<Album> { 0L }  // 简写：不在本函数内处理
    AlbumSortMode.RECENTLY_MOVED -> compareByDescending<Album> { 0L }  // 简写：不在本函数内处理
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
    val systemHiddenAlbumPaths by viewModel.systemHiddenAlbumPaths.collectAsStateWithLifecycle()
    val persistentRules by viewModel.persistentRules.collectAsStateWithLifecycle()
    val recentAccessMap by viewModel.recentAccessMap.collectAsStateWithLifecycle()
    val recentMoveAlbums by viewModel.recentMoveAlbums.collectAsStateWithLifecycle()
    val recentMigrationRoots by viewModel.recentMigrationRoots.collectAsStateWithLifecycle()

    // ── 层级相册 ──
    val parentEntities by viewModel.parentEntities.collectAsStateWithLifecycle()
    val parentChildrenBucketMap by viewModel.parentChildrenBucketMap.collectAsStateWithLifecycle()
    val allChildBucketIds by viewModel.allChildBucketIds.collectAsStateWithLifecycle()
    val parentSharedTagMap by viewModel.parentSharedTagMap.collectAsStateWithLifecycle()

    // Grid/List 统一父子关系查找：一次遍历 parentChildrenBucketMap 构建两个 map
    val parentBadgeMap: Map<String, String>
    val childToParentMap: Map<String, Long>
    val result = remember(parentChildrenBucketMap, parentEntities) {
        val badge = mutableMapOf<String, String>()
        val lookup = mutableMapOf<String, Long>()
        for ((parentId, childBucketIds) in parentChildrenBucketMap) {
            val parentName = parentEntities.find { it.id == parentId }?.name ?: continue
            childBucketIds.forEach { bucketId ->
                badge[bucketId] = parentName
                lookup[bucketId] = parentId
            }
        }
        Triple(badge, lookup, Unit)
    }
    parentBadgeMap = result.first
    childToParentMap = result.second

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
            "recent" -> AlbumSortMode.RECENT_ACCESS
            "recently_moved" -> AlbumSortMode.RECENTLY_MOVED
            else -> AlbumSortMode.DATE
        })
    }

    // ── 搜索状态 ──
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 下拉菜单显隐：放在 LaunchedEffect 前，确保 suspend lambda 可捕获
    var showSuggestions by remember { mutableStateOf(true) }

    // 收到顶层信号后收键盘 + 隐藏搜索建议（不退出搜索态，不影响焦点状态）
    LaunchedEffect(_dismissSearchSuggestions.value) {
        if (_dismissSearchSuggestions.value) {
            keyboardController?.hide()
            showSuggestions = false
            _dismissSearchSuggestions.value = false
        }
    }
    // 收到失焦信号后仅隐藏建议（不碰 IME，防止焦点抖动导致键盘被收回）
    LaunchedEffect(_hideSearchSuggestions.value) {
        if (_hideSearchSuggestions.value) {
            showSuggestions = false
            _hideSearchSuggestions.value = false
        }
    }

    /** 退出搜索模式（关闭按钮、返回键等明确退出入口） */
    fun exitSearch() {
        searchQuery = ""
        isSearchActive = false
        keyboardController?.hide()
        focusManager.clearFocus()
    }
    // 进入搜索模式时：重置建议显隐、延迟聚焦（等 TextField 挂载）、弹 IME
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            showSuggestions = true
            delay(1)
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // 本地排序：根据排序模式排列相册
    // RECENT_ACCESS 和 RECENTLY_MOVED 是时间相关排序，星标不置顶以免干扰时间顺序
    // recentAccessMap/recentMoveAlbums 通过 .value 在 remember 内捕获快照，
    // 不作为 key，避免每次点击相册更新访问时间后重新排序导致位置错位
    val albums = remember(rawAlbums, starredIds, albumSortMode) {
        when (albumSortMode) {
            AlbumSortMode.RECENTLY_MOVED -> {
                val moveMap = viewModel.recentMoveAlbums.value.associate { it.directoryPath to it.movedAt }
                rawAlbums.sortedWith(
                    compareByDescending<Album> { moveMap[it.directoryPath] ?: 0L }
                        .then(compareByDescending { it.dateAdded })
                )
            }
            AlbumSortMode.RECENT_ACCESS ->
                rawAlbums.sortedWith(
                    compareByDescending<Album> { viewModel.recentAccessMap.value[it.directoryPath] }
                        .then(compareByDescending { it.dateAdded })
                )
            else ->
                rawAlbums.sortedWith(
                    compareByDescending<Album> { it.bucketId in starredIds }
                        .then(when (albumSortMode) {
                            AlbumSortMode.DATE -> compareByDescending { it.dateAdded }
                            AlbumSortMode.NAME -> compareBy { it.bucketName }
                            AlbumSortMode.SIZE -> compareByDescending { it.totalSize }
                            AlbumSortMode.IMAGE_COUNT -> compareByDescending { it.imageCount }
                            AlbumSortMode.VIDEO_COUNT -> compareByDescending { it.videoCount }
                            else -> compareByDescending { it.dateAdded }
                        })
                )
        }
    }

    // 搜索建议：取 albums（排序后原始列表）中匹配的相册名，最多 8 条供下拉展示
    val searchSuggestions = remember(albums, searchQuery) {
        if (searchQuery.isBlank()) emptyList()
        else albums.filter { it.bucketName.contains(searchQuery, ignoreCase = true) }.take(8)
    }

    // ── 日期分组视图状态（持久化）──
    var isDateView by remember {
        mutableStateOf(prefs.getBoolean("date_view", false))
    }
    val allMediaItems by viewModel.allMediaItems.collectAsStateWithLifecycle()
    val isAllMediaInitialLoading by viewModel.isAllMediaInitialLoading.collectAsStateWithLifecycle()
    val tempFilter by viewModel.tempFilter.collectAsStateWithLifecycle()
    // 日期视图：按相册筛选规则过滤（被隐藏的相册中的内容也不在日期模式出现）
    val dateMediaItems = remember(allMediaItems, albums, persistentRules, albumTags, tempFilter, systemHiddenAlbumPaths) {
        val hasActiveAlbumRule = persistentRules.any {
            it.enabled && (it.scope == com.example.rcgallery.model.FilterScope.ALBUM || it.scope == com.example.rcgallery.model.FilterScope.BOTH)
        }
        val hasTemp = tempFilter.isActive
        if (!hasActiveAlbumRule && !hasTemp) {
            allMediaItems
        } else {
            val hiddenBucketIds = albums.filter { album ->
                val tagNames = buildList {
                    addAll(albumTags[album.directoryPath]?.map { it.name }.orEmpty())
                    if (viewModel.isSystemHiddenAlbum(album.directoryPath)) add(SystemTags.HID_NAME)
                }
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
        viewModel.setAllMediaViewActive(isDateView)
        if (isDateView) {
            viewModel.loadAllMedia()
        } else if (selectedDatePhotoIndex >= 0) {
            // 退出日期视图时若有 Preview 开着，恢复底部导航栏
            selectedDatePhotoIndex = -1
            onAlbumActiveChanged(false)
        }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.setAllMediaViewActive(false) }
    }

    // ── MediaGrid overlay 状态（代替 navigation push，LazyVerticalGrid 保持存活）──
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    var selectedAlbumName by remember { mutableStateOf("") }
    var selectedAlbumDirectoryPath by remember { mutableStateOf("") }
    var showAlbumPickDialog by remember { mutableStateOf(false) }
    var directAlbumPickItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    val albumPickScope = rememberCoroutineScope()
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
    // ── 搜索态独立返回拦截（避免系统接管导致回桌面）──
    if (isSearchActive) {
        BackHandler { exitSearch() }
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
    var migrateTargetAlbum by remember { mutableStateOf<Album?>(null) }
    var migratingAlbumName by remember { mutableStateOf<String?>(null) }
    val renameLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        Toast.makeText(context, "授权成功，请重新长按相册进行改名", Toast.LENGTH_SHORT).show()
    }
    if (showAlbumRenameDialog && renameTargetAlbum != null) {
        val target = renameTargetAlbum!!
        val currentName = target.bucketName.ifEmpty { "未知" }
        var editText by remember { mutableStateOf(currentName) }
        fun confirmAlbumRename() {
            val newName = editText.trim()
            if (newName.isEmpty()) return
            showAlbumRenameDialog = false
            renameTargetAlbum = null
            if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
                viewModel.renameNow(target.bucketId, newName) { ok ->
                    Toast.makeText(
                        context,
                        if (ok) "相册已重命名" else "重命名失败，请重试",
                        Toast.LENGTH_SHORT
                    ).show()
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
        }
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
                    AutoFocusRenameTextField(
                        initialText = currentName,
                        onValueChange = { editText = it },
                        onDone = { confirmAlbumRename() },
                        label = "新相册名"
                    )
                }
            },
            confirmButton = {
                Button(onClick = { confirmAlbumRename() }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showAlbumRenameDialog = false; renameTargetAlbum = null }) { Text("取消") } }
        )
    }

    migrateTargetAlbum?.let { targetAlbum ->
        AlbumStoragePickDialog(
            sourceAlbum = targetAlbum,
            recentRoots = recentMigrationRoots,
            onDismiss = { migrateTargetAlbum = null },
            onSelectRoot = { root ->
                migrateTargetAlbum = null
                migratingAlbumName = targetAlbum.bucketName
                viewModel.migrateAlbumStorage(targetAlbum, root.absolutePath) { result ->
                    migratingAlbumName = null
                    Toast.makeText(
                        context,
                        result.fold(
                            onSuccess = { "相册已迁移到 ${FormatUtil.formatDisplayPath(it)}" },
                            onFailure = { it.message ?: "迁移失败" }
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }
    migratingAlbumName?.let { albumName ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在迁移") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在迁移“$albumName”，请勿退出")
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }

    // ── 权限状态 ──
    var hasPermission by remember {
        mutableStateOf(checkPermission(context))
    }

    // ── RecyclerView 引用（给 FloatingJumpButton 用）──
    val albumRvRef = remember { mutableStateOf<RecyclerView?>(null) }

    // ── 筛选规则过滤后的相册列表（持久规则 + 临时筛选叠加）──
    val filteredAlbums = remember(albums, persistentRules, albumTags, tempFilter, systemHiddenAlbumPaths) {
        val hasActiveAlbumRule = persistentRules.any {
            it.enabled && (it.scope == com.example.rcgallery.model.FilterScope.ALBUM || it.scope == com.example.rcgallery.model.FilterScope.BOTH)
        }
        val hasTemp = tempFilter.isActive
        AppLogger.d("Filter", "filteredAlbums recalc persistent=$hasActiveAlbumRule temp=$hasTemp")
        if (!hasActiveAlbumRule && !hasTemp) albums
        else {
            albums.filter { album ->
                val tagNames = buildList {
                    addAll(albumTags[album.directoryPath]?.map { it.name }.orEmpty())
                    if (viewModel.isSystemHiddenAlbum(album.directoryPath)) add(SystemTags.HID_NAME)
                }
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

    // ── 搜索过滤（基于已筛选的相册列表再做名称匹配）──
    val displayAlbums = remember(filteredAlbums, searchQuery) {
        if (searchQuery.isBlank()) filteredAlbums
        else filteredAlbums.filter { it.bucketName.contains(searchQuery, ignoreCase = true) }
    }

    // ── 父级相册搜索匹配（父级名或任意子相册名匹配即命中，用于 List 模式的 parentItems 搜索过滤）──
    val matchedParentIds = remember(searchQuery, albums, parentEntities, parentChildrenBucketMap) {
        if (searchQuery.isBlank()) emptySet()
        else {
            val q = searchQuery.trim()
            if (q.isEmpty()) emptySet()
            else parentEntities.filter { parent ->
                parent.name.contains(q, ignoreCase = true) ||
                (parentChildrenBucketMap[parent.id]?.any { childBucketId ->
                    albums.any { it.bucketId == childBucketId && it.bucketName.contains(q, ignoreCase = true) }
                } == true)
            }.map { it.id }.toSet()
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
                    albums = displayAlbums,
                    starredIds = starredIds,
                    albumTags = albumTags,
                    systemHiddenAlbumPaths = systemHiddenAlbumPaths,
                    isSearchActive = isSearchActive,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it; showSuggestions = true },
                    onActivateSearch = { isSearchActive = true },
                    onExitSearch = { exitSearch() },
                    searchSuggestions = searchSuggestions,
                    searchFocusRequester = searchFocusRequester,
                    onAlbumClick = { album ->
                        AppLogger.d("AlbumGrid", "click album=${album.bucketName} id=${album.bucketId} count=${album.count}")
                        // 点击相册时不退出搜索态，只隐藏键盘、清焦点
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        viewModel.recordAlbumView(album.bucketId, album.bucketName, album.directoryPath)
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
                    onMigrateAlbum = { album -> migrateTargetAlbum = album },
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
                            AlbumSortMode.RECENT_ACCESS -> "recent"
                            AlbumSortMode.RECENTLY_MOVED -> "recently_moved"
                        }).apply()
                    },
                    isDateView = isDateView,
                    onToggleDateView = {
                        isDateView = !isDateView
                        prefs.edit().putBoolean("date_view", isDateView).apply()
                    },
                    allDateMediaItems = dateMediaItems,
                    isDateInitialLoading = isAllMediaInitialLoading,
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
                        viewModel.moveToTrash(toDelete)
                    },
                    onAddDateMediaToClipboard = { items -> viewModel.addToClipboard(items) },
                    onPickDateMediaTarget = { items ->
                        directAlbumPickItems = items
                        showAlbumPickDialog = true
                    },
                    hasActiveFilter = persistentRules.any { it.enabled } || tempFilter.isActive,
                    onOpenFilter = { showFilterPage = true },
                    // ── 层级相册 ──
                    parentEntities = parentEntities,
                    parentChildrenBucketMap = parentChildrenBucketMap,
                    allChildBucketIds = allChildBucketIds,
                    parentBadgeMap = parentBadgeMap,
                    childToParentMap = childToParentMap,
                    onCreateParent = { name, onResult -> viewModel.createParentAlbum(name, onResult) },
                    onRenameParent = { id, name, onResult -> viewModel.renameParentAlbum(id, name, onResult) },
                    onDeleteParent = { id -> viewModel.deleteParentAlbum(id) },
                    onAddChildren = { parentId, bucketIds -> viewModel.addChildrenToParent(parentId, bucketIds) },
                    onRemoveChild = { parentId, bucketId -> viewModel.removeChildFromParent(parentId, bucketId) },
                    onRemoveChildren = { parentId, bucketIds -> viewModel.removeChildrenFromParent(parentId, bucketIds) },
                    onAddSharedTag = { parentId, tagName -> viewModel.addSharedTagToParent(parentId, tagName) },
                    onRemoveSharedTag = { parentId, tagId -> viewModel.removeSharedTagFromParent(parentId, tagId) },
                    parentSharedTagMap = parentSharedTagMap,
                    matchedParentIds = matchedParentIds
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
                        systemGalleryHidden = systemHiddenAlbumPaths.any {
                            it.equals(currentTagAlbum.directoryPath, ignoreCase = true)
                        },
                        onSystemGalleryHiddenChange = { hidden ->
                            viewModel.setSystemGalleryHidden(currentTagAlbum.directoryPath, hidden)
                        },
                        onAddTag = { name -> viewModel.addAlbumTag(currentTagAlbum.directoryPath, name) },
                        onRemoveTag = { tagId -> viewModel.removeAlbumTag(currentTagAlbum.directoryPath, tagId) },
                        onDismiss = { tagDialogAlbum = null }
                    )
                }
            }
            // ── 搜索建议（在 content 中渲染，不走 Popup，避免抢输入法焦点）──
            if (isSearchActive && searchSuggestions.isNotEmpty() && showSuggestions) {
                SearchSuggestionsDropdown(
                    suggestions = searchSuggestions,
                    searchQuery = searchQuery,
                    onSuggestionClick = { album ->
                        searchQuery = album.bucketName
                        showSuggestions = false
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, end = 12.dp, top = 56.dp)
                )
            }
            FpsMonitor(enabled = FpsMonitorEnabled, modifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 8.dp))
            FloatingJumpButton(recyclerView = albumRvRef.value, modifier = Modifier.align(Alignment.BottomStart))

            // ── 中转站浮动 badge（仅当不在 MediaGrid/Trash/Preview overlay 中时显示）──
            if (selectedAlbumId == null && !showTrash && selectedDatePhotoIndex < 0) {
                val clipboardItems by viewModel.clipboardItems.collectAsStateWithLifecycle()
                if (clipboardItems.isNotEmpty()) {
                    ClipboardBadge(
                        clipboardCount = clipboardItems.size,
                        currentAlbumDir = null,  // 不传入当前相册（相册列表模式下没有"当前相册"）
                        onPasteToAlbum = { mode, dir -> viewModel.pasteToAlbum(mode, dir, "") },
                        onPickTargetAlbum = {
                            directAlbumPickItems = emptyList()
                            showAlbumPickDialog = true
                        },
                        onClear = { viewModel.clearClipboard() },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 80.dp)
                    )
                }
            }

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
                val allTags by viewModel.filterableTags.collectAsStateWithLifecycle()
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

            // ── 选择目标相册对话框 ──
            if (showAlbumPickDialog) {
                val allAlbums by viewModel.albums.collectAsStateWithLifecycle()
                val recentDirs by viewModel.recentMoveAlbums.collectAsStateWithLifecycle()
                AlbumPickDialog(
                    albums = allAlbums,
                    recentMoveAlbums = recentDirs,
                    onDismiss = {
                        showAlbumPickDialog = false
                        directAlbumPickItems = emptyList()
                    },
                    onAlbumSelected = { targetDir, targetName, mode ->
                        val directItems = directAlbumPickItems
                        if (directItems.isNotEmpty()) {
                            if (mode == PasteMode.MOVE) {
                                viewModel.moveItemsToAlbum(directItems, targetDir, targetName)
                            } else {
                                viewModel.copyItemsToAlbum(directItems, targetDir, targetName)
                            }
                        } else {
                            viewModel.pasteToAlbum(mode, targetDir, targetName)
                        }
                        directAlbumPickItems = emptyList()
                    },
                    onCreateFolder = { name, onResult ->
                        albumPickScope.launch(Dispatchers.IO) {
                            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                            val dir = java.io.File(dcim, name)
                            val path = if (dir.mkdirs() || dir.exists()) {
                                viewModel.loadAlbums()
                                dir.absolutePath
                            } else null
                            onResult(path)
                        }
                    }
                )
            }

            val moveFailures by viewModel.moveFailures.collectAsStateWithLifecycle()
            if (moveFailures.isNotEmpty() && selectedAlbumId == null && selectedDatePhotoIndex < 0 && !showTrash) {
                val grouped = moveFailures.groupBy { it.reason }.mapValues { it.value.size }
                AlertDialog(
                    onDismissRequest = { viewModel.clearMoveFailures() },
                    title = { Text("移动未完全成功") },
                    text = {
                        Column {
                            Text("共 ${moveFailures.size} 个文件移动失败：")
                            Spacer(Modifier.height(8.dp))
                            grouped.forEach { (reason, count) ->
                                val message = when (reason) {
                                    GalleryViewModel.MoveFailureReason.SOURCE_DELETE_FAILED ->
                                        "$count 个文件删除源文件失败，请检查所有文件访问权限。"
                                    GalleryViewModel.MoveFailureReason.TARGET_VERIFICATION_FAILED ->
                                        "$count 个文件目标验证失败，请检查存储空间。"
                                    GalleryViewModel.MoveFailureReason.MEDIASTORE_UPDATE_FAILED ->
                                        "$count 个文件 MediaStore 移动或复制失败。"
                                    GalleryViewModel.MoveFailureReason.SCAN_FAILED ->
                                        "$count 个文件媒体扫描注册失败。"
                                    GalleryViewModel.MoveFailureReason.SOURCE_NOT_FOUND ->
                                        "$count 个源文件已不存在。"
                                    GalleryViewModel.MoveFailureReason.UNKNOWN_ERROR ->
                                        "$count 个文件发生未知错误。"
                                }
                                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = { viewModel.clearMoveFailures() }) { Text("知道了") }
                    }
                )
            }

            // ── 文件冲突对话框 ──
            val fileConflict by viewModel.fileConflict.collectAsStateWithLifecycle()
            if (fileConflict != null) {
                val conflict = fileConflict ?: return@Box
                var applyToAll by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = {
                        viewModel.respondConflict(
                            GalleryViewModel.FileConflictResponse(
                                GalleryViewModel.FileConflictAction.SKIP, false
                            )
                        )
                    },
                    title = { Text("文件冲突") },
                    text = {
                        Column {
                            Text("目标目录中已存在「${conflict.sourceFileName}」")
                            Spacer(Modifier.height(4.dp))
                            Text("如何处理此文件？(${conflict.index}/${conflict.total})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = applyToAll,
                                    onCheckedChange = { applyToAll = it }
                                )
                                Text("对全部冲突应用此操作", fontSize = 13.sp)
                            }
                        }
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                viewModel.respondConflict(
                                    GalleryViewModel.FileConflictResponse(GalleryViewModel.FileConflictAction.OVERWRITE, applyToAll)
                                )
                            }) { Text("覆盖") }
                            TextButton(onClick = {
                                viewModel.respondConflict(
                                    GalleryViewModel.FileConflictResponse(GalleryViewModel.FileConflictAction.SKIP, applyToAll)
                                )
                            }) { Text("跳过") }
                            TextButton(onClick = {
                                viewModel.respondConflict(
                                    GalleryViewModel.FileConflictResponse(GalleryViewModel.FileConflictAction.RENAME, applyToAll)
                                )
                            }) { Text("重命名") }
                        }
                    },
                    dismissButton = {}
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
    onMigrateAlbum: (Album) -> Unit = {},
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
    systemHiddenAlbumPaths: Set<String> = emptySet(),
    allTags: List<TagEntity> = emptyList(),
    onBatchAddTagsToAlbums: (String, String) -> Unit = { _, _ -> },
    onBatchAddTagsToMedia: (String, String) -> Unit = { _, _ -> },
    onDeleteDateMedia: (List<String>) -> Unit = {},
    onAddDateMediaToClipboard: (List<MediaItem>) -> Unit = {},
    onPickDateMediaTarget: (List<MediaItem>) -> Unit = {},
    // ── 搜索参数 ──
    isSearchActive: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    onActivateSearch: () -> Unit = {},
    onExitSearch: () -> Unit = {},
    searchSuggestions: List<Album> = emptyList(),
    searchFocusRequester: FocusRequester = FocusRequester(),
    // ── 日期分组视图参数 ──
    isDateView: Boolean = false,
    onToggleDateView: () -> Unit = {},
    allDateMediaItems: List<MediaItem> = emptyList(),
    isDateInitialLoading: Boolean = false,
    selectedDatePhotoIndex: Int = -1,
    onDatePhotoClick: (Int) -> Unit = {},
    onDatePhotoBack: () -> Unit = {},
    // ── 筛选参数 ──
    hasActiveFilter: Boolean = false,
    onOpenFilter: () -> Unit = {},
    // ── 层级相册参数 ──
    parentEntities: List<ParentAlbumEntity> = emptyList(),
    parentChildrenBucketMap: Map<Long, List<String>> = emptyMap(),
    allChildBucketIds: Set<String> = emptySet(),
    parentBadgeMap: Map<String, String> = emptyMap(),
    childToParentMap: Map<String, Long> = emptyMap(),
    onCreateParent: (String, (Result<ParentAlbumEntity>) -> Unit) -> Unit = { _, _ -> },
    onRenameParent: (Long, String, (Result<Unit>) -> Unit) -> Unit = { _, _, _ -> },
    onDeleteParent: (Long) -> Unit = {},
    onAddChildren: (Long, List<String>) -> Unit = { _, _ -> },
    onRemoveChild: (Long, String) -> Unit = { _, _ -> },
    onRemoveChildren: (Long, List<String>) -> Unit = { _, _ -> },
    onAddSharedTag: (Long, String) -> Unit = { _, _ -> },
    onRemoveSharedTag: (Long, Long) -> Unit = { _, _ -> },
    parentSharedTagMap: Map<Long, List<TagEntity>> = emptyMap(),
    matchedParentIds: Set<Long> = emptySet()
) {
    // ── Grid 模式多选状态 ──
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
    var selectedDateMediaUris by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun exitDateMultiSelect() {
        isDateMultiSelect = false
        selectedDateMediaUris = emptySet()
    }

    fun toggleDateMediaSelection(uri: String) {
        selectedDateMediaUris = if (uri in selectedDateMediaUris)
            selectedDateMediaUris - uri
        else
            selectedDateMediaUris + uri
        if (selectedDateMediaUris.isEmpty()) isDateMultiSelect = false
    }

    val selectedDateMediaItems = remember(allDateMediaItems, selectedDateMediaUris) {
        allDateMediaItems.filter { it.uri.toString() in selectedDateMediaUris }
    }

    LaunchedEffect(isDateView) {
        if (!isDateView && isDateMultiSelect) exitDateMultiSelect()
    }
    LaunchedEffect(allDateMediaItems) {
        if (selectedDateMediaUris.isNotEmpty()) {
            val currentUris = allDateMediaItems.mapTo(HashSet()) { it.uri.toString() }
            selectedDateMediaUris = selectedDateMediaUris.intersect(currentUris)
            if (selectedDateMediaUris.isEmpty()) isDateMultiSelect = false
        }
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

    // ── 层级相册状态 ──
    var expandedParentIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var renameParentId by remember { mutableStateOf<Long?>(null) }
    var showParentRenameDialog by remember { mutableStateOf(false) }
    var addToParentId by remember { mutableStateOf<Long?>(null) }
    var showAddChildDialog by remember { mutableStateOf(false) }
    var showCreateParentDialog by remember { mutableStateOf(false) }
    // ── 解散/移出确认状态 ──
    var showDeleteParentConfirm by remember { mutableStateOf(false) }
    var targetDeleteParentId by remember { mutableStateOf<Long?>(null) }
    var showRemoveChildConfirm by remember { mutableStateOf(false) }
    var targetRemoveParentId by remember { mutableStateOf<Long?>(null) }
    var targetRemoveChildBucketId by remember { mutableStateOf<String?>(null) }

    // ── 添加/移除共享 TAG 对话框状态 ──
    var manageSharedTagParentId by remember { mutableStateOf<Long?>(null) }

    // ── 创建父级对话框 ──
    if (showCreateParentDialog) {
        var parentNameInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateParentDialog = false },
            title = { Text("创建层级相册") },
            text = {
                Column {
                    Text("输入父级相册名称：", fontSize = 13.sp, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = parentNameInput,
                        onValueChange = { parentNameInput = it },
                        singleLine = true,
                        placeholder = { Text("名称（不能重名）", fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = parentNameInput.trim()
                        if (name.isEmpty()) return@Button
                        showCreateParentDialog = false
                        onCreateParent(name) {}
                    }
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateParentDialog = false }) { Text("取消") } }
        )
    }

    // ── 重命名父级对话框 ──
    if (showParentRenameDialog && renameParentId != null) {
        val targetParent = parentEntities.find { it.id == renameParentId }
        val currentParentName = targetParent?.name ?: ""
        var newName by remember { mutableStateOf(currentParentName) }
        fun confirmParentRename() {
            val name = newName.trim()
            if (name.isEmpty() || targetParent == null) return
            showParentRenameDialog = false
            renameParentId = null
            onRenameParent(targetParent.id, name) {}
        }
        AlertDialog(
            onDismissRequest = { showParentRenameDialog = false; renameParentId = null },
            title = { Text("重命名") },
            text = {
                Column {
                    Text("输入新名称：", fontSize = 13.sp, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    AutoFocusRenameTextField(
                        initialText = currentParentName,
                        onValueChange = { newName = it },
                        onDone = { confirmParentRename() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = { confirmParentRename() }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showParentRenameDialog = false; renameParentId = null }) { Text("取消") } }
        )
    }

    // ── 添加子相册对话框 ──
    if (showAddChildDialog && addToParentId != null) {
        val availableAlbums: List<Album> = albums.filter { it.bucketId !in allChildBucketIds }
        var selectedAddBucketIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var searchQuery by remember { mutableStateOf("") }
        val filtered: List<Album> = remember(availableAlbums, searchQuery) {
            if (searchQuery.isBlank()) availableAlbums
            else availableAlbums.filter { it.bucketName.contains(searchQuery, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { showAddChildDialog = false; addToParentId = null },
            title = { Text("添加子相册") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("搜索相册...", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    if (filtered.isEmpty()) {
                        Text("没有可添加的相册", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(8.dp))
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(filtered, key = { album -> album.bucketId }) { album ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedAddBucketIds = if (album.bucketId in selectedAddBucketIds)
                                                selectedAddBucketIds - album.bucketId
                                            else selectedAddBucketIds + album.bucketId
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = album.bucketId in selectedAddBucketIds,
                                        onCheckedChange = {
                                            selectedAddBucketIds = if (album.bucketId in selectedAddBucketIds)
                                                selectedAddBucketIds - album.bucketId
                                            else selectedAddBucketIds + album.bucketId
                                        }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(album.bucketName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text("${album.count} 项", fontSize = 11.sp, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedAddBucketIds.isNotEmpty(),
                    onClick = {
                        onAddChildren(addToParentId!!, selectedAddBucketIds.toList())
                        showAddChildDialog = false
                        addToParentId = null
                    }
                ) { Text("添加 (${selectedAddBucketIds.size})") }
            },
            dismissButton = { TextButton(onClick = { showAddChildDialog = false; addToParentId = null }) { Text("取消") } }
        )
    }

    // ── 解散父级确认对话框 ──
    if (showDeleteParentConfirm && targetDeleteParentId != null) {
        val parentName = parentEntities.find { it.id == targetDeleteParentId }?.name ?: "未知"
        AlertDialog(
            onDismissRequest = { showDeleteParentConfirm = false; targetDeleteParentId = null },
            title = { Text("解散父级相册") },
            text = {
                Column {
                    Text("确认解散「${parentName}」？")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• 子相册会回到根层",
                        fontSize = 13.sp,
                        color = Color(0xFFCCCCCC)
                    )
                    Text(
                        "• 不会删除子相册本身",
                        fontSize = 13.sp,
                        color = Color(0xFFCCCCCC)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteParent(targetDeleteParentId!!)
                        showDeleteParentConfirm = false
                        targetDeleteParentId = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("确认解散") }
            },
            dismissButton = { TextButton(onClick = { showDeleteParentConfirm = false; targetDeleteParentId = null }) { Text("取消") } }
        )
    }

    // ── 移出子相册确认对话框 ──
    if (showRemoveChildConfirm && targetRemoveParentId != null && targetRemoveChildBucketId != null) {
        val childAlbum = albums.find { it.bucketId == targetRemoveChildBucketId }
        val childName = childAlbum?.bucketName ?: "此相册"
        AlertDialog(
            onDismissRequest = { showRemoveChildConfirm = false; targetRemoveParentId = null; targetRemoveChildBucketId = null },
            title = { Text("移出子相册") },
            text = {
                Column {
                    Text("确认将「${childName}」从父级中移出？")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "移出后该相册将回到根层",
                        fontSize = 13.sp,
                        color = Color(0xFFCCCCCC)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveChild(targetRemoveParentId!!, targetRemoveChildBucketId!!)
                        showRemoveChildConfirm = false
                        targetRemoveParentId = null
                        targetRemoveChildBucketId = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("确认移出") }
            },
            dismissButton = { TextButton(onClick = { showRemoveChildConfirm = false; targetRemoveParentId = null; targetRemoveChildBucketId = null }) { Text("取消") } }
        )
    }

    // ── 管理共享 TAG 对话框（统一添加 / 移除）──
    if (manageSharedTagParentId != null) {
        val targetParentId = manageSharedTagParentId!!
        val currentTags = parentSharedTagMap[targetParentId] ?: emptyList()
        var tagInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { manageSharedTagParentId = null },
            title = { Text("管理共享 TAG") },
            text = {
                Column {
                    // ── 添加区域 ──
                    Text("添加共享 TAG：", fontSize = 13.sp, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = tagInput,
                            onValueChange = { tagInput = it },
                            singleLine = true,
                            placeholder = { Text("TAG 名称", fontSize = 13.sp) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = tagInput.isNotBlank(),
                            onClick = {
                                val name = tagInput.trim()
                                if (name.isEmpty()) return@Button
                                onAddSharedTag(targetParentId, name)
                                tagInput = ""
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) { Text("添加", fontSize = 13.sp) }
                    }
                    // ── 移除区域 ──
                    if (currentTags.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("已有共享 TAG：", fontSize = 13.sp, color = Color.Gray)
                        Spacer(Modifier.height(4.dp))
                        currentTags.forEach { tag ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tag.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                TextButton(
                                    onClick = {
                                        manageSharedTagParentId = null
                                        onRemoveSharedTag(targetParentId, tag.id)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("移除", fontSize = 12.sp, color = Color(0xFFCC4444))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { manageSharedTagParentId = null }) { Text("关闭") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // 关闭搜索按钮（← 箭头，始终可见）
                                IconButton(
                                    onClick = { onExitSearch() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    painter = painterResource(com.example.rcgallery.R.drawable.ic_arrow_back),
                                    contentDescription = "关闭搜索",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            // 搜索输入框（无边框，与 TopAppBar 背景融合）
                            TextField(
                                value = searchQuery,
                                onValueChange = onSearchQueryChange,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = { _dismissSearchSuggestions.value = true }
                                ),
                                textStyle = MaterialTheme.typography.bodyLarge,
                                placeholder = {
                                    Text(
                                        "搜索相册...",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(
                                            onClick = { onSearchQueryChange("") },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(com.example.rcgallery.R.drawable.ic_close),
                                                contentDescription = "清空",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                },
                                colors = TextFieldDefaults.colors(
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(searchFocusRequester)
                                    .onFocusChanged { if (!it.isFocused) _hideSearchSuggestions.value = true }
                            )
                        }
                    }
                } else {
                    Text(
                        if (isAlbumMultiSelect) "已选 ${selectedAlbumIds.size} 项"
                        else if (isDateMultiSelect) "已选 ${selectedDateMediaUris.size} 项"
                        else "RCGallery"
                    )
                }
                },
                navigationIcon = {
                    if (isAlbumMultiSelect || isDateMultiSelect) {
                        if (isAlbumMultiSelect) {
                            TextButton(onClick = { exitAlbumMultiSelect() }) {
                                Text("取消", color = MaterialTheme.colorScheme.onSurface)
                            }
                        } else {
                            TextButton(onClick = { exitDateMultiSelect() }) {
                                Text("取消", color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    } else {
                        // ── 齿轮菜单按钮（测试入口，永久放在最左边）──
                        var showGearMenu by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.padding(start = 4.dp)) {
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
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),  // 外层 Scaffold 已处理状态栏 insets
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    if (!isAlbumMultiSelect && !isDateMultiSelect) {
                        if (isSearchActive) {
                            // 搜索模式下 actions 为空（搜索 UI 在 title 中）
                        } else {
                            // ── 创建父级相册按钮 ──
                            TopBarActionButton(
                                painter = painterResource(com.example.rcgallery.R.drawable.ic_new_folder),
                                contentDescription = "创建父级相册",
                                onClick = { showCreateParentDialog = true }
                            )
                            Spacer(Modifier.width(12.dp))
                            // ── 搜索按钮 ──
                            TopBarActionButton(
                                painter = painterResource(com.example.rcgallery.R.drawable.ic_search),
                                contentDescription = "搜索",
                                onClick = { onActivateSearch() }
                            )
                            Spacer(Modifier.width(12.dp))
                            // ── 回收站按钮 ──
                            TopBarActionButton(
                                painter = painterResource(com.example.rcgallery.R.drawable.ic_trash),
                                contentDescription = "回收站",
                                onClick = { onOpenTrash() }
                            )
                        }
                    } else if (isDateMultiSelect) {
                        val allUris = allDateMediaItems.map { it.uri.toString() }.toSet()
                        val allSelected = allUris.isNotEmpty() && allUris.all { it in selectedDateMediaUris }
                        TextButton(onClick = {
                            selectedDateMediaUris = if (allSelected) emptySet() else allUris
                            isDateMultiSelect = selectedDateMediaUris.isNotEmpty()
                        }) {
                            Text(if (allSelected) "取消全选" else "全选", fontSize = 13.sp)
                        }
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
            // ── 层级相册：扁平 items 列表（List 模式用）──
            val isListMode = displayMode is AlbumDisplayMode.List
            val parentItems = remember(albums, parentEntities, parentChildrenBucketMap, allChildBucketIds, expandedParentIds, isListMode, albumSortMode, starredIds, searchQuery, matchedParentIds) {
                if (isListMode && parentEntities.isNotEmpty()) {
                    val isSearching = searchQuery.isNotBlank()
                    // 统一排序：父级和普通相册在同一排序器中对等比较
                    // RECENT_ACCESS/RECENTLY_MOVED 不执行星标置顶
                    val shouldStarPin = albumSortMode != AlbumSortMode.RECENT_ACCESS && albumSortMode != AlbumSortMode.RECENTLY_MOVED
                    // 排序键：空父级 > 星标 > 在 albums 中的位置
                    // 封装为 Triple(isEmptyParent, isStarred, position)
                    data class SortKey(val isEmptyParent: Boolean, val isStarred: Boolean, val position: Int)

                    fun sortKeyValue(k: SortKey): Long {
                        val flag = (if (k.isEmptyParent) 1L shl 62 else 0L) or
                                   (if (k.isStarred) 1L shl 61 else 0L)
                        return flag or (Int.MAX_VALUE.toLong() - k.position)
                    }

                    val items = mutableListOf<Pair<Any, SortKey>>()

                    // 父级（搜索模式下只保留命中的父级）
                    for (parent in parentEntities) {
                        if (isSearching && parent.id !in matchedParentIds) continue
                        val childIds = parentChildrenBucketMap[parent.id] ?: emptyList()
                        val isEmpty = childIds.isEmpty()
                        val firstChildPos = if (isEmpty) Int.MAX_VALUE
                            else albums.indexOfFirst { it.bucketId in childIds }.let { if (it < 0) Int.MAX_VALUE else it }
                        items.add(parent to SortKey(isEmpty, shouldStarPin && "parent:${parent.id}" in starredIds, firstChildPos))
                    }

                    // 普通相册（不属于任何父级）
                    for ((idx, album) in albums.withIndex()) {
                        if (album.bucketId !in allChildBucketIds) {
                            items.add(album to SortKey(false, shouldStarPin && album.bucketId in starredIds, idx))
                        }
                    }

                    // 按 sortKeyValue 降序排序（封装为 Long，一次比较）
                    items.sortByDescending { sortKeyValue(it.second) }

                    // ── 渲染 ──
                    val result = mutableListOf<Any>()
                    for ((item, _) in items) {
                        when (item) {
                            is ParentAlbumEntity -> {
                                val childIds = parentChildrenBucketMap[item.id] ?: emptyList()
                                val isEmpty = childIds.isEmpty()
                                // 搜索模式下自动展开父级，仅显示命中的子级
                                val showChildren = if (isSearching) {
                                    !isEmpty && childIds.any { childBucketId ->
                                        albums.any { it.bucketId == childBucketId && it.bucketName.contains(searchQuery, ignoreCase = true) }
                                    }
                                } else {
                                    !isEmpty && item.id in expandedParentIds
                                }
                                result.add(ParentHeader(item.id, item.name, showChildren, childIds.size))
                                if (showChildren) {
                                    val childrenToShow = if (isSearching) {
                                        albums.filter { it.bucketId in childIds && it.bucketName.contains(searchQuery, ignoreCase = true) }
                                    } else {
                                        albums.filter { it.bucketId in childIds }
                                    }
                                    val childrenSortComparator = if (shouldStarPin) {
                                        compareByDescending<Album> { it.bucketId in starredIds }
                                            .then(albumSortComparator(albumSortMode))
                                    } else albumSortComparator(albumSortMode)
                                    val sortedChildren = childrenToShow.sortedWith(childrenSortComparator)
                                    sortedChildren.forEach { result.add(ChildRow(it.bucketId, item.name, item.id)) }
                                }
                            }
                            is Album -> result.add(item)
                        }
                    }

                    result
                } else emptyList<Any>()
            }
            AndroidView(
                factory = { ctx ->
                    // Grid 模式使用 displayAlbums（由 albums 参数传入），List 模式用 albums 作查询后备
                    val adapter = AlbumGridAdapter(
                        items = albums,
                        onClick = { album -> wrappedAlbumClick(album) },
                        onLongClick = onAlbumLongClick,
                        onToggleStar = onToggleStar,
                        onManageTags = onManageAlbumTags,
                        onMigrateAlbum = onMigrateAlbum,
                        onGridLongClick = onGridLongClick,
                        parentBadgeMap = parentBadgeMap,
                        parentItems = parentItems,
                        onParentClick = { parentId ->
                            // 展开/折叠切换
                            expandedParentIds = if (parentId in expandedParentIds) expandedParentIds - parentId else expandedParentIds + parentId
                        },
                        onParentLongClick = { parentId ->
                            renameParentId = parentId
                            showParentRenameDialog = true
                        },
                        onAddChildClick = { parentId ->
                            addToParentId = parentId
                            showAddChildDialog = true
                        },
                        onDeleteParentClick = { parentId ->
                            targetDeleteParentId = parentId
                            showDeleteParentConfirm = true
                        },
                        onRemoveChildClick = { parentId, bucketId ->
                            targetRemoveParentId = parentId
                            targetRemoveChildBucketId = bucketId
                            showRemoveChildConfirm = true
                        },
                        onManageSharedTagClick = { parentId ->
                            manageSharedTagParentId = parentId
                        },
                        parentSharedTagMap = parentSharedTagMap
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
                        // 滑动时关闭搜索框焦点和下拉建议
                        addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                                    _dismissSearchSuggestions.value = true
                                }
                            }
                        })
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
                    val modeChanged = prevMode != displayMode
                    adapter.items = albums
                    adapter.albumMap = albums.associateBy { it.bucketId }
                    adapter.starredIds = starredIds
                    adapter.albumTagsMap = albumTags
                    adapter.systemHiddenAlbumPaths = systemHiddenAlbumPaths
                    adapter.selectedIds = selectedAlbumIds
                    adapter.parentBadgeMap = parentBadgeMap
                    adapter.parentSharedTagMap = parentSharedTagMap
                    adapter.parentItems = parentItems
                    if (modeChanged) {
                        adapter.currentMode = displayMode
                        val spanCount = when (displayMode) {
                            is AlbumDisplayMode.Grid -> displayMode.columns
                            is AlbumDisplayMode.List -> 1
                        }
                        val lm = rv.layoutManager as? GridLayoutManager
                        if (lm == null || lm.spanCount != spanCount) {
                            rv.layoutManager = GridLayoutManager(rv.context, spanCount)
                        }
                    }
                    adapter.notifyDataSetChanged()
                    if (modeChanged) {
                        scroller.refresh()
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
                var dateViewItems by remember { mutableStateOf<List<DateViewItem>>(emptyList()) }
                LaunchedEffect(allDateMediaItems) {
                    val buildStartedAt = System.nanoTime()
                    val builtItems = withContext(Dispatchers.Default) {
                        buildDateViewItems(allDateMediaItems)
                    }
                    AppLogger.d(
                        "DatePerf",
                        "build date items media=${allDateMediaItems.size} " +
                            "rows=${builtItems.size} ms=${(System.nanoTime() - buildStartedAt) / 1_000_000}"
                    )
                    dateViewItems = builtItems
                }
                DateGroupRecyclerView(
                    items = dateViewItems,
                    columns = columns,
                    onClick = { mediaItem, allMediaIdx ->
                        if (isDateMultiSelect) {
                            toggleDateMediaSelection(mediaItem.uri.toString())
                        } else {
                            onDatePhotoClick(allMediaIdx)
                        }
                    },
                    onLongClick = { mediaItem ->
                        if (!isDateMultiSelect) isDateMultiSelect = true
                        toggleDateMediaSelection(mediaItem.uri.toString())
                    },
                    selectedUris = selectedDateMediaUris,
                    onDragSelectUris = { uris ->
                        if (uris.isNotEmpty()) isDateMultiSelect = true
                        selectedDateMediaUris = uris
                    }
                )
                if (isDateInitialLoading && dateViewItems.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
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

            if (isDateMultiSelect && selectedDateMediaUris.isNotEmpty()) {
                FloatingMultiSelectButtons(
                    selectedCount = selectedDateMediaUris.size,
                    onBatchTag = { showDateBatchTagDialog = true },
                    onDeleteToTrash = {
                        onDeleteDateMedia(selectedDateMediaItems.map { it.filePath })
                        exitDateMultiSelect()
                    },
                    onAddToClipboard = {
                        onAddDateMediaToClipboard(selectedDateMediaItems)
                        exitDateMultiSelect()
                    },
                    onPickTargetAlbum = {
                        onPickDateMediaTarget(selectedDateMediaItems)
                        exitDateMultiSelect()
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 80.dp)
                )
            }
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
                title = "批量加标签 - 已选 ${selectedDateMediaUris.size} 个媒体文件",
                allTags = allTags,
                onAddTag = { tagName ->
                    selectedDateMediaItems.forEach { item ->
                        onBatchAddTagsToMedia(item.filePath, tagName)
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
//  统一 TopBar Action 按钮组件
// ══════════════════════════════════════

/**
 * TopAppBar actions 统一按钮：36dp 圆形容器 + 22dp 图标 + onSurfaceVariant 色调
 */
@Composable
private fun TopBarActionButton(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
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
    private val onMigrateAlbum: (Album) -> Unit = {},
    private val onGridLongClick: ((Album) -> Unit)? = null,  // Grid 模式长按（多选），不传则回退到 onLongClick
    // ── 层级相册相关 ──
    var parentBadgeMap: Map<String, String> = emptyMap(),  // bucketId -> parentName
    var parentItems: List<Any> = emptyList(),  // List 模式的扁平 items（Album 或 ParentHeader 或 ChildRow）
    var onParentClick: ((Long) -> Unit)? = null,
    var onParentLongClick: ((Long) -> Unit)? = null,
    var onAddChildClick: ((Long) -> Unit)? = null,
    var onDeleteParentClick: ((Long) -> Unit)? = null,
    var onRemoveChildClick: ((Long, String) -> Unit)? = null,
    var onManageSharedTagClick: ((Long) -> Unit)? = null,
    var parentSharedTagMap: Map<Long, List<TagEntity>> = emptyMap()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var currentMode: AlbumDisplayMode = AlbumDisplayMode.Grid(3)
    var starredIds: Set<String> = emptySet()
    var albumTagsMap: Map<String, List<TagEntity>> = emptyMap()
    var systemHiddenAlbumPaths: Set<String> = emptySet()
    var selectedIds: Set<String> = emptySet()  // 多选模式选中项 bucketId 集合
    var albumMap: Map<String, Album> = emptyMap()  // bucketId → Album 快速查找

    init { setHasStableIds(true) }

    override fun getItemCount() = if (currentMode is AlbumDisplayMode.List && parentItems.isNotEmpty()) parentItems.size else items.size

    override fun getItemId(position: Int): Long {
        return if (currentMode is AlbumDisplayMode.List && parentItems.isNotEmpty()) {
            val item = parentItems[position]
            when (item) {
                is Album -> item.bucketId.hashCode().toLong()
                is ParentHeader -> "parent_header:${item.parentId}".hashCode().toLong()
                is ChildRow -> "child:${item.bucketId}".hashCode().toLong()
                else -> 0L
            }
        } else {
            items.getOrNull(position)?.bucketId?.hashCode()?.toLong() ?: 0L
        }
    }

    override fun getItemViewType(position: Int): Int {
        if (currentMode is AlbumDisplayMode.List && parentItems.isNotEmpty()) {
            return when (parentItems[position]) {
                is ParentHeader -> VIEW_TYPE_LIST_PARENT_HEADER
                is ChildRow -> VIEW_TYPE_LIST_CHILD
                is Album -> VIEW_TYPE_LIST
                else -> VIEW_TYPE_LIST
            }
        }
        return if (currentMode is AlbumDisplayMode.List) VIEW_TYPE_LIST else VIEW_TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_LIST -> ListVH.create(parent, onClick, onLongClick, onToggleStar, onManageTags, onMigrateAlbum, albumTagsMap)
            VIEW_TYPE_LIST_PARENT_HEADER -> ParentHeaderVH.create(parent, onParentClick, onParentLongClick, onAddChildClick, onToggleStar, onDeleteParentClick, onManageSharedTagClick)
            VIEW_TYPE_LIST_CHILD -> ChildRowVH.create(parent, onClick, onRemoveChildClick)
            else -> GridVH.create(parent, onClick, onGridLongClick ?: onLongClick, onToggleStar)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val columns = when (val mode = currentMode) {
            is AlbumDisplayMode.Grid -> mode.columns
            is AlbumDisplayMode.List -> 1
        }
        if (currentMode is AlbumDisplayMode.List && parentItems.isNotEmpty()) {
            val item = parentItems[position]
            when {
                holder is ParentHeaderVH && item is ParentHeader -> {
                    holder.bind(item, position, starredIds, parentSharedTagMap) { onManageSharedTagClick?.invoke(item.parentId) }
                }
                holder is ChildRowVH && item is ChildRow -> {
                    val album = albumMap[item.bucketId]
                    if (album != null) holder.bind(album, position, item.parentName)
                }
                holder is ListVH && item is Album -> {
                    holder.bind(item, position, starredIds, albumTagsMap, isSystemHidden(item.directoryPath))
                }
            }
        } else if (holder is GridVH) {
            val item = items[position]
            holder.bind(item, position, starredIds, selectedIds, columns, parentBadgeMap)
        } else if (holder is ListVH) {
            val item = items[position]
            holder.bind(item, position, starredIds, albumTagsMap, isSystemHidden(item.directoryPath))
        }
    }

    private fun isSystemHidden(directoryPath: String): Boolean =
        systemHiddenAlbumPaths.any { it.equals(directoryPath, ignoreCase = true) }

}

private const val VIEW_TYPE_GRID = 0
private const val VIEW_TYPE_LIST = 1
private const val VIEW_TYPE_LIST_PARENT_HEADER = 2
private const val VIEW_TYPE_LIST_CHILD = 3

/** List 模式扁平 item 类型：父级标头 */
private data class ParentHeader(
    val parentId: Long,
    val parentName: String,
    val isExpanded: Boolean,
    val childCount: Int
)

/** List 模式扁平 item 类型：子相册行 */
private data class ChildRow(
    val bucketId: String,
    val parentName: String,
    val parentId: Long
)

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

            // ── 层级相册父级标签：左下角半透明黑底白字 ──
            val parentBadgeTv = TextView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM or android.view.Gravity.START
                ).apply { setMargins((4 * density).toInt(), 0, 0, (4 * density).toInt()) }
                setTextSize(10f)
                setTextColor(android.graphics.Color.WHITE)
                setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
                    setCornerRadius((4 * density))
                    setColor(android.graphics.Color.argb(160, 0, 0, 0))
                }
                tag = "parent_badge"
                visibility = android.view.View.GONE
            }
            coverFrame.addView(parentBadgeTv)

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
                val rv = root.parent as? RecyclerView ?: return@setOnClickListener
                val adapter = rv.adapter as? AlbumGridAdapter ?: return@setOnClickListener
                val bucketId = root.tag as? String ?: return@setOnClickListener
                adapter.albumMap[bucketId]?.let { onClick(it) }
            }
            root.setOnLongClickListener {
                val rv = root.parent as? RecyclerView ?: return@setOnLongClickListener true
                val adapter = rv.adapter as? AlbumGridAdapter ?: return@setOnLongClickListener true
                val bucketId = root.tag as? String ?: return@setOnLongClickListener true
                adapter.albumMap[bucketId]?.let { onLongClick(it) }
                true
            }
            return GridVH(root, starContainer, starWrapper, starIv)
        }
    }

    fun bind(item: Album, pos: Int, starredIds: Set<String>, selectedIds: Set<String> = emptySet(), columns: Int = 3, parentBadgeMap: Map<String, String> = emptyMap()) {
        itemView.tag = item.bucketId
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
        val hideStar = columns == 4 || columns == 5
        val inMultiSelect = selectedIds.isNotEmpty()
        val isSelected = item.bucketId in selectedIds
        starContainer.visibility = if (inMultiSelect || hideStar) android.view.View.GONE else android.view.View.VISIBLE
        starContainer.isClickable = !hideStar
        starContainer.isEnabled = !hideStar
        val checkmark = itemView.findViewById<FrameLayout>(android.R.id.checkbox)
        if (checkmark != null) {
            checkmark.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.GONE
        }
        // 层级相册：左下角父级标签
        val badge = itemView.findViewWithTag<android.widget.TextView>("parent_badge")
        val parentName = parentBadgeMap[item.bucketId]
        if (badge != null && parentName != null) {
            badge.text = parentName
            badge.visibility = android.view.View.VISIBLE
        } else if (badge != null) {
            badge.visibility = android.view.View.GONE
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
                   onManageTags: (Album) -> Unit = {}, onMigrateAlbum: (Album) -> Unit = {},
                   albumTagsMap: Map<String, List<TagEntity>> = emptyMap()): ListVH {
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

            // ── 普通相册菜单：与父级相册同规格（48dp 容器 + 24dp 图标）──
            val menuBtn = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (48 * density).toInt(),
                    (48 * density).toInt()
                ).apply {
                    setMargins(0, 0, (3 * density).toInt(), 0)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                isClickable = true
                focusable = View.FOCUSABLE
            }
            val menuIv = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (24 * density).toInt(),
                    (24 * density).toInt(),
                    android.view.Gravity.CENTER
                )
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(
                    createVerticalDotsBitmap(
                        (24 * density).toInt(),
                        (24 * density).toInt(),
                        android.graphics.Color.GRAY
                    )
                )
            }
            menuBtn.addView(menuIv)
            row.addView(menuBtn)

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

            menuBtn.setOnClickListener {
                val rv = root.parent as? RecyclerView ?: return@setOnClickListener
                val adapter = rv.adapter as? AlbumGridAdapter ?: return@setOnClickListener
                val bucketId = root.tag as? String ?: return@setOnClickListener
                val album = adapter.albumMap[bucketId] ?: return@setOnClickListener
                android.widget.PopupMenu(ctx, menuBtn).apply {
                    menu.add(0, 1, 0, "重命名")
                    menu.add(0, 2, 1, "迁移存储路径")
                    setOnMenuItemClickListener { menuItem ->
                        when (menuItem.itemId) {
                            1 -> { onLongClick(album); true }
                            2 -> { onMigrateAlbum(album); true }
                            else -> false
                        }
                    }
                    show()
                }
            }

            root.setOnClickListener {
                val rv = root.parent as? RecyclerView ?: return@setOnClickListener
                val adapter = rv.adapter as? AlbumGridAdapter ?: return@setOnClickListener
                val bucketId = root.tag as? String ?: return@setOnClickListener
                adapter.albumMap[bucketId]?.let { onClick(it) }
            }
            root.setOnLongClickListener {
                // 预留普通相册长按入口；当前不绑定功能。
                true
            }
            return ListVH(root, starContainer, starIv, onManageTags)
        }
    }

    fun bind(
        item: Album,
        pos: Int,
        starredIds: Set<String>,
        albumTagsMap: Map<String, List<TagEntity>> = emptyMap(),
        systemGalleryHidden: Boolean = false
    ) {
        itemView.tag = item.bucketId
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
        pathTv.text = FormatUtil.formatDisplayPath(item.directoryPath)
        pathTv.visibility = if (item.directoryPath.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        val isStarred = item.bucketId in starredIds
        starIv.colorFilter = android.graphics.PorterDuffColorFilter(
            if (isStarred) android.graphics.Color.rgb(255, 193, 7) else android.graphics.Color.rgb(160, 160, 160),
            android.graphics.PorterDuff.Mode.SRC_IN
        )
        // ── TAG 行 ──
        val tags = albumTagsMap[item.directoryPath] ?: emptyList()
        val labels = buildList {
            if (systemGalleryHidden) add("HID")
            addAll(tags.map { it.name })
        }
        // + 按钮点击
        (tagRow.getChildAt(0) as? TextView)?.setOnClickListener { onManageTags(item) }

        // 预建 chip：只改 text 和 visibility，不新建 View
        labels.forEachIndexed { i, label ->
            val chip = tagChips.getOrNull(i) ?: return@forEachIndexed
            chip.text = label
            chip.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 6 * itemView.context.resources.displayMetrics.density
                setColor(
                    if (systemGalleryHidden && i == 0) android.graphics.Color.rgb(97, 97, 97)
                    else android.graphics.Color.argb(180, 100, 140, 255)
                )
            }
            chip.visibility = android.view.View.VISIBLE
        }
        // 隐藏多余的 chip
        for (i in labels.size until tagChips.size) {
            tagChips[i].visibility = android.view.View.GONE
        }
        tagRow.visibility = android.view.View.VISIBLE
    }
}

// ── 层级相册：List 模式父级标头 ViewHolder ──
// 与 ListVH 同骨架：64dp 封面区 + [badge+标题] + 副标题 + 星标 + 分隔线

private class ParentHeaderVH(
    itemView: android.view.View,
    private val starContainer: FrameLayout,
    private val starIv: ImageView,
    private val tagRow: LinearLayout,
    private val tagChips: Array<TextView>
) : RecyclerView.ViewHolder(itemView) {

    companion object {
        fun create(
            parent: ViewGroup,
            onParentClick: ((Long) -> Unit)?,
            onParentLongClick: ((Long) -> Unit)?,
            onAddChildClick: ((Long) -> Unit)?,
            onToggleStar: (String) -> Unit,
            onDeleteParentClick: ((Long) -> Unit)? = null,
            onManageSharedTagClick: ((Long) -> Unit)? = null
        ): ParentHeaderVH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            // ── 内容行（与 ListVH 同骨架：封面 + 文字 + 星标）──
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt()) }
            }
            // 封面区：64dp 圆角容器（与 ListVH 缩略图同尺寸），保持文件夹图标
            val thumbSize = (64 * density).toInt()
            val coverFrame = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, (ITEM_CORNER_RADIUS_DP * density))
                    }
                }
                clipToOutline = true
                setBackgroundColor(android.graphics.Color.argb(25, 255, 255, 255))
            }
            val folderIcon = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (32 * density).toInt(),
                    (32 * density).toInt(),
                    android.view.Gravity.CENTER
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(com.example.rcgallery.R.drawable.ic_collection)
            }
            coverFrame.addView(folderIcon)
            row.addView(coverFrame)

            // 文字列
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

            // ── 标题行：[badge "父级"] + [nameTv] ──
            val titleRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val badgeTv = TextView(ctx).apply {
                text = "父级"
                textSize = 10f
                setTextColor(android.graphics.Color.argb(180, 255, 255, 255))
                setBackgroundDrawable(
                    android.graphics.drawable.GradientDrawable().apply {
                        setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
                        setCornerRadius((4 * density))
                        setColor(android.graphics.Color.argb(60, 255, 255, 255))
                    }
                )
                setPadding((4 * density).toInt(), (1 * density).toInt(), (4 * density).toInt(), (1 * density).toInt())
                tag = "identity_badge"
            }
            titleRow.addView(badgeTv)
            titleRow.addView(android.view.View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((6 * density).toInt(), 0)
            })
            val nameTv = TextView(ctx).apply {
                id = android.R.id.title
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { weight = 1f }
                setTextSize(14f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            titleRow.addView(nameTv)
            textColumn.addView(titleRow)

            val countTv = TextView(ctx).apply {
                id = android.R.id.summary
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextSize(12f)
                setTextColor(android.graphics.Color.GRAY)
                maxLines = 1
            }
            textColumn.addView(countTv)

            // ── 共享 TAG 行（与 ListVH 同款：绿色圆圈 + 按钮）──
            val tagRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, (3 * density).toInt(), 0, 0) }
                visibility = android.view.View.GONE
            }
            // + 按钮（与 ListVH 完全一致：绿色圆形 20dp）
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
                tag = "parent_tag_add"
            }
            tagRow.addView(tagAddChip)
            // 预建共享 TAG chip（与 ListVH 同风格：圆角矩形白色文字）
            val chipCount = 8
            val tagChips = Array(chipCount) {
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
                    gravity = android.view.Gravity.CENTER
                    setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        (20 * density).toInt()
                    ).apply { setMargins(0, 0, (4 * density).toInt(), 0) }
                    visibility = android.view.View.GONE
                }
            }
            tagChips.forEach { tagRow.addView(it) }
            textColumn.addView(tagRow)

            row.addView(textColumn)

            // ── ⋮ 菜单按钮（48dp 容器 + 24dp 图标，与星标同规格）──
            val menuBtn = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (48 * density).toInt(),
                    (48 * density).toInt()
                ).apply { setMargins(0, 0, (3 * density).toInt(), 0); gravity = android.view.Gravity.CENTER_VERTICAL }
                isClickable = true
                focusable = View.FOCUSABLE
            }
            val menuIv = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (24 * density).toInt(),
                    (24 * density).toInt(),
                    android.view.Gravity.CENTER
                )
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(createVerticalDotsBitmap((24 * density).toInt(), (24 * density).toInt(), android.graphics.Color.GRAY))
            }
            menuBtn.addView(menuIv)
            row.addView(menuBtn)

            // ── 星标（48dp 容器 + 24dp 图标，与 ListVH 一致）──
            val starContainer = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (48 * density).toInt(),
                    (48 * density).toInt()
                ).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
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

            // ── 分隔线（与 ListVH 同款）──
            val divider = android.view.View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (1 * density).toInt()
                ).apply { setMargins((12 * density).toInt(), 0, (12 * density).toInt(), 0) }
                setBackgroundColor(android.graphics.Color.argb(25, 255, 255, 255))
            }
            root.addView(divider)

            // ── 星标点击 ──
            starContainer.setOnClickListener {
                val bucketId = starContainer.tag as? String ?: return@setOnClickListener
                onToggleStar(bucketId)
            }

            // ⋮ 菜单点击
            menuBtn.setOnClickListener {
                val parentId = root.tag as? Long ?: return@setOnClickListener
                val popup = android.widget.PopupMenu(ctx, menuBtn)
                popup.menu.add(0, 1, 0, "重命名")
                popup.menu.add(0, 2, 0, "添加子相册")
                popup.menu.add(0, 3, 0, "解散父级")
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> { onParentLongClick?.invoke(parentId); true }
                        2 -> { onAddChildClick?.invoke(parentId); true }
                        3 -> { onDeleteParentClick?.invoke(parentId); true }
                        else -> false
                    }
                }
                popup.show()
            }

            // ── 点击展开/折叠 ──
            root.setOnClickListener {
                val parentId = root.tag as? Long ?: return@setOnClickListener
                onParentClick?.invoke(parentId)
            }

            return ParentHeaderVH(root, starContainer, starIv, tagRow, tagChips)
        }
    }

    fun bind(item: ParentHeader, pos: Int, starredIds: Set<String>, parentSharedTagMap: Map<Long, List<TagEntity>> = emptyMap(), onManageSharedTag: ((Long) -> Unit)? = null) {
        itemView.tag = item.parentId
        starContainer.tag = "parent:${item.parentId}"
        val title = itemView.findViewById<android.widget.TextView>(android.R.id.title)
        title?.text = item.parentName
        val summary = itemView.findViewById<android.widget.TextView>(android.R.id.summary)
        summary?.text = if (item.childCount > 0) "${item.childCount} 个子相册" else "暂无子相册"
        val isStarred = "parent:${item.parentId}" in starredIds
        starIv.colorFilter = android.graphics.PorterDuffColorFilter(
            if (isStarred) android.graphics.Color.rgb(255, 193, 7) else android.graphics.Color.rgb(160, 160, 160),
            android.graphics.PorterDuff.Mode.SRC_IN
        )

        // ── 共享 TAG 行 ──
        tagRow.visibility = android.view.View.VISIBLE
        // + 按钮点击：管理共享 TAG
        (tagRow.getChildAt(0) as? TextView)?.setOnClickListener { onManageSharedTag?.invoke(item.parentId) }
        // 填充已有 TAG chip
        val tags = parentSharedTagMap[item.parentId] ?: emptyList()
        tags.forEachIndexed { i, tag ->
            val chip = tagChips.getOrNull(i) ?: return@forEachIndexed
            chip.text = tag.name
            chip.visibility = android.view.View.VISIBLE
        }
        // 隐藏多余的
        for (i in tags.size until tagChips.size) {
            tagChips[i].visibility = android.view.View.GONE
        }
    }
}

// ── 层级相册：List 模式子相册行 ViewHolder ──
// 与 ListVH 同骨架：64dp 缩略图 + [badge+标题] + album 数据副标题 + ✕ 按钮 + 分隔线
// 层级关系靠缩进 + badge "父:xxx" 表示

private class ChildRowVH(
    itemView: android.view.View
) : RecyclerView.ViewHolder(itemView) {

    companion object {
        fun create(parent: ViewGroup, onClick: (Album) -> Unit, onRemoveChildClick: ((Long, String) -> Unit)? = null): ChildRowVH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            // ── 内容行（缩进 + 封面 + 文字 + ✕）──
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins((32 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt()) }
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

            // ── 标题行：[badge "父:xxx"] + [nameTv] ──
            val titleRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val badgeTv = TextView(ctx).apply {
                textSize = 10f
                setTextColor(android.graphics.Color.argb(180, 255, 255, 255))
                setBackgroundDrawable(
                    android.graphics.drawable.GradientDrawable().apply {
                        setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
                        setCornerRadius((4 * density))
                        setColor(android.graphics.Color.argb(40, 180, 200, 255))
                    }
                )
                setPadding((4 * density).toInt(), (1 * density).toInt(), (4 * density).toInt(), (1 * density).toInt())
                tag = "parent_badge"
            }
            titleRow.addView(badgeTv)
            titleRow.addView(android.view.View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((6 * density).toInt(), 0)
            })
            val nameTv = TextView(ctx).apply {
                id = android.R.id.title
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { weight = 1f }
                setTextSize(14f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            titleRow.addView(nameTv)
            textColumn.addView(titleRow)

            val infoTv = TextView(ctx).apply {
                id = android.R.id.summary
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

            // ── ✕ 移出按钮（48dp 容器，与 ListVH 星标同规格）──
            val removeBtn = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (48 * density).toInt(),
                    (48 * density).toInt()
                ).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
                isClickable = true
                focusable = View.FOCUSABLE
            }
            val removeIv = TextView(ctx).apply {
                text = "✕"
                textSize = 18f
                setTextColor(android.graphics.Color.argb(180, 255, 100, 100))
                gravity = android.view.Gravity.CENTER
                includeFontPadding = false
                layoutParams = FrameLayout.LayoutParams(
                    (24 * density).toInt(),
                    (24 * density).toInt(),
                    android.view.Gravity.CENTER
                )
            }
            removeBtn.addView(removeIv)
            row.addView(removeBtn)

            root.addView(row)

            // ── 分隔线（左对齐缩进起始）──
            val divider = android.view.View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (1 * density).toInt()
                ).apply { setMargins((32 * density).toInt(), 0, (12 * density).toInt(), 0) }
                setBackgroundColor(android.graphics.Color.argb(25, 255, 255, 255))
            }
            root.addView(divider)

            // ── 点击进入相册 ──
            root.setOnClickListener {
                val rv = root.parent as? RecyclerView ?: return@setOnClickListener
                val adapter = rv.adapter as? AlbumGridAdapter ?: return@setOnClickListener
                val bucketId = root.tag as? String ?: return@setOnClickListener
                adapter.albumMap[bucketId]?.let { onClick(it) }
            }

            // ✕ 按钮点击
            removeBtn.setOnClickListener {
                val rv = root.parent as? RecyclerView ?: return@setOnClickListener
                val adapter = rv.adapter as? AlbumGridAdapter ?: return@setOnClickListener
                val bucketId = root.tag as? String ?: return@setOnClickListener
                val childRow = adapter.parentItems.filterIsInstance<ChildRow>().find { it.bucketId == bucketId } ?: return@setOnClickListener
                onRemoveChildClick?.invoke(childRow.parentId, childRow.bucketId)
            }

            return ChildRowVH(root)
        }
    }

    fun bind(item: Album, pos: Int, parentName: String? = null) {
        itemView.tag = item.bucketId
        val iv = itemView.findViewById<ImageView>(android.R.id.icon)
        iv?.load(item.coverUri) { size(160); crossfade(false) }
        val title = itemView.findViewById<android.widget.TextView>(android.R.id.title)
        title?.text = item.bucketName
        // 副标题：相册自己的数据
        val info = itemView.findViewById<android.widget.TextView>(android.R.id.summary)
        info?.text = buildString {
            append(com.example.rcgallery.util.FormatUtil.formatFileSize(item.totalSize))
            append(" · ${item.imageCount} 图片")
            if (item.videoCount > 0) append(" · ${item.videoCount} 视频")
            if (item.gifCount > 0) append(" · ${item.gifCount} GIF")
        }
        info?.visibility = android.view.View.VISIBLE
        // badge：归属关系
        val badge = itemView.findViewWithTag<android.widget.TextView>("parent_badge")
        if (badge != null && parentName != null) {
            badge.text = "父:$parentName"
            badge.visibility = android.view.View.VISIBLE
        } else if (badge != null) {
            badge.visibility = android.view.View.GONE
        }
    }
}

// ── 显示模式选择器

/**
 * 搜索建议下拉菜单，显示匹配的相册名称供用户点击填充输入框。
 */
@Composable
private fun SearchSuggestionsDropdown(
    suggestions: List<Album>,
    searchQuery: String,
    onSuggestionClick: (Album) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
        shadowElevation = 4.dp,
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            suggestions.forEach { album ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSuggestionClick(album) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(com.example.rcgallery.R.drawable.ic_folder),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = album.bucketName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = "${album.count} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

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
    val result = ArrayList<DateViewItem>(allItems.size + 512)
    var previousDateKey = Int.MIN_VALUE

    allItems.forEachIndexed { index, item ->
        cal.timeInMillis = item.dateAdded * 1000L
        val y = cal.get(java.util.Calendar.YEAR)
        val m = cal.get(java.util.Calendar.MONTH)
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val dateKey = y * 10000 + (m + 1) * 100 + d
        if (dateKey != previousDateKey) {
            result.add(DateViewItem.Header(formatDateLabel(dateKey)))
            previousDateKey = dateKey
        }
        result.add(DateViewItem.Media(item, allMediaIndex = index))
    }
    return result
}

/** 将 "YYYY-M-D" 转为自然语言日期标签 */
private fun formatDateLabel(dateKey: Int): String {
    val y = dateKey / 10000
    val m = (dateKey / 100) % 100
    val d = dateKey % 100
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
    selectedUris: Set<String> = emptySet(),
    onDragSelectUris: (Set<String>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            val density = ctx.resources.displayMetrics.density
            val topPad = (44 * density).toInt()
            val adapter = DateGroupAdapter(emptyList(), columns, onClick).apply {
                this.onLongClick = onLongClick
                this.onDragSelectUris = onDragSelectUris
                this.selectedUris = selectedUris
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
                var dragStartUri = ""
                var dragStartVersion = -1
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
                            dragState.dragStartUri = ""
                            downX = e.x; downY = e.y
                            handler.removeCallbacksAndMessages(null)
                            handler.postDelayed({
                                val child = rv.findChildViewUnder(downX, downY) ?: return@postDelayed
                                val pos = rv.getChildAdapterPosition(child)
                                if (pos < 0) return@postDelayed
                                val item = adapter.items.getOrNull(pos) as? DateViewItem.Media ?: return@postDelayed
                                dragState.dragStartIdx = pos
                                dragState.dragStartUri = item.item.uri.toString()
                                dragState.dragStartVersion = adapter.listVersion
                                dragState.isDragging = true
                                adapter.onLongClick(item.item)
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
                            val wasDragging = dragState.isDragging
                            handler.removeCallbacksAndMessages(null)
                            dragState.isDragging = false
                            dragState.dragStartIdx = -1
                            dragState.dragStartUri = ""
                            // Consume the release after a recognized long press so the item
                            // click listener cannot immediately toggle the selection back off.
                            return wasDragging
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
                            val startPos = if (dragState.dragStartVersion == adapter.listVersion) {
                                dragState.dragStartIdx
                            } else {
                                adapter.positionOfUri(dragState.dragStartUri)
                            }
                            if (startPos < 0) {
                                dragState.isDragging = false
                                dragState.dragStartIdx = -1
                                dragState.dragStartUri = ""
                                return
                            }
                            val minPos = minOf(startPos, pos)
                            val maxPos = maxOf(startPos, pos)
                            val rangeUris = (minPos..maxPos).mapNotNull { index ->
                                (adapter.items.getOrNull(index) as? DateViewItem.Media)
                                    ?.item?.uri?.toString()
                            }.toSet()
                            adapter.onDragSelectUris(rangeUris)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            dragState.isDragging = false
                            dragState.dragStartIdx = -1
                            dragState.dragStartUri = ""
                        }
                    }
                }
            })

            rv
        },
        update = { rv ->
            val adapter = rv.adapter as DateGroupAdapter
            val oldColumns = adapter.columns
            adapter.columns = columns
            adapter.onClick = onClick
            adapter.onLongClick = onLongClick
            adapter.onDragSelectUris = onDragSelectUris
            adapter.updateSelection(selectedUris)

            val columnsChanged = oldColumns != columns
            if (columnsChanged) {
                // 列数变化：全量重建 VH，确保 onCreateViewHolder 使用正确尺寸和类型。
                adapter.notifyDataSetChanged()
            }
            adapter.submitItems(items)

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
private object DateThumbnailLoader {
    private const val CACHE_SIZE_KB = 24 * 1024
    private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(CACHE_SIZE_KB) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = java.util.WeakHashMap<ImageView, Job>()

    fun load(imageView: ImageView, uri: Uri, targetSizePx: Int) {
        cancel(imageView)
        val requestSize = targetSizePx.coerceAtLeast(1)
        val key = "$uri@$requestSize"
        imageView.tag = key
        imageView.setImageDrawable(null)

        cache.get(key)?.takeUnless(android.graphics.Bitmap::isRecycled)?.let {
            imageView.setImageBitmap(it)
            return
        }

        jobs[imageView] = scope.launch {
            val bitmap = loadSystemThumbnail(imageView, uri, requestSize)
            if (imageView.tag != key) return@launch
            if (bitmap != null) {
                cache.put(key, bitmap)
                imageView.setImageBitmap(bitmap)
            } else {
                imageView.load(uri) {
                    size(requestSize)
                    precision(Precision.INEXACT)
                    bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                    crossfade(false)
                }
            }
        }
    }

    fun cancel(imageView: ImageView) {
        jobs.remove(imageView)?.cancel()
        imageView.tag = null
    }

    private suspend fun loadSystemThumbnail(
        imageView: ImageView,
        uri: Uri,
        size: Int
    ): android.graphics.Bitmap? = suspendCancellableCoroutine { continuation ->
        val signal = android.os.CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        executor.execute {
            val bitmap = runCatching {
                imageView.context.contentResolver.loadThumbnail(
                    uri,
                    android.util.Size(size, size),
                    signal
                )
            }.getOrNull()
            if (continuation.isActive) continuation.resume(bitmap)
        }
    }
}

private class DateGroupAdapter(
    initialItems: List<DateViewItem>,
    var columns: Int,
    var onClick: (MediaItem, allMediaIndex: Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_MEDIA = 1
        const val VIEW_TYPE_LIST_MEDIA = 2
        private const val PAYLOAD_SELECTION = "date_selection"
    }

    var onLongClick: (MediaItem) -> Unit = {}
    var onDragSelectUris: (Set<String>) -> Unit = {}
    var selectedUris: Set<String> = emptySet()
    var listVersion: Int = 0
        private set
    private var submittedItems: List<DateViewItem> = initialItems
    private var mediaPositionByUri: Map<String, Int> = emptyMap()
    private val differ = AsyncListDiffer(this, DateViewItemDiffCallback())
    val items: List<DateViewItem> get() = differ.currentList

    init {
        setHasStableIds(true)
        differ.submitList(initialItems)
    }

    fun submitItems(newItems: List<DateViewItem>) {
        if (submittedItems === newItems) return
        submittedItems = newItems
        differ.submitList(newItems) {
            listVersion++
            mediaPositionByUri = items.mapIndexedNotNull { index, item ->
                (item as? DateViewItem.Media)?.let { it.item.uri.toString() to index }
            }.toMap()
        }
    }

    fun positionOfUri(uri: String): Int = mediaPositionByUri[uri] ?: -1

    fun updateSelection(newSelection: Set<String>) {
        if (selectedUris == newSelection) return
        val changedUris = (selectedUris - newSelection) + (newSelection - selectedUris)
        selectedUris = newSelection
        val changedPositions = changedUris.mapNotNull(mediaPositionByUri::get).distinct()
        if (changedPositions.size > 64) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        } else {
            changedPositions.forEach { position -> notifyItemChanged(position, PAYLOAD_SELECTION) }
        }
    }

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
                val isSelected = item.item.uri.toString() in selectedUris
                if (holder is ListMediaViewHolder) {
                    holder.itemView.tag = position
                    DateThumbnailLoader.load(
                        holder.imageView,
                        item.item.uri,
                        holder.imageView.layoutParams.width
                    )
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
                    DateThumbnailLoader.load(iv, item.item.uri, side)
                    val checkmark = frame.findViewById<FrameLayout>(android.R.id.checkbox)
                    if (checkmark != null) {
                        checkmark.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.GONE
                    }
                }
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (PAYLOAD_SELECTION in payloads) {
            val media = items.getOrNull(position) as? DateViewItem.Media ?: return
            val isSelected = media.item.uri.toString() in selectedUris
            val checkmark = holder.itemView.findViewById<FrameLayout>(android.R.id.checkbox)
            checkmark?.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.GONE
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        val imageView = when (holder) {
            is ListMediaViewHolder -> holder.imageView
            else -> (holder.itemView as? FrameLayout)?.getChildAt(0) as? ImageView
        }
        imageView?.let {
            DateThumbnailLoader.cancel(it)
            it.setImageDrawable(null)
        }
        super.onViewRecycled(holder)
    }

    private class ListMediaViewHolder(
        itemView: android.view.View,
        val imageView: ImageView,
        val nameView: TextView,
        val infoView: TextView
    ) : RecyclerView.ViewHolder(itemView) {}
}

private class DateViewItemDiffCallback : DiffUtil.ItemCallback<DateViewItem>() {
    override fun areItemsTheSame(old: DateViewItem, new: DateViewItem): Boolean {
        return when {
            old is DateViewItem.Header && new is DateViewItem.Header -> old.label == new.label
            old is DateViewItem.Media && new is DateViewItem.Media -> old.item.uri == new.item.uri
            else -> false
        }
    }

    override fun areContentsTheSame(old: DateViewItem, new: DateViewItem): Boolean = old == new
}


/** 生成竖直三点图标 Bitmap（与星标图标同规格，保证对齐） */
private fun createVerticalDotsBitmap(width: Int, height: Int, color: Int): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val dotRadius = width * 0.08f
    val spacing = height / 4f
    val centerX = width / 2f
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    for (i in 1..3) {
        canvas.drawCircle(centerX, spacing * i, dotRadius, paint)
    }
    return bmp
}

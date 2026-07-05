package com.example.rcgallery.viewmodel

import android.app.Application
import android.media.MediaScannerConnection
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rcgallery.data.MediaRepository
import com.example.rcgallery.data.TrashManager
import com.example.rcgallery.data.smb.SmbBrowseState
import com.example.rcgallery.data.smb.SmbDevice
import com.example.rcgallery.data.smb.SmbRepository
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.util.MediaStoreObserver
import com.example.rcgallery.model.Album
import com.example.rcgallery.model.MediaItem
import com.example.rcgallery.model.TrashEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.File

@OptIn(kotlinx.coroutines.FlowPreview::class)
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val observer = MediaStoreObserver(application)
    private val trashManager = TrashManager(application)

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _mediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaItems: StateFlow<List<MediaItem>> = _mediaItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ── Tab 切换（本地 / 网络）──
    private val _currentTab = MutableStateFlow(0)  // 0=本地, 1=网络
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

    fun switchTab(tab: Int) {
        _currentTab.value = tab
    }

    // ── SMB 网络浏览状态 ──
    private val _smbBrowseState = MutableStateFlow<SmbBrowseState>(SmbBrowseState.DeviceList)
    val smbBrowseState: StateFlow<SmbBrowseState> = _smbBrowseState.asStateFlow()

    private val _smbDevices = MutableStateFlow<List<SmbDevice>>(emptyList())
    val smbDevices: StateFlow<List<SmbDevice>> = _smbDevices.asStateFlow()

    private val smbRepository = SmbRepository.getInstance()

    // SMB 浏览历史栈（支持上一级回退不走重新连接）
    private val _smbBackStack = mutableListOf<SmbBrowseState>()
    // 当前正在扫描的路径（防竞态：扫描完成时如果用户已按返回，忽略结果）
    private var pendingScanPath: String? = null
    /** 当前 SMB 扫描协程 Job — 每次开/关文件夹时取消旧的，防止 SMB 连接堆积 */
    private var scanJob: Job? = null

    // ── 回收站状态 ──
    private val _trashEntries = MutableStateFlow<List<TrashEntry>>(emptyList())
    val trashEntries: StateFlow<List<TrashEntry>> = _trashEntries.asStateFlow()

    private val _trashCount = MutableStateFlow(0)
    val trashCount: StateFlow<Int> = _trashCount.asStateFlow()

    // ── 星标状态 ──
    private val _starredBucketIds = MutableStateFlow<Set<String>>(emptySet())
    val starredBucketIds: StateFlow<Set<String>> = _starredBucketIds.asStateFlow()

    // ── 媒体项星标状态 ──
    private val _starredMediaUris = MutableStateFlow<Set<String>>(emptySet())
    val starredMediaUris: StateFlow<Set<String>> = _starredMediaUris.asStateFlow()

    private var loadMediaJob: Job? = null
    private var pendingAlbumId: String? = null

    init {
        // 从 SharedPreferences 恢复星标状态
        _starredBucketIds.value = loadStarredIds()
        _starredMediaUris.value = loadMediaStarredIds()
        // 恢复已保存的 SMB 设备列表
        _smbDevices.value = loadSmbDevices()
        refreshTrashCount()
        viewModelScope.launch {
            observer.observeMediaChanges()
                .debounce(500)
                .collect { refreshCurrentView() }
        }
    }

    // ══════════════════════════════════════
    //  数据加载（含回收站过滤）
    // ══════════════════════════════════════

    fun loadAlbums() {
        AppLogger.d("VM", "loadAlbums")
        viewModelScope.launch {
            _isLoading.value = true
            repository.loadAlbums()
                .onSuccess { albums ->
                    // 从各相册计数中减去已回收的文件数
                    val trashList = trashManager.getAll()
                    val trashPerAlbum = trashList
                        .filter { it.originalAlbumId != null }
                        .groupBy { it.originalAlbumId }
                        .mapValues { it.value.size }
                    val filtered = albums.mapNotNull { album ->
                        val trashCount = trashPerAlbum[album.bucketId] ?: 0
                        val newCount = album.count - trashCount
                        if (newCount <= 0) null
                        else album.copy(count = newCount)
                    }
                    _albums.value = filtered
                    AppLogger.d("VM", "loadAlbums OK count=${filtered.size} (trashed filtered)")
                }
                .onFailure {
                    _albums.value = emptyList()
                    AppLogger.e("VM", "loadAlbums FAIL", it)
                }
            _isLoading.value = false
        }
    }

    fun loadMedia(albumId: String? = null) {
        AppLogger.d("VM", "loadMedia albumId=[$albumId] cancel prev=$pendingAlbumId")
        loadMediaJob?.cancel()
        pendingAlbumId = albumId
        _isLoading.value = true

        loadMediaJob = viewModelScope.launch {
            repository.loadMediaItems(albumId = albumId)
                .onSuccess {
                    val match = pendingAlbumId == albumId
                    AppLogger.d("VM", "loadMedia OK albumId=[$albumId] items=${it.size} pendingMatch=$match")
                    if (match) {
                        // 过滤已回收文件
                        val trashedUris = trashManager.getAll().map { entry -> entry.uri }.toSet()
                        val filtered = it.filter { item -> item.uri.toString() !in trashedUris }
                        _mediaItems.value = filtered
                    }
                }
                .onFailure {
                    AppLogger.e("VM", "loadMedia FAIL albumId=[$albumId]", it)
                    if (pendingAlbumId == albumId) {
                        _mediaItems.value = emptyList()
                    }
                }
            if (pendingAlbumId == albumId) {
                _isLoading.value = false
            }
        }
    }

    private fun refreshCurrentView() {
        AppLogger.d("VM", "refreshCurrentView (observer)")
        loadAlbums()
        // 注意：不调用 loadMedia()。mediaItems 由 LaunchedEffect(albumId) 在相册入口时加载，
        // 以及 moveToTrash/restoreFromTrash 等用户操作增量修改。observer 异步调用 loadMedia
        // 会替换 _mediaItems 导致网格与预览之间的 index 错位。
    }

    // ══════════════════════════════════════
    //  相册重命名 — File.renameTo 直接改名
    // ══════════════════════════════════════

    /**
     * 重命名相册（磁盘目录重命名 + _albums/_mediaItems 同步更新）。
     * @param onResult 异步结果回调：true=成功，false=失败/不可操作
     */
    fun renameNow(bucketId: String, newName: String, onResult: (Boolean) -> Unit = {}) {
        AppLogger.d("Rename", "renameNow bucket=$bucketId → $newName")

        val allItems = _mediaItems.value.filter { it.albumId == bucketId }
        val dirPath = allItems.firstOrNull()?.let { item ->
            item.filePath.substringBeforeLast("/").takeIf { it.isNotEmpty() }
        } ?: run {
            val rp = allItems.firstOrNull()?.relativePath
            if (rp.isNullOrEmpty()) {
                AppLogger.d("Rename", "No path available, skip renameNow")
                onResult(false)
                return
            }
            "/storage/emulated/0/$rp"
        }

        viewModelScope.launch {
            val oldDir = File(dirPath)
            val newDir = File(oldDir.parentFile, newName)
            val ok = withContext(Dispatchers.IO) {
                try {
                    oldDir.renameTo(newDir)
                } catch (e: Exception) {
                    AppLogger.e("Rename", "renameTo threw", e)
                    false
                }
            }
            AppLogger.d("Rename", "renameTo result=$ok: $dirPath → ${newDir.absolutePath}")
            if (ok) {
                _albums.value = _albums.value.map {
                    if (it.bucketId == bucketId) it.copy(bucketName = newName) else it
                }
                // 同步更新 _mediaItems 中的 albumName 和 filePath
                val oldDirStr = dirPath.trimEnd('/')
                val newDirStr = newDir.absolutePath.trimEnd('/')
                _mediaItems.value = _mediaItems.value.map { item ->
                    if (item.albumId == bucketId) {
                        val newFilePath = if (item.filePath.startsWith(oldDirStr)) {
                            item.filePath.replace(oldDirStr, newDirStr)
                        } else {
                            item.filePath
                        }
                        item.copy(albumName = newName, filePath = newFilePath)
                    } else {
                        item
                    }
                }
                // scanFile 让 MediaStore 感知目录改名，使后续 loadMedia 能正确匹配新路径
                MediaScannerConnection.scanFile(
                    getApplication<Application>(),
                    arrayOf(newDir.absolutePath), null, null
                )
            }
            onResult(ok)
        }
    }

    // ══════════════════════════════════════
    //  回收站操作
    // ══════════════════════════════════════

    /**
     * 将媒体项移入回收站（逻辑删除——只写索引，不删文件）。
     */
    fun moveToTrash(item: MediaItem) {
        trashManager.add(
            TrashEntry(
                uri = item.uri.toString(),
                filePath = item.filePath,
                fileName = item.fileName,
                deleteTime = System.currentTimeMillis(),
                originalAlbumId = item.albumId,
                originalAlbumName = item.albumName,
                mimeType = item.mimeType
            )
        )
        refreshTrashCount()
        // 如果当前正在浏览该相册，从列表中移除（同步过滤，不走 loadMedia 全量重查）
        _mediaItems.value = _mediaItems.value.filter { it.uri.toString() != item.uri.toString() }
        // 直接更新相册计数（减 1），count=0 的相册自动移除
        _albums.value = _albums.value.mapNotNull { album ->
            if (album.bucketId == item.albumId) {
                val newCount = (album.count - 1).coerceAtLeast(0)
                if (newCount <= 0) null else album.copy(count = newCount)
            } else {
                album
            }
        }
        AppLogger.d("VM", "moveToTrash: ${item.fileName}")
    }

    /**
     * Snackbar 撤销时把删除的项加回列表（增量恢复，不走 loadMedia 全量重查）。
     * 仅用于 PreviewScreen 的 Snackbar 撤销路径。
     */
    fun addMediaItemBack(item: MediaItem) {
        _mediaItems.value = (_mediaItems.value + item).sortedByDescending { it.dateAdded }
        // 如果相册已被移除（上次 moveToTrash 时 count=0），重新加入
        val existingAlbum = _albums.value.find { it.bucketId == item.albumId }
        _albums.value = if (existingAlbum != null) {
            _albums.value.map { album ->
                if (album.bucketId == item.albumId) album.copy(count = album.count + 1) else album
            }
        } else {
            _albums.value + Album(
                bucketId = item.albumId ?: "",
                bucketName = item.albumName ?: "Unknown",
                coverUri = item.uri,
                count = 1,
                totalSize = item.size,
                imageCount = if (item.isImage) 1 else 0,
                videoCount = if (item.isVideo) 1 else 0,
                gifCount = if (item.isGif) 1 else 0
            )
        }
        AppLogger.d("VM", "addMediaItemBack: ${item.fileName}")
    }

    /**
     * 从回收站恢复文件（只清除索引标记，不涉及文件操作）。
     * ⚠️ 不再调用 refreshCurrentView()，避免异步 loadMedia 与同步过滤产生竞态。
     * 调用方（TrashScreen/PreviewScreen）根据需要自行调用 loadAlbums()。
     */
    fun restoreFromTrash(uri: String) {
        trashManager.remove(uri)
        refreshTrashCount()
        _trashEntries.value = trashManager.getAll()
        AppLogger.d("VM", "restoreFromTrash: $uri")
    }

    /**
     * 从回收站永久删除文件（物理删除 + 清除索引）。
     * ⚠️ 不再调用 refreshCurrentView()，避免异步 loadMedia 竞态。
     * @param uri 已物理删除的文件的 URI（只需从索引中移除）
     * @param albumId 可选的相册 ID，用于同步更新相册计数
     */
    fun permanentlyDeleteConfirmed(uri: String, albumId: String? = null) {
        trashManager.remove(uri)
        refreshTrashCount()
        _trashEntries.value = trashManager.getAll()
        // 同步更新相册计数，count=0 的相册自动移除
        if (albumId != null) {
            _albums.value = _albums.value.mapNotNull { album ->
                if (album.bucketId == albumId) {
                    val newCount = (album.count - 1).coerceAtLeast(0)
                    if (newCount <= 0) null else album.copy(count = newCount)
                } else {
                    album
                }
            }
        }
        AppLogger.d("VM", "permanentlyDeleteConfirmed: $uri")
    }

    /** 获取全部回收站条目（供 TrashScreen 使用） */
    fun loadTrashEntries() {
        _trashEntries.value = trashManager.getAll()
    }

    // ── 批量回收站操作 ──

    /** 批量从回收站恢复文件（只清除索引标记） */
    fun batchRestoreFromTrash(uris: List<String>) {
        trashManager.removeAll(uris)
        refreshTrashCount()
        _trashEntries.value = trashManager.getAll()
        AppLogger.d("VM", "batchRestoreFromTrash: ${uris.size} items")
    }

    /** 批量永久删除确认（物理删除已由调用方通过 IntentSender 完成） */
    fun batchPermanentlyDeleteConfirmed(entries: List<Pair<String, String?>>) {
        val uris = entries.map { it.first }
        trashManager.removeAll(uris)
        refreshTrashCount()
        _trashEntries.value = trashManager.getAll()
        // 更新相册计数，count=0 的相册自动移除
        entries.forEach { (_, albumId) ->
            if (albumId != null) {
                _albums.value = _albums.value.mapNotNull { album ->
                    if (album.bucketId == albumId) {
                        val newCount = (album.count - 1).coerceAtLeast(0)
                        if (newCount <= 0) null else album.copy(count = newCount)
                    } else {
                        album
                    }
                }
            }
        }
        AppLogger.d("VM", "batchPermanentlyDeleteConfirmed: ${uris.size} items")
    }

    // ══════════════════════════════════════
    //  文件重命名
    // ══════════════════════════════════════

    /**
     * 重命名文件后同步更新 ViewModel 中的 _mediaItems，确保下次预览时名称正确。
     * 调用时机：InfoCard 文件重命名成功后。
     */
    fun renameFile(uri: String, newFileName: String) {
        _mediaItems.value = _mediaItems.value.map { item ->
            if (item.uri.toString() == uri) {
                val newPath = item.filePath.substringBeforeLast("/") + "/" + newFileName
                item.copy(fileName = newFileName, filePath = newPath)
            } else {
                item
            }
        }
        AppLogger.d("VM", "renameFile: $uri → $newFileName")
    }

    // ══════════════════════════════════════
    //  预览页直接永久删除（不经过回收站）
    // ══════════════════════════════════════

    /**
     * 从预览页直接永久删除媒体项（不经过回收站）。
     * 物理删除由调用方通过 IntentSender 处理，此方法只更新 UI 状态。
     *
     * 同时清理回收站索引（如果该文件之前被快删），防止幽灵条目。
     */
    fun removeFromMediaItems(item: MediaItem) {
        val uriStr = item.uri.toString()
        // 清理回收站索引（防止该文件之前已移入回收站）
        if (trashManager.isTrashed(uriStr)) {
            trashManager.remove(uriStr)
            refreshTrashCount()
            _trashEntries.value = trashManager.getAll()
            AppLogger.d("VM", "removeFromMediaItems: also removed from trash index")
        }
        _mediaItems.value = _mediaItems.value.filter { it.uri.toString() != uriStr }
        _albums.value = _albums.value.mapNotNull { album ->
            if (album.bucketId == item.albumId) {
                val newCount = (album.count - 1).coerceAtLeast(0)
                if (newCount <= 0) null else album.copy(count = newCount)
            } else {
                album
            }
        }
        AppLogger.d("VM", "removeFromMediaItems: ${item.fileName}")
    }

    // ══════════════════════════════════════
    //  星标操作
    // ══════════════════════════════════════

    /** 切换相册星标状态（灰色 ⇄ 黄色），并持久化到 SharedPreferences */
    fun toggleStar(bucketId: String) {
        val current = _starredBucketIds.value
        val updated = if (bucketId in current) current - bucketId else current + bucketId
        _starredBucketIds.value = updated
        saveStarredIds(updated)
        AppLogger.d("VM", "toggleStar bucket=$bucketId starred=${bucketId in updated}")
    }

    private fun loadStarredIds(): Set<String> {
        return try {
            getApplication<Application>()
                .getSharedPreferences("rcgallery_prefs", android.content.Context.MODE_PRIVATE)
                .getStringSet("starred_albums", emptySet()) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun saveStarredIds(ids: Set<String>) {
        try {
            getApplication<Application>()
                .getSharedPreferences("rcgallery_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putStringSet("starred_albums", ids)
                .apply()
        } catch (e: Exception) {
            AppLogger.e("VM", "saveStarredIds FAIL", e)
        }
    }

    // ══════════════════════════════════════
    //  媒体项星标操作
    // ══════════════════════════════════════

    /** 切换媒体项星标状态（灰色 ⇄ 黄色），并持久化到 SharedPreferences */
    fun toggleMediaStar(uriStr: String) {
        val current = _starredMediaUris.value
        val updated = if (uriStr in current) current - uriStr else current + uriStr
        _starredMediaUris.value = updated
        saveMediaStarredIds(updated)
        AppLogger.d("VM", "toggleMediaStar uri=$uriStr starred=${uriStr in updated}")
    }

    private fun loadMediaStarredIds(): Set<String> {
        return try {
            getApplication<Application>()
                .getSharedPreferences("rcgallery_prefs", android.content.Context.MODE_PRIVATE)
                .getStringSet("starred_media", emptySet()) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun saveMediaStarredIds(ids: Set<String>) {
        try {
            getApplication<Application>()
                .getSharedPreferences("rcgallery_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putStringSet("starred_media", ids)
                .apply()
        } catch (e: Exception) {
            AppLogger.e("VM", "saveMediaStarredIds FAIL", e)
        }
    }

    private fun refreshTrashCount() {
        _trashCount.value = trashManager.count()
    }

    // ══════════════════════════════════════
    //  SMB 网络共享操作（文件夹相册模式）
    // ══════════════════════════════════════

    /**
     * 连接主机并枚举共享。
     *
     * 认证路径从 `smb://host/` 改为 `smb://host/IPC$/`，
     * 更符合 Windows SMB 共享发现机制。
     */
    fun smbConnect(host: String) {
        _smbBackStack.clear()
        _smbBrowseState.value = SmbBrowseState.Connecting(host, "正在连接 $host ...")
        viewModelScope.launch {
            smbRepository.listShares(host)
                .onSuccess { shares ->
                    val device = SmbDevice(host = host, displayName = host)
                    val existing = _smbDevices.value.find { it.host == host }
                    if (existing == null) {
                        _smbDevices.value = _smbDevices.value + device
                        saveSmbDevices(_smbDevices.value)
                    }
                    _smbBrowseState.value = SmbBrowseState.ShareList(host, shares)
                }
                .onFailure { e ->
                    AppLogger.e("SMB", "connect failed host=$host", e)
                    val msg = e.message ?: "连接失败，请确认：\n1. PC 已开启网络共享\n2. IP 地址正确\n3. 在同一局域网"
                    _smbBrowseState.value = SmbBrowseState.Error(msg)
                }
        }
    }

    /**
     * 打开一个路径（共享或子文件夹），扫描其内容。
     *
     * ### 两阶段加载策略
     *
     * 1. **Phase 1（快速）**: 只做顶层 `listFiles()` 区分文件夹/媒体文件，
     *    子文件夹的计数和封面暂不扫描 → **立即显示** FolderContent。
     * 2. **Phase 2（后台并行）**: 所有子文件夹同时扫描（async 并发），
     *    每完成一个就把计数+封面更新到界面上。
     *
     * 与 CX 文件管理器行为一致：点击共享文件夹 → 瞬间显示内容，
     * 文件夹计数约几百毫秒后逐个填充。
     *
     * 使用 pendingScanPath 防竞态：用户在 Phase 2 期间按返回，
     * 后续更新自动被忽略。
     */
    fun smbOpenFolder(path: String, folderName: String) {
        val state = _smbBrowseState.value
        val host = when (state) {
            is SmbBrowseState.ShareList -> state.host
            is SmbBrowseState.FolderContent -> state.host
            else -> return
        }

        // 取消旧的扫描协程（释放其 SMB 连接）
        scanJob?.cancel()
        scanJob = null

        // 将当前状态推入历史栈
        _smbBackStack.add(state)
        pendingScanPath = path

        scanJob = viewModelScope.launch {
            // ══ Phase 1: 快速扫描顶层 → 立即显示 ══
            // 加 30s 超时避免 SMB 连接耗尽时无限等待
            val quickResult = try {
                withTimeout(30_000L) {
                    smbRepository.quickScanFolderContent(path)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                AppLogger.e("SMB", "quickScanFolder timeout path=$path")
                if (pendingScanPath == path) {
                    pendingScanPath = null
                    _smbBrowseState.value = SmbBrowseState.Error("扫描超时，请稍后重试")
                }
                return@launch
            }
            quickResult
                .onSuccess { result ->
                    if (pendingScanPath != path) return@launch

                    // 使用 MutableList 以便后续更新
                    val mutableFolders = result.subFolders.toMutableList()

                    // 跳转 Connecting，直接显示 FolderContent
                    _smbBrowseState.value = SmbBrowseState.FolderContent(
                        host = host,
                        currentPath = path,
                        folderName = folderName,
                        subFolders = mutableFolders.toList(),
                        mediaFiles = result.mediaFiles
                    )

                    // ══ Phase 2: 限流并行扫描所有子文件夹 → 一次更新 ══
                    // 用 Semaphore(4) 限制并发 SMB 连接数，防止淹没 Windows SMB 服务端
                    val phase2Semaphore = java.util.concurrent.Semaphore(4)
                    val deferreds = mutableFolders.indices.map { i ->
                        async(Dispatchers.IO) {
                            phase2Semaphore.acquire()
                            try {
                                val updated = smbRepository.scanSingleFolder(mutableFolders[i])
                                i to updated
                            } finally {
                                phase2Semaphore.release()
                            }
                        }
                    }
                    // 等全部完成再一次性更新
                    var allDone = true
                    for (d in deferreds) {
                        if (pendingScanPath != path) { allDone = false; break }
                        val (idx, updated) = d.await()
                        mutableFolders[idx] = updated
                    }
                    if (allDone && pendingScanPath == path) {
                        pendingScanPath = null
                        _smbBrowseState.value = SmbBrowseState.FolderContent(
                            host = host,
                            currentPath = path,
                            folderName = folderName,
                            subFolders = mutableFolders.toList(),
                            mediaFiles = result.mediaFiles
                        )
                    }
                }
                .onFailure { e ->
                    AppLogger.e("SMB", "quickScanFolder failed path=$path", e)
                    if (pendingScanPath == path) {
                        pendingScanPath = null
                        if (_smbBackStack.isNotEmpty()) {
                            _smbBrowseState.value = _smbBackStack.removeLast()
                        } else {
                            _smbBrowseState.value = SmbBrowseState.Error(e.message ?: "无法扫描文件夹")
                        }
                    }
                }
        }
    }

    /**
     * 返回上一级。
     *
     * 使用历史栈直接回退，不重新连接服务器。
     * 取消当前正在进行的扫描。
     */
    fun smbGoBack() {
        // 取消扫描协程，释放 SMB 连接
        scanJob?.cancel()
        scanJob = null
        pendingScanPath = null
        val state = _smbBrowseState.value
        when (state) {
            is SmbBrowseState.FolderContent,
            is SmbBrowseState.ShareList -> {
                if (_smbBackStack.isNotEmpty()) {
                    _smbBrowseState.value = _smbBackStack.removeLast()
                } else {
                    _smbBrowseState.value = SmbBrowseState.DeviceList
                }
            }
            is SmbBrowseState.Error -> {
                _smbBrowseState.value = SmbBrowseState.DeviceList
            }
            else -> {}
        }
    }

    fun smbResetState() {
        _smbBackStack.clear()
        _smbBrowseState.value = SmbBrowseState.DeviceList
    }

    /**
     * 删除设备：从 UI 列表移除 + 清理认证缓存。
     */
    fun smbRemoveDevice(deviceId: String) {
        val device = _smbDevices.value.find { it.id == deviceId } ?: return
        _smbDevices.value = _smbDevices.value.filter { it.id != deviceId }
        saveSmbDevices(_smbDevices.value)
        // 清理该主机对应的认证缓存
        smbRepository.clearAuthCache(device.host)
        AppLogger.d("SMB", "removed device $deviceId (${device.host}), auth cache cleared")
    }

    // ── SMB 设备持久化（SharedPreferences）──

    private fun saveSmbDevices(devices: List<SmbDevice>) {
        try {
            val prefs = getApplication<Application>()
                .getSharedPreferences("rcgallery_smb_prefs", android.content.Context.MODE_PRIVATE)
            val json = devices.joinToString("|") { "${it.id},${it.host},${it.displayName}" }
            prefs.edit().putString("smb_devices", json).apply()
        } catch (e: Exception) {
            AppLogger.e("SMB", "saveSmbDevices FAIL", e)
        }
    }

    private fun loadSmbDevices(): List<SmbDevice> {
        return try {
            val prefs = getApplication<Application>()
                .getSharedPreferences("rcgallery_smb_prefs", android.content.Context.MODE_PRIVATE)
            val json = prefs.getString("smb_devices", "") ?: ""
            if (json.isEmpty()) return emptyList()
            json.split("|").mapNotNull { part ->
                val segs = part.split(",")
                if (segs.size >= 2) {
                    SmbDevice(id = segs[0], host = segs[1], displayName = segs.getOrElse(2) { "" })
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

package com.example.rcgallery.viewmodel

import android.app.Application
import android.media.MediaScannerConnection
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rcgallery.data.MediaRepository
import com.example.rcgallery.data.TrashManager
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.util.MediaStoreObserver
import com.example.rcgallery.model.Album
import com.example.rcgallery.model.MediaItem
import com.example.rcgallery.model.TrashEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
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

    // ── 回收站状态 ──
    private val _trashEntries = MutableStateFlow<List<TrashEntry>>(emptyList())
    val trashEntries: StateFlow<List<TrashEntry>> = _trashEntries.asStateFlow()

    private val _trashCount = MutableStateFlow(0)
    val trashCount: StateFlow<Int> = _trashCount.asStateFlow()

    private var loadMediaJob: Job? = null
    private var pendingAlbumId: String? = null

    init {
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

    fun renameNow(bucketId: String, newName: String) {
        AppLogger.d("Rename", "renameNow bucket=$bucketId → $newName")

        val allItems = _mediaItems.value.filter { it.albumId == bucketId }
        val dirPath = allItems.firstOrNull()?.let { item ->
            item.filePath.substringBeforeLast("/").takeIf { it.isNotEmpty() }
        } ?: run {
            val rp = allItems.firstOrNull()?.relativePath
            if (rp.isNullOrEmpty()) {
                AppLogger.d("Rename", "No path available, skip renameNow")
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
                MediaScannerConnection.scanFile(
                    getApplication<Application>(),
                    arrayOf(newDir.absolutePath), null, null
                )
            }
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
        // 直接更新相册计数（减 1），不走全量 loadAlbums
        _albums.value = _albums.value.map { album ->
            if (album.bucketId == item.albumId) album.copy(count = (album.count - 1).coerceAtLeast(0)) else album
        }
        AppLogger.d("VM", "moveToTrash: ${item.fileName}")
    }

    /**
     * Snackbar 撤销时把删除的项加回列表（增量恢复，不走 loadMedia 全量重查）。
     * 仅用于 PreviewScreen 的 Snackbar 撤销路径。
     */
    fun addMediaItemBack(item: MediaItem) {
        _mediaItems.value = (_mediaItems.value + item).sortedByDescending { it.dateAdded }
        _albums.value = _albums.value.map { album ->
            if (album.bucketId == item.albumId) album.copy(count = album.count + 1) else album
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
        // 同步更新相册计数
        if (albumId != null) {
            _albums.value = _albums.value.map { album ->
                if (album.bucketId == albumId) album.copy(count = (album.count - 1).coerceAtLeast(0)) else album
            }
        }
        AppLogger.d("VM", "permanentlyDeleteConfirmed: $uri")
    }

    /** 获取全部回收站条目（供 TrashScreen 使用） */
    fun loadTrashEntries() {
        _trashEntries.value = trashManager.getAll()
    }

    private fun refreshTrashCount() {
        _trashCount.value = trashManager.count()
    }
}

package com.example.rcgallery.viewmodel

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rcgallery.data.AlbumNameStore
import com.example.rcgallery.data.MediaRepository
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.util.MediaStoreObserver
import com.example.rcgallery.model.Album
import com.example.rcgallery.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(kotlinx.coroutines.FlowPreview::class)
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val observer = MediaStoreObserver(application)
    private val albumNameStore = AlbumNameStore(application)

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _mediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaItems: StateFlow<List<MediaItem>> = _mediaItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var loadMediaJob: Job? = null
    private var pendingAlbumId: String? = null

    // ── 相册重命名（虚拟 + 物理双轨）──

    data class PendingAlbumRename(
        val bucketId: String,
        val urisWithNewPaths: List<Pair<Uri, String>>
    )

    private val _pendingAlbumRename = MutableStateFlow<PendingAlbumRename?>(null)
    val pendingAlbumRename: StateFlow<PendingAlbumRename?> = _pendingAlbumRename.asStateFlow()

    init {
        viewModelScope.launch {
            observer.observeMediaChanges()
                .debounce(500)
                .collect { refreshCurrentView() }
        }
    }

    fun loadAlbums() {
        AppLogger.d("VM", "loadAlbums")
        viewModelScope.launch {
            _isLoading.value = true
            repository.loadAlbums()
                .onSuccess { albums ->
                    _albums.value = applyCustomNames(albums)
                    AppLogger.d("VM", "loadAlbums OK count=${albums.size}")
                }
                .onFailure {
                    _albums.value = emptyList()
                    AppLogger.e("VM", "loadAlbums FAIL", it)
                }
            _isLoading.value = false
        }
    }

    private fun applyCustomNames(albums: List<Album>): List<Album> {
        val customNames = albumNameStore.getAllCustomNames()
        if (customNames.isEmpty()) return albums
        return albums.map { album ->
            val customName = customNames[album.bucketId]
            if (customName != null) album.copy(bucketName = customName) else album
        }
    }

    fun loadMedia(albumId: String? = null) {
        AppLogger.d("VM", "loadMedia albumId=[$albumId] cancel prev=$pendingAlbumId")
        // 取消旧的加载 → 防止旧协程覆盖新数据
        loadMediaJob?.cancel()
        pendingAlbumId = albumId
        // 不提前清除 _mediaItems！这会导致覆盖态的 composable（key scope）销毁重建
        _isLoading.value = true

        loadMediaJob = viewModelScope.launch {
            repository.loadMediaItems(albumId = albumId)
                .onSuccess {
                    val match = pendingAlbumId == albumId
                    AppLogger.d("VM", "loadMedia OK albumId=[$albumId] items=${it.size} pendingMatch=$match")
                    if (match) {
                        _mediaItems.value = it
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
        viewModelScope.launch {
            repository.loadAlbums()
                .onSuccess { albums ->
                    _albums.value = applyCustomNames(albums)
                    AppLogger.d("VM", "refreshCurrentView OK albums=${albums.size}")
                }
            // 同时刷新当前相册的媒体列表（外部删图后自动更新网格）
            val albumId = pendingAlbumId
            if (albumId != null) {
                AppLogger.d("VM", "refreshCurrentView reload media for album=[$albumId]")
                loadMedia(albumId = albumId)
            }
        }
    }

    // ══════════════════════════════════════
    //  相册重命名（虚拟 + 物理双轨）
    // ══════════════════════════════════════

    /**
     * 重命名相册。
     * 虚拟重命名立即生效（SharedPreferences），
     * 物理移动推迟到用户退出 Preview 时触发（见 executePhysicalRename）。
     */
    fun renameAlbum(bucketId: String, oldName: String, newName: String) {
        AppLogger.d("Rename", "renameAlbum bucket=$bucketId $oldName → $newName")

        // Step 1: 虚拟重命名 — 立即生效
        albumNameStore.setCustomName(bucketId, newName)
        _albums.value = _albums.value.map {
            if (it.bucketId == bucketId) it.copy(bucketName = newName) else it
        }

        // Step 2: 构建待处理的物理移动数据
        val allItems = _mediaItems.value.filter { it.albumId == bucketId }
        val urisWithNewPaths = allItems.mapNotNull { item ->
            val rp = item.relativePath
            if (rp.isNotEmpty()) {
                item.uri to replaceAlbumSegment(rp, newName)
            } else {
                AppLogger.d("Rename", "Item ${item.id} has no relativePath, skipping")
                null
            }
        }

        if (urisWithNewPaths.isEmpty()) {
            AppLogger.d("Rename", "No files with relativePath, physical rename skipped")
            return
        }

        _pendingAlbumRename.value = PendingAlbumRename(
            bucketId = bucketId,
            urisWithNewPaths = urisWithNewPaths
        )
        AppLogger.d("Rename", "Pending physical rename: ${urisWithNewPaths.size} files")
    }

    /**
     * 清除待处理的物理移动（用户拒绝授权时调用）。
     */
    fun clearPendingRename() {
        _pendingAlbumRename.value = null
    }

    /**
     * 执行物理文件移动 — 在用户退出 Preview 时由 launcher 回调触发。
     * 所有文件移到新目录后清除虚拟名；部分失败则保留虚拟名保底。
     */
    fun executePhysicalRename(resolver: ContentResolver, onDone: () -> Unit) {
        val pending = _pendingAlbumRename.value ?: run { onDone(); return }
        _pendingAlbumRename.value = null

        viewModelScope.launch(Dispatchers.IO) {
            var successCount = 0
            var failCount = 0
            for ((uri, newPath) in pending.urisWithNewPaths) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, newPath)
                    }
                    resolver.update(uri, values, null, null)
                    successCount++
                } catch (e: Exception) {
                    AppLogger.e("Rename", "Physical move failed: uri=$uri path=$newPath", e)
                    failCount++
                }
            }

            AppLogger.d("Rename", "Physical rename done: ok=$successCount fail=$failCount")

            // 全部成功 → 清除虚拟名（物理名已生效）
            if (failCount == 0) {
                albumNameStore.removeCustomName(pending.bucketId)
            }

            withContext(Dispatchers.Main) {
                loadAlbums()
                loadMedia(albumId = pending.bucketId)
                onDone()
            }
        }
    }

    /**
     * 获取相册显示名（虚拟名优先，不存在则返回原始名）。
     */
    fun getAlbumDisplayName(bucketId: String?, originalName: String?): String {
        if (bucketId == null) return originalName ?: "未知"
        return albumNameStore.getCustomName(bucketId) ?: originalName ?: "未知"
    }

    /**
     * 替换相对路径的最后一段（相册目录名）为新名称。
     * "DCIM/Camera" → "DCIM/NewName"
     * "DCIM/Camera/sub" → "DCIM/NewName/sub"
     */
    private fun replaceAlbumSegment(relativePath: String, newName: String): String {
        val segments = relativePath.split("/")
        if (segments.isEmpty()) return newName
        return segments.dropLast(1).joinToString("/") + "/$newName"
    }
}

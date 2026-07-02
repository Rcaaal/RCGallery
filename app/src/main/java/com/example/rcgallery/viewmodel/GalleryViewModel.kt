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

    // ── 相册重命名状态（虚拟 + 物理双轨）──

    /**
     * 物理移动执行进度。
     */
    data class AlbumRenameProgress(
        val bucketId: String,
        val newName: String,
        val totalCount: Int,
        val completedCount: Int = 0,
        val failedCount: Int = 0,
        val currentFileName: String = "",
        val isRunning: Boolean = false,
        val isDone: Boolean = false,
        val rolledBack: Boolean = false,
        val allSucceeded: Boolean = false
    )

    private val _renameProgress = MutableStateFlow<AlbumRenameProgress?>(null)
    val renameProgress: StateFlow<AlbumRenameProgress?> = _renameProgress.asStateFlow()

    // 物理移动所需数据（不暴露给 UI）
    private var pendingUrisWithNewPaths: List<Pair<Uri, String>> = emptyList()
    private var pendingOldPaths: List<Pair<Uri, String>> = emptyList()
    private var pendingBucketId: String = ""
    private var pendingNewName: String = ""
    private var pendingOldNameOriginal: String = ""  // 原始相册名（用于拒绝后还原）

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
     * 重命名相册 — 虚拟名立即生效。
     * 调用后需立刻调用 requestAlbumRenamePermission() 弹授权窗。
     */
    fun renameAlbum(bucketId: String, oldName: String, newName: String): List<Pair<Uri, String>> {
        AppLogger.d("Rename", "renameAlbum bucket=$bucketId $oldName → $newName")

        // 保存原始相册名（用于拒绝后还原）
        val allItems = _mediaItems.value.filter { it.albumId == bucketId }
        pendingOldNameOriginal = oldName

        // Step 1: 虚拟重命名 — 立即生效
        albumNameStore.setCustomName(bucketId, newName)
        _albums.value = _albums.value.map {
            if (it.bucketId == bucketId) it.copy(bucketName = newName) else it
        }

        // Step 2: 构建待处理的物理移动数据
        val urisWithNewPaths = allItems.mapNotNull { item ->
            val rp = item.relativePath
            if (rp.isNotEmpty()) {
                item.uri to replaceAlbumSegment(rp, newName)
            } else {
                AppLogger.d("Rename", "Item ${item.id} has no relativePath, skipping")
                null
            }
        }

        // 保存原始路径供回滚用
        val oldPaths = allItems.mapNotNull { item ->
            val rp = item.relativePath
            if (rp.isNotEmpty()) item.uri to rp else null
        }

        pendingUrisWithNewPaths = urisWithNewPaths
        pendingOldPaths = oldPaths
        pendingBucketId = bucketId
        pendingNewName = newName

        if (urisWithNewPaths.isEmpty()) {
            AppLogger.d("Rename", "No files with relativePath, physical rename skipped")
            _renameProgress.value = AlbumRenameProgress(
                bucketId = bucketId, newName = newName,
                totalCount = 0, isDone = true, allSucceeded = true
            )
        }

        return urisWithNewPaths
    }

    /**
     * 用户已授权 → 标记为已授权待搬运。
     */
    fun grantAlbumRename() {
        val total = pendingUrisWithNewPaths.size
        AppLogger.d("Rename", "grantAlbumRename: $total files authorized")
        _renameProgress.value = AlbumRenameProgress(
            bucketId = pendingBucketId,
            newName = pendingNewName,
            totalCount = total,
            isRunning = false,
            isDone = false
        )
    }

    /**
     * 用户拒绝授权 → 清除全部状态，还原虚拟名。
     */
    fun cancelAlbumRename() {
        AppLogger.d("Rename", "cancelAlbumRename — user denied permission, reverting virtual name")
        val bucketId = pendingBucketId
        val oldName = pendingOldNameOriginal

        // 还原虚拟名
        if (bucketId.isNotEmpty()) {
            albumNameStore.removeCustomName(bucketId)
            // 更新 UI 中的相册名列表
            _albums.value = _albums.value.map {
                if (it.bucketId == bucketId) {
                    if (oldName.isNotEmpty()) it.copy(bucketName = oldName) else it
                } else it
            }
        }

        pendingUrisWithNewPaths = emptyList()
        pendingOldPaths = emptyList()
        pendingBucketId = ""
        pendingNewName = ""
        pendingOldNameOriginal = ""
        _renameProgress.value = null
    }

    /**
     * 开始/继续物理搬运 — 由 AlbumGridScreen 检测到已授权后自动调用。
     */
    fun startPhysicalRename(resolver: ContentResolver) {
        val progress = _renameProgress.value
        AppLogger.d("Rename", "startPhysicalRename called: progress=$progress")
        if (progress == null) return
        if (!progress.isRunning || progress.isDone) {
            AppLogger.d("Rename", "startPhysicalRename → executing")
            _executePhysicalRename(resolver)
        } else {
            AppLogger.d("Rename", "startPhysicalRename already running, skip")
        }
    }

    private fun _executePhysicalRename(resolver: ContentResolver) {
        val urisToMove = pendingUrisWithNewPaths
        AppLogger.d("Rename", "_executePhysicalRename: ${urisToMove.size} files to move")
        if (urisToMove.isEmpty()) return

        _renameProgress.value = _renameProgress.value?.copy(isRunning = true)

        viewModelScope.launch(Dispatchers.IO) {
            var completed = 0
            var failed = 0
            val moved = mutableListOf<Pair<Uri, String>>()  // 已成功移动的 (uri, originalPath)
            var shouldRollback = false

            for ((uri, newPath) in urisToMove) {
                if (shouldRollback) break

                // 更新当前文件名
                val fileName = uri.lastPathSegment ?: ""
                withContext(Dispatchers.Main) {
                    _renameProgress.value = _renameProgress.value?.copy(
                        currentFileName = fileName
                    )
                }

                try {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, newPath)
                    }
                    resolver.update(uri, values, null, null)
                    completed++
                    moved.add(uri to replaceAlbumSegment(newPath, pendingNewName).let {
                        // 回滚时需要原始路径，直接从 pendingOldPaths 查找
                        pendingOldPaths.find { it.first == uri }?.second ?: ""
                    })
                } catch (e: Exception) {
                    AppLogger.e("Rename", "Physical move FAILED: uri=$uri path=$newPath", e)
                    failed++
                    shouldRollback = true
                }
            }

            if (shouldRollback && moved.isNotEmpty()) {
                // ── 回滚：把已移动的文件移回原路径 ──
                AppLogger.d("Rename", "Rolling back ${moved.size} files...")
                for ((uri, _) in moved) {
                    val originalPath = pendingOldPaths.find { it.first == uri }?.second
                    if (originalPath != null) {
                        try {
                            val values = ContentValues().apply {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, originalPath)
                            }
                            resolver.update(uri, values, null, null)
                            AppLogger.d("Rename", "Rolled back: uri=$uri → $originalPath")
                        } catch (e2: Exception) {
                            AppLogger.e("Rename", "Rollback FAILED! uri=$uri", e2)
                        }
                    }
                }
                AppLogger.d("Rename", "Rollback complete: ${moved.size} files reverted")
            }

            val allOk = failed == 0 && !shouldRollback

            if (allOk) {
                albumNameStore.removeCustomName(pendingBucketId)
                AppLogger.d("Rename", "All $completed files moved, virtual name cleared")
            }

            // 清理临时数据
            pendingUrisWithNewPaths = emptyList()
            pendingOldPaths = emptyList()

            withContext(Dispatchers.Main) {
                _renameProgress.value = AlbumRenameProgress(
                    bucketId = pendingBucketId,
                    newName = pendingNewName,
                    totalCount = urisToMove.size,
                    completedCount = completed,
                    failedCount = failed,
                    currentFileName = "",
                    isRunning = false,
                    isDone = true,
                    allSucceeded = allOk,
                    rolledBack = shouldRollback
                )
                loadAlbums()
                if (!shouldRollback) {
                    loadMedia(albumId = pendingBucketId)
                }
            }
        }
    }

    /**
     * 清除重命名完成状态（AlbumGrid 展示完进度后调用）。
     */
    fun clearRenameProgress() {
        _renameProgress.value = null
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

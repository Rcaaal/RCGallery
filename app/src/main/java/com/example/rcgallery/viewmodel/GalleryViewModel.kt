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

    // ══════════════════════════════════════
    //  相册重命名 — 多任务队列
    // ══════════════════════════════════════

    data class RenameTask(
        val bucketId: String,
        val newName: String,
        val originalName: String,
        val urisWithNewPaths: List<Pair<Uri, String>>,
        val oldPaths: List<Pair<Uri, String>>
    )

    data class AlbumRenameProgress(
        val totalTasks: Int = 0,
        val currentTaskIndex: Int = 0,
        val currentBucketId: String = "",
        val currentNewName: String = "",
        val totalCount: Int = 0,
        val completedCount: Int = 0,
        val failedCount: Int = 0,
        val currentFileName: String = "",
        val isRunning: Boolean = false,
        val isDone: Boolean = false,
        val allSucceeded: Boolean = false,
        val lastResult: String = ""  // "完成", "已回滚", "N个文件失败"
    )

    private val _renameQueue = MutableStateFlow<List<RenameTask>>(emptyList())
    val renameQueue: StateFlow<List<RenameTask>> = _renameQueue.asStateFlow()

    private val _renameProgress = MutableStateFlow<AlbumRenameProgress?>(null)
    val renameProgress: StateFlow<AlbumRenameProgress?> = _renameProgress.asStateFlow()

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
        loadMediaJob?.cancel()
        pendingAlbumId = albumId
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
            val albumId = pendingAlbumId
            if (albumId != null) {
                AppLogger.d("VM", "refreshCurrentView reload media for album=[$albumId]")
                loadMedia(albumId = albumId)
            }
        }
    }

    // ══════════════════════════════════════
    //  相册重命名 — 多任务队列操作
    // ══════════════════════════════════════

    /**
     * 构建重命名任务数据（虚拟名立即生效）。
     * 返回 RenameTask（Preview 应根据授权结果调用 addRenameTask 或 cancelRenameTask）。
     */
    fun buildRenameTask(bucketId: String, oldName: String, newName: String): RenameTask? {
        AppLogger.d("Rename", "buildRenameTask bucket=$bucketId $oldName → $newName")

        // 虚拟重命名 — 立即生效
        albumNameStore.setCustomName(bucketId, newName)
        _albums.value = _albums.value.map {
            if (it.bucketId == bucketId) it.copy(bucketName = newName) else it
        }

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
        val oldPaths = allItems.mapNotNull { item ->
            val rp = item.relativePath
            if (rp.isNotEmpty()) item.uri to rp else null
        }

        if (urisWithNewPaths.isEmpty()) {
            AppLogger.d("Rename", "No files with relativePath, physical rename skipped")
            return null
        }

        return RenameTask(
            bucketId = bucketId,
            newName = newName,
            originalName = oldName,
            urisWithNewPaths = urisWithNewPaths,
            oldPaths = oldPaths
        )
    }

    /**
     * 授权成功后加入队列。
     */
    fun addRenameTask(task: RenameTask) {
        AppLogger.d("Rename", "addRenameTask: ${task.bucketId} → ${task.newName} (${task.urisWithNewPaths.size} files)")
        _renameQueue.value = _renameQueue.value + task
    }

    /**
     * 拒绝授权后还原虚拟名。
     */
    fun cancelRenameTask(bucketId: String, originalName: String) {
        AppLogger.d("Rename", "cancelRenameTask bucket=$bucketId revert to $originalName")
        albumNameStore.removeCustomName(bucketId)
        _albums.value = _albums.value.map {
            if (it.bucketId == bucketId) it.copy(bucketName = originalName) else it
        }
    }

    /**
     * 清除队列中所有与指定 bucketId 匹配的任务（手动取消时用）。
     */
    private fun removeQueueTask(bucketId: String) {
        _renameQueue.value = _renameQueue.value.filter { it.bucketId != bucketId }
    }

    /**
     * 处理队列中的下一个任务 — 由 AlbumGrid 按钮触发。
     * 如果已经在运行则忽略。
     */
    fun processNextTask(resolver: ContentResolver) {
        if (_renameProgress.value?.isRunning == true) {
            AppLogger.d("Rename", "processNextTask ignored: already running")
            return
        }
        val queue = _renameQueue.value
        if (queue.isEmpty()) {
            AppLogger.d("Rename", "processNextTask: queue empty")
            return
        }
        val task = queue.first()
        AppLogger.d("Rename", "processNextTask: ${task.bucketId} → ${task.newName} (${task.urisWithNewPaths.size} files)")

        val totalTasks = queue.size
        val currentIndex = 0
        val queueAfter = queue.drop(1)

        _renameProgress.value = AlbumRenameProgress(
            totalTasks = totalTasks,
            currentTaskIndex = currentIndex,
            currentBucketId = task.bucketId,
            currentNewName = task.newName,
            totalCount = task.urisWithNewPaths.size,
            isRunning = true
        )

        _executeTask(task, queueAfter, resolver)
    }

    /**
     * 执行单个任务，完成后取下一个或标记完成。
     */
    private fun _executeTask(
        task: RenameTask,
        remainingQueue: List<RenameTask>,
        resolver: ContentResolver
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var completed = 0
            var failed = 0
            val moved = mutableListOf<Pair<Uri, String>>()
            var shouldRollback = false

            for ((uri, newPath) in task.urisWithNewPaths) {
                if (shouldRollback) break

                val fileName = uri.lastPathSegment ?: ""
                withContext(Dispatchers.Main) {
                    _renameProgress.value = _renameProgress.value?.copy(
                        completedCount = completed,
                        currentFileName = fileName
                    )
                }

                try {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, newPath)
                    }
                    resolver.update(uri, values, null, null)
                    completed++
                    moved.add(uri to (task.oldPaths.find { it.first == uri }?.second ?: ""))
                } catch (e: Exception) {
                    AppLogger.e("Rename", "FAILED: uri=$uri path=$newPath", e)
                    failed++
                    shouldRollback = true
                }
            }

            // ── 回滚 ──
            if (shouldRollback && moved.isNotEmpty()) {
                AppLogger.d("Rename", "Rolling back ${moved.size} files...")
                for ((uri, _) in moved) {
                    val originalPath = task.oldPaths.find { it.first == uri }?.second
                    if (originalPath != null) {
                        try {
                            val values = ContentValues().apply {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, originalPath)
                            }
                            resolver.update(uri, values, null, null)
                            AppLogger.d("Rename", "Rollback: uri=$uri → $originalPath")
                        } catch (e2: Exception) {
                            AppLogger.e("Rename", "Rollback FAILED: uri=$uri", e2)
                        }
                    }
                }
                AppLogger.d("Rename", "Rollback complete: ${moved.size} files")
            }

            val allOk = !shouldRollback

            if (allOk) {
                albumNameStore.removeCustomName(task.bucketId)
                AppLogger.d("Rename", "Task done: ${task.bucketId} → ${task.newName} (all $completed files ok)")
            } else {
                // 任务失败，还原虚拟名（已回滚）
                albumNameStore.removeCustomName(task.bucketId)
                _albums.value = _albums.value.map {
                    if (it.bucketId == task.bucketId) it.copy(bucketName = task.originalName) else it
                }
                AppLogger.d("Rename", "Task FAILED, reverted virtual name: ${task.bucketId}")
            }

            // ── 更新队列（移除已完成任务）──
            _renameQueue.value = remainingQueue

            withContext(Dispatchers.Main) {
                loadAlbums()
                if (allOk) loadMedia(albumId = task.bucketId)

                if (!remainingQueue.isEmpty()) {
                    // 还有任务 → 自动开始下一个
                    _renameProgress.value = null
                    AppLogger.d("Rename", "auto-advance: ${remainingQueue.size} tasks remaining")
                    processNextTask(resolver)
                } else {
                    // 全部完成
                    _renameProgress.value = AlbumRenameProgress(
                        isDone = true,
                        allSucceeded = allOk,
                        lastResult = if (allOk) "✅ 全部搬运完成" else "⚠ 搬运失败，已回滚"
                    )
                }
            }
        }
    }

    /**
     * 清除进度状态（5 秒后自动或用户点击关闭）。
     */
    fun clearRenameProgress() {
        _renameProgress.value = null
    }

    fun getAlbumDisplayName(bucketId: String?, originalName: String?): String {
        if (bucketId == null) return originalName ?: "未知"
        return albumNameStore.getCustomName(bucketId) ?: originalName ?: "未知"
    }

    private fun replaceAlbumSegment(relativePath: String, newName: String): String {
        val segments = relativePath.split("/")
        if (segments.isEmpty()) return newName
        return segments.dropLast(1).joinToString("/") + "/$newName"
    }
}

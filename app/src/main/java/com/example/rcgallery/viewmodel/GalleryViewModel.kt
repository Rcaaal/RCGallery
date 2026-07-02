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
    //  Preview 只改名+入队，授权在 AlbumGrid 点击按钮时触发
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
        val lastResult: String = ""
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
    //  相册重命名 — Preview 只记录，授权在按钮触发
    // ══════════════════════════════════════

    /**
     * 构建任务并立即生效虚拟名 — 由 Preview 调用。
     */
    fun buildAndQueueTask(bucketId: String, oldName: String, newName: String) {
        AppLogger.d("Rename", "buildAndQueueTask bucket=$bucketId $oldName → $newName")

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
                AppLogger.d("Rename", "Item ${item.id} no relativePath")
                null
            }
        }
        val oldPaths = allItems.mapNotNull { item ->
            val rp = item.relativePath
            if (rp.isNotEmpty()) item.uri to rp else null
        }

        if (urisWithNewPaths.isEmpty()) {
            AppLogger.d("Rename", "No files with relativePath, skip queue")
            return
        }

        val task = RenameTask(bucketId, newName, oldName, urisWithNewPaths, oldPaths)
        _renameQueue.value = _renameQueue.value + task
        AppLogger.d("Rename", "Queued: ${task.bucketId} → ${task.newName} (queue=${_renameQueue.value.size})")
    }

    /**
     * 获取队列第一个任务的 URI 列表（供 AlbumGrid 弹授权窗用）。
     * 返回 null 表示队列为空。
     */
    fun getNextTaskUris(): List<Uri>? {
        val q = _renameQueue.value
        if (q.isEmpty()) return null
        val uris = q.first().urisWithNewPaths.map { it.first }
        AppLogger.d("Rename", "getNextTaskUris: ${q.first().newName} (${uris.size} URIs)")
        return uris
    }

    /**
     * 授权成功 → 执行当前第一个任务。
     * 完成后如果队列还有任务，isRunning=false 让按钮重新出现。
     */
    fun executeNextTask(resolver: ContentResolver) {
        val q = _renameQueue.value
        if (q.isEmpty()) { AppLogger.d("Rename", "executeNextTask: queue empty"); return }
        val task = q.first()
        val remaining = q.drop(1)
        AppLogger.d("Rename", "executeNextTask: ${task.bucketId} → ${task.newName}")

        _renameProgress.value = AlbumRenameProgress(
            totalTasks = remaining.size + 1,
            currentBucketId = task.bucketId,
            totalCount = task.urisWithNewPaths.size,
            isRunning = true
        )

        _executeTask(task, remaining, resolver)
    }

    /**
     * 用户拒绝授权 → 还原第一个任务的虚拟名并从队列移除。
     */
    fun cancelNextTask() {
        val q = _renameQueue.value
        if (q.isEmpty()) { AppLogger.d("Rename", "cancelNextTask: queue empty"); return }
        val task = q.first()
        AppLogger.d("Rename", "cancelNextTask: ${task.bucketId} revert to ${task.originalName}")

        // 还原虚拟名
        albumNameStore.removeCustomName(task.bucketId)
        _albums.value = _albums.value.map {
            if (it.bucketId == task.bucketId) it.copy(bucketName = task.originalName) else it
        }
        // 从队列移除
        _renameQueue.value = q.drop(1)
    }

    /**
     * 执行单个任务，完成后自动取下一个任务或标记完成。
     * 注意：下一个任务需要再次授权，所以不自动继续（弹出进度让按钮重新显示）。
     */
    private fun _executeTask(task: RenameTask, remainingQueue: List<RenameTask>, resolver: ContentResolver) {
        viewModelScope.launch(Dispatchers.IO) {
            var completed = 0
            var moved = mutableListOf<Pair<Uri, String>>()
            var shouldRollback = false

            for ((uri, newPath) in task.urisWithNewPaths) {
                if (shouldRollback) break

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
                    moved.add(uri to (task.oldPaths.find { it.first == uri }?.second ?: ""))
                } catch (e: Exception) {
                    AppLogger.e("Rename", "FAILED: uri=$uri path=$newPath", e)
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
                            resolver.update(uri, ContentValues().apply {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, originalPath)
                            }, null, null)
                        } catch (_: Exception) { }
                    }
                }
                AppLogger.d("Rename", "Rollback complete")
            }

            val allOk = !shouldRollback

            if (allOk) {
                albumNameStore.removeCustomName(task.bucketId)
                AppLogger.d("Rename", "Task done: ${task.bucketId} all $completed files ok")
            } else {
                albumNameStore.removeCustomName(task.bucketId)
                _albums.value = _albums.value.map {
                    if (it.bucketId == task.bucketId) it.copy(bucketName = task.originalName) else it
                }
                AppLogger.d("Rename", "Task FAILED, reverted: ${task.bucketId}")
            }

            // 更新队列
            _renameQueue.value = remainingQueue

            withContext(Dispatchers.Main) {
                loadAlbums()
                if (allOk) loadMedia(albumId = task.bucketId)

                if (remainingQueue.isNotEmpty()) {
                    // 还有任务，复位状态让按钮重新出现
                    _renameProgress.value = null
                    AppLogger.d("Rename", "More tasks remain (${remainingQueue.size}), waiting for next button click")
                } else {
                    _renameProgress.value = AlbumRenameProgress(
                        isDone = true,
                        allSucceeded = allOk,
                        lastResult = if (allOk) "✅ 搬运完成" else "⚠ 搬运失败，已回滚"
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

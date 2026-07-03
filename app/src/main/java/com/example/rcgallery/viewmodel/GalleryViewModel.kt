package com.example.rcgallery.viewmodel

import android.app.Application
import android.media.MediaScannerConnection
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import java.io.File

@OptIn(kotlinx.coroutines.FlowPreview::class)
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val observer = MediaStoreObserver(application)

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _mediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaItems: StateFlow<List<MediaItem>> = _mediaItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var loadMediaJob: Job? = null
    private var pendingAlbumId: String? = null

    init {
        viewModelScope.launch {
            observer.observeMediaChanges()
                .debounce(500)
                .collect { refreshCurrentView() }
        }
    }

    // ══════════════════════════════════════
    //  数据加载
    // ══════════════════════════════════════

    fun loadAlbums() {
        AppLogger.d("VM", "loadAlbums")
        viewModelScope.launch {
            _isLoading.value = true
            repository.loadAlbums()
                .onSuccess { albums ->
                    _albums.value = albums
                    AppLogger.d("VM", "loadAlbums OK count=${albums.size}")
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
                    _albums.value = albums
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
    //  相册重命名 — File.renameTo 直接改名
    //  无权限时由 UI 层引导跳转 Settings
    // ══════════════════════════════════════

    /**
     * 立即重命名 — 用户已有 MANAGE_EXTERNAL_STORAGE 权限时调用。
     * 直接执行 File.renameTo，更新 _albums，触发 MediaScanner。
     */
    fun renameNow(bucketId: String, newName: String) {
        AppLogger.d("Rename", "renameNow bucket=$bucketId → $newName")

        // 取目录路径
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

        viewModelScope.launch(Dispatchers.IO) {
            val oldDir = File(dirPath)
            val newDir = File(oldDir.parentFile, newName)
            try {
                val ok = oldDir.renameTo(newDir)
                AppLogger.d("Rename", "renameTo result=$ok: $dirPath → ${newDir.absolutePath}")
                if (ok) {
                    // 直接更新 _albums → 立刻显示新名
                    _albums.value = _albums.value.map {
                        if (it.bucketId == bucketId) it.copy(bucketName = newName) else it
                    }
                    MediaScannerConnection.scanFile(
                        getApplication<Application>(),
                        arrayOf(newDir.absolutePath), null, null
                    )
                }
            } catch (e: Exception) {
                AppLogger.e("Rename", "renameNow threw", e)
            }
        }
    }
}

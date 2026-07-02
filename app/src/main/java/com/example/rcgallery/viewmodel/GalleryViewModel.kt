package com.example.rcgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rcgallery.data.MediaRepository
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.util.MediaStoreObserver
import com.example.rcgallery.model.Album
import com.example.rcgallery.model.MediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
            observer.observeMediaChanges().collect {
                refreshCurrentView()
            }
        }
    }

    fun loadAlbums() {
        AppLogger.d("VM", "loadAlbums")
        viewModelScope.launch {
            _isLoading.value = true
            repository.loadAlbums()
                .onSuccess {
                    _albums.value = it
                    AppLogger.d("VM", "loadAlbums OK count=${it.size}")
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
                .onSuccess {
                    _albums.value = it
                    AppLogger.d("VM", "refreshCurrentView OK albums=${it.size}")
                }
            // 同时刷新当前相册的媒体列表（外部删图后自动更新网格）
            val albumId = pendingAlbumId
            if (albumId != null) {
                AppLogger.d("VM", "refreshCurrentView reload media for album=[$albumId]")
                loadMedia(albumId = albumId)
            }
        }
    }
}

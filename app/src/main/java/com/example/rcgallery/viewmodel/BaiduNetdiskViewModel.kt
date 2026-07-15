package com.example.rcgallery.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rcgallery.data.baidu.BaiduBrowseState
import com.example.rcgallery.data.baidu.BaiduCloudEntry
import com.example.rcgallery.data.baidu.BaiduDownloadProgress
import com.example.rcgallery.data.baidu.BaiduNetdiskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

class BaiduNetdiskViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BaiduNetdiskRepository(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, 0)
    private val backStack = ArrayDeque<String>()
    private var currentPath = ROOT_PATH

    private val _browseState = MutableStateFlow<BaiduBrowseState>(
        if (repository.isSignedIn) BaiduBrowseState.Loading("正在读取百度网盘...")
        else BaiduBrowseState.SignedOut
    )
    val browseState: StateFlow<BaiduBrowseState> = _browseState.asStateFlow()

    private val _downloadProgress = MutableStateFlow<BaiduDownloadProgress?>(null)
    val downloadProgress: StateFlow<BaiduDownloadProgress?> = _downloadProgress.asStateFlow()

    val isBackendConfigured: Boolean get() = repository.isBackendConfigured

    init {
        if (repository.isSignedIn) openFolder(ROOT_PATH, addToHistory = false)
    }

    fun beginLogin(): Result<String> = runCatching {
        val state = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_PENDING_STATE, state).apply()
        repository.authorizationUrl(state)
    }

    fun handleOAuthCallback(uri: Uri) {
        if (uri.scheme != "rcgallery" || uri.host != "baidu" || uri.path != "/oauth") return
        val expectedState = prefs.getString(KEY_PENDING_STATE, null)
        val state = uri.getQueryParameter("state")
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        if (error != null) {
            _browseState.value = BaiduBrowseState.Error("百度授权失败：$error")
            return
        }
        if (expectedState.isNullOrEmpty() || state != expectedState || code.isNullOrEmpty()) {
            _browseState.value = BaiduBrowseState.Error("百度授权回调校验失败，请重新登录")
            return
        }
        prefs.edit().remove(KEY_PENDING_STATE).apply()
        viewModelScope.launch {
            _browseState.value = BaiduBrowseState.Loading("正在完成百度授权...")
            runCatching { repository.completeLogin(code, state) }
                .onSuccess {
                    backStack.clear()
                    openFolder(ROOT_PATH, addToHistory = false)
                }
                .onFailure { _browseState.value = BaiduBrowseState.Error(it.message ?: "百度授权失败") }
        }
    }

    fun openFolder(path: String, addToHistory: Boolean = true) {
        val previousPath = (_browseState.value as? BaiduBrowseState.Folder)?.path
        if (addToHistory && previousPath != null && previousPath != path) backStack.addLast(previousPath)
        viewModelScope.launch {
            currentPath = path
            _browseState.value = BaiduBrowseState.Loading("正在读取 ${path.substringAfterLast('/').ifEmpty { "百度网盘" }}...")
            runCatching { repository.listFolder(path) }
                .onSuccess { entries -> _browseState.value = BaiduBrowseState.Folder(path, entries) }
                .onFailure { error ->
                    if (error.message?.contains("登录") == true || error.message?.contains("授权") == true) {
                        repository.signOut()
                        _browseState.value = BaiduBrowseState.SignedOut
                    } else {
                        _browseState.value = BaiduBrowseState.Error(error.message ?: "百度网盘读取失败")
                    }
                }
        }
    }

    fun goBack(): Boolean {
        val previous = backStack.removeLastOrNull() ?: return false
        openFolder(previous, addToHistory = false)
        return true
    }

    fun retry() {
        openFolder(currentPath, addToHistory = false)
    }

    fun download(entry: BaiduCloudEntry) {
        if (_downloadProgress.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            _downloadProgress.value = BaiduDownloadProgress(entry.name, 0, entry.size)
            runCatching {
                repository.downloadToGallery(entry) { copied, total ->
                    _downloadProgress.value = BaiduDownloadProgress(entry.name, copied, total)
                }
            }.onFailure { error ->
                _browseState.value = BaiduBrowseState.Error("下载失败：${error.message ?: "未知错误"}")
            }
            _downloadProgress.value = null
        }
    }

    suspend fun mediaUrl(entry: BaiduCloudEntry): String = repository.mediaUrl(entry)

    fun signOut() {
        repository.signOut()
        backStack.clear()
        _browseState.value = BaiduBrowseState.SignedOut
    }

    private companion object {
        const val ROOT_PATH = "/"
        const val PREFS_NAME = "rcgallery_baidu_auth"
        const val KEY_PENDING_STATE = "pending_oauth_state"
    }
}

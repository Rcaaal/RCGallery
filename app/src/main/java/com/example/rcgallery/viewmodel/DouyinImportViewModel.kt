package com.example.rcgallery.viewmodel

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rcgallery.data.douyin.DouyinImportState
import com.example.rcgallery.data.douyin.DouyinDynamicMediaStatus
import com.example.rcgallery.data.douyin.DouyinMediaResource
import com.example.rcgallery.data.douyin.DouyinParseException
import com.example.rcgallery.data.douyin.DouyinParser
import com.example.rcgallery.data.douyin.DouyinWorkInfo
import com.example.rcgallery.data.ImportedMediaOutput
import com.example.rcgallery.data.MediaImportHistoryPlatform
import com.example.rcgallery.data.MediaImportHistoryRepository
import com.example.rcgallery.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import com.example.rcgallery.data.douyin.DouyinCookieStore

class DouyinImportViewModel(application: Application) : AndroidViewModel(application) {
    private val parser = DouyinParser()
    private val cookieStore = DouyinCookieStore()
    private val _state = MutableStateFlow<DouyinImportState>(DouyinImportState.Idle)
    val state: StateFlow<DouyinImportState> = _state.asStateFlow()
    private var sessionCookies: String? = cookieStore.getAuthenticatedCookies()
    private val _isLoggedIn = MutableStateFlow(sessionCookies != null)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()
    // Parsing/dynamic enhancement and saving must not cancel one another.
    private var operationJob: Job? = null
    private var downloadJob: Job? = null
    private val downloadInProgress = AtomicBoolean(false)
    private val historyRepository = MediaImportHistoryRepository(application)
    private var lastInput: String = ""

    fun parse(text: String) {
        if (text.isBlank()) {
            _state.value = DouyinImportState.Error("请粘贴抖音分享链接")
            return
        }
        lastInput = text
        cancel()
        operationJob = viewModelScope.launch {
            _state.value = DouyinImportState.Parsing
            try {
                AppLogger.d("DouyinImport", "parse start authenticated=${sessionCookies != null}")
                val work = withContext(Dispatchers.IO) { parser.parse(text, sessionCookies) }
                AppLogger.d("DouyinImport", "parse success work=${work.workId} media=${work.media.size}")
                _state.value = DouyinImportState.Ready(work)

                // If the work has images, try API enhancement for animated image data
                if (work.media.any { it is DouyinMediaResource.Image }) {
                    tryEnhanceWithApi(work)
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (error: DouyinParseException) {
                AppLogger.e("DouyinImport", "parse failed: ${error.message}", error)
                _state.value = DouyinImportState.Error(error.message ?: "解析失败")
            } catch (error: Exception) {
                AppLogger.e("DouyinImport", "parse unexpected failure", error)
                _state.value = DouyinImportState.Error(
                    error.message?.takeIf { it.isNotBlank() } ?: "网络请求失败"
                )
            }
        }
    }

    fun refreshLoginState() {
        sessionCookies = cookieStore.getAuthenticatedCookies()
        _isLoggedIn.value = sessionCookies != null
        AppLogger.d("DouyinImport", "login refreshed loggedIn=${_isLoggedIn.value}")
        val ready = _state.value as? DouyinImportState.Ready ?: return
        if (_isLoggedIn.value && ready.work.media.any { it is DouyinMediaResource.Image }) {
            operationJob?.cancel()
            operationJob = viewModelScope.launch { tryEnhanceWithApi(ready.work) }
        }
    }

    fun clearLogin() {
        cookieStore.clearLogin()
        sessionCookies = null
        _isLoggedIn.value = false
    }

    private suspend fun tryEnhanceWithApi(work: DouyinWorkInfo) {
        val cookies = sessionCookies
        if (cookies == null) {
            updateDynamicStatus(work, DouyinDynamicMediaStatus.LoginRequired)
            return
        }

        try {
            val enhancement = try {
                withContext(Dispatchers.IO) {
                    parser.enhanceWithApi(work.workId, DouyinCookieStore.REQUEST_USER_AGENT, cookies)
                }
            } catch (nativeError: Exception) {
                AppLogger.d("DouyinImport", "native detail failed; trying WebView bridge work=${work.workId}")
                val webJson = cookieStore.fetchDetailViaWebView(work.workId)
                    ?: throw nativeError
                parser.enhanceFromDetailJson(webJson)
            }

            val currentState = _state.value
            if (currentState !is DouyinImportState.Ready || currentState.work.workId != work.workId) return

            val enhancedMedia = currentState.work.media.map { media ->
                if (media is DouyinMediaResource.Image) {
                    val matches = media.sourceKey?.let(enhancement.urlsBySourceKey::get)
                        ?: enhancement.urlsByIndex[media.index - 1]
                    if (matches != null) {
                        DouyinMediaResource.AnimatedImage(
                            media.index, media.urls, matches, media.sourceKey
                        )
                    } else media
                } else media
            }
            _state.value = DouyinImportState.Ready(
                currentState.work.copy(
                    media = enhancedMedia,
                    dynamicMediaStatus = if (enhancedMedia.any { it is DouyinMediaResource.AnimatedImage }) {
                        DouyinDynamicMediaStatus.Available
                    } else {
                        DouyinDynamicMediaStatus.None
                    },
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.e("DouyinImport", "dynamic enhancement failed work=${work.workId}", error)
            updateDynamicStatus(work, DouyinDynamicMediaStatus.Failed)
        }
    }

    private fun updateDynamicStatus(work: DouyinWorkInfo, status: DouyinDynamicMediaStatus) {
        val current = _state.value
        if (current is DouyinImportState.Ready && current.work.workId == work.workId) {
            _state.value = DouyinImportState.Ready(current.work.copy(dynamicMediaStatus = status))
        }
    }

    fun download(work: DouyinWorkInfo, onSaved: () -> Unit) {
        if (!downloadInProgress.compareAndSet(false, true)) {
            AppLogger.d("DouyinImport", "duplicate download ignored work=${work.workId}")
            return
        }
        downloadJob = viewModelScope.launch {
            try {
            // The Ready state is published before the WebView detail bridge completes.
            // Wait for that bridge so animated-image video URLs are not lost on save.
            val pendingEnhancement = operationJob
            if (pendingEnhancement?.isActive == true) {
                AppLogger.d("DouyinImport", "download waiting dynamic enhancement work=${work.workId}")
                pendingEnhancement.join()
            }
            val resolvedWork = (_state.value as? DouyinImportState.Ready)
                ?.work
                ?.takeIf { it.workId == work.workId }
                ?: work
            val resources = resourcesFor(resolvedWork)
            AppLogger.d(
                "DouyinImport",
                "download start work=${resolvedWork.workId} media=${resolvedWork.media.size} resources=${resources.size}"
            )
            var completed = 0
            var failed = 0
            var firstName: String? = null
            val savedOutputs = mutableListOf<ImportedMediaOutput>()
            try {
                resources.forEachIndexed { index, resource ->
                    _state.value = downloadingState(resolvedWork, index, resources.size, completed, failed)
                    try {
                        val output = downloadResource(
                            resolvedWork, resource, index, resources.size, completed, failed
                        )
                        if (firstName == null) firstName = output.displayName
                        savedOutputs += output
                        completed++
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        failed++
                    }
                }
                if (completed == 0) {
                    _state.value = DouyinImportState.Error("下载失败，未保存任何文件")
                } else {
                    withContext(Dispatchers.IO) {
                        historyRepository.record(
                            platform = MediaImportHistoryPlatform.DOUYIN,
                            sourceUrl = lastInput,
                            title = resolvedWork.title,
                            author = resolvedWork.author,
                            coverUrl = resolvedWork.coverUrl,
                            successCount = completed,
                            failedCount = failed,
                            outputs = savedOutputs,
                        )
                    }
                    _state.value = DouyinImportState.Success(completed, failed, firstName)
                    onSaved()
                }
            } catch (_: CancellationException) {
                return@launch
            }
            } finally {
                downloadInProgress.set(false)
            }
        }
    }

    fun cancel() {
        val current = _state.value
        operationJob?.cancel()
        downloadJob?.cancel()
        operationJob = null
        downloadJob = null
        _state.value = when (current) {
            is DouyinImportState.Downloading -> DouyinImportState.Ready(current.work)
            DouyinImportState.Parsing -> DouyinImportState.Idle
            else -> current
        }
    }

    fun reset() {
        cancel()
        _state.value = DouyinImportState.Idle
    }

    private data class DownloadResource(
        val urls: List<String>,
        val baseName: String,
        val isVideo: Boolean,
    )

    private fun resourcesFor(work: DouyinWorkInfo): List<DownloadResource> {
        val titlePrefix = work.author
            ?.let(::sanitizeFilePart)
            ?.takeIf { it.isNotBlank() }
            ?.let { "${it}_${work.title}" }
            ?: work.title
        return work.media.flatMapIndexed { position, media ->
            val baseName = if (work.media.size == 1) titlePrefix
                else "${titlePrefix}_${(position + 1).toString().padStart(2, '0')}"
            when (media) {
                is com.example.rcgallery.data.douyin.DouyinMediaResource.Image -> listOf(
                    DownloadResource(urls = media.urls, baseName = baseName, isVideo = false)
                )
                is com.example.rcgallery.data.douyin.DouyinMediaResource.AnimatedImage -> listOf(
                    DownloadResource(urls = media.urls, baseName = baseName, isVideo = false),
                    DownloadResource(urls = media.animatedUrls, baseName = "${baseName}_anim", isVideo = true),
                )
                is com.example.rcgallery.data.douyin.DouyinMediaResource.Video -> listOf(
                    DownloadResource(
                        urls = media.urls,
                        baseName = if (work.media.size == 1) titlePrefix
                        else "${titlePrefix}_video_${(position + 1).toString().padStart(2, '0')}",
                        isVideo = true,
                    )
                )
            }
        }
    }

    private fun sanitizeFilePart(value: String): String = value
        .replace(Regex("""[\\/:*?\"<>|#\n\r]"""), "_")
        .replace(Regex("""\.{2,}"""), ".")
        .trim(' ', '.')
        .take(48)

    private fun downloadingState(
        work: DouyinWorkInfo,
        currentIndex: Int,
        itemCount: Int,
        completedCount: Int,
        failedCount: Int,
        downloadedBytes: Long = 0L,
        totalBytes: Long = -1L,
        bytesPerSecond: Long = 0L,
    ) = DouyinImportState.Downloading(
        work, currentIndex, itemCount, completedCount, failedCount,
        downloadedBytes, totalBytes, bytesPerSecond
    )

    private suspend fun downloadResource(
        work: DouyinWorkInfo,
        resource: DownloadResource,
        currentIndex: Int,
        itemCount: Int,
        completedCount: Int,
        failedCount: Int,
    ): ImportedMediaOutput = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        resource.urls.forEach { url ->
            coroutineContext.ensureActive()
            try {
                return@withContext downloadFromUrl(
                    work, resource, url, currentIndex, itemCount, completedCount, failedCount
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("没有可用的下载地址")
    }

    private suspend fun downloadFromUrl(
        work: DouyinWorkInfo,
        resource: DownloadResource,
        url: String,
        currentIndex: Int,
        itemCount: Int,
        completedCount: Int,
        failedCount: Int,
    ): ImportedMediaOutput {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
            // Signed media URLs are bound to the authenticated WebView session fingerprint.
            setRequestProperty(
                "User-Agent",
                if (sessionCookies != null) DouyinCookieStore.REQUEST_USER_AGENT else work.userAgent
            )
            setRequestProperty("Referer", "https://www.douyin.com/")
            sessionCookies?.let { setRequestProperty("Cookie", it) }
        }
        var pendingUri: Uri? = null

        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("下载失败（HTTP $code）")
            val contentType = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
            if (contentType.contains("text/html") || contentType.contains("application/json")) {
                throw IllegalStateException("下载地址已失效，请重新解析")
            }
            if (resource.isVideo && contentType.startsWith("audio/")) {
                throw IllegalStateException("该地址是背景音乐，不是视频")
            }
            val mimeType = if (resource.isVideo) "video/mp4" else normalizeImageMime(contentType)
            val collection = if (resource.isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val displayName = uniqueDisplayName(
                "${resource.baseName}.${extensionFor(mimeType)}", collection
            )
            val resolver = getApplication<Application>().contentResolver
            pendingUri = resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, DOUYIN_RELATIVE_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            ) ?: throw IllegalStateException("无法创建本地媒体文件")
            val uri = pendingUri
            val total = connection.contentLengthLong.coerceAtLeast(-1L)
            resolver.openOutputStream(uri, "w")?.use { rawOutput ->
                    BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                        BufferedOutputStream(rawOutput, BUFFER_SIZE).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var downloaded = 0L
                            var windowBytes = 0L
                            var windowStart = System.nanoTime()
                            var lastUpdate = windowStart
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                windowBytes += read
                                val now = System.nanoTime()
                                if (now - lastUpdate >= PROGRESS_INTERVAL_NS) {
                                    val elapsedSeconds = (now - windowStart) / 1_000_000_000.0
                                    val speed = if (elapsedSeconds > 0) (windowBytes / elapsedSeconds).toLong() else 0L
                                    _state.value = downloadingState(
                                        work, currentIndex, itemCount, completedCount, failedCount,
                                        downloaded, total, speed
                                    )
                                    windowStart = now
                                    windowBytes = 0
                                    lastUpdate = now
                                }
                            }
                            output.flush()
                            if (downloaded <= 0) throw IllegalStateException("下载结果为空")
                            if (total > 0 && downloaded != total) throw IllegalStateException("下载不完整（$downloaded/$total）")
                        }
                    }
            } ?: throw IllegalStateException("无法写入本地媒体文件")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
            )
            pendingUri = null
            return ImportedMediaOutput(displayName, uri.toString())
        } finally {
            pendingUri?.let { getApplication<Application>().contentResolver.delete(it, null, null) }
            connection.disconnect()
        }
    }

    private fun normalizeImageMime(contentType: String): String = when (contentType) {
        "image/png" -> "image/png"
        "image/webp" -> "image/webp"
        "image/heic", "image/heif" -> "image/heic"
        "image/jpeg", "image/jpg", "" -> "image/jpeg"
        else -> if (contentType.startsWith("image/")) contentType else "image/jpeg"
    }

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "video/mp4" -> "mp4"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        else -> "jpg"
    }

    private fun uniqueDisplayName(baseName: String, collection: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        var candidate = baseName
        val stem = baseName.substringBeforeLast('.', baseName)
        val extension = baseName.substringAfterLast('.', "jpg")
        var suffix = 1
        while (true) {
            val exists = resolver.query(
                collection,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DISPLAY_NAME}=? AND ${MediaStore.Video.Media.RELATIVE_PATH}=?",
                arrayOf(candidate, DOUYIN_RELATIVE_PATH),
                null,
            )?.use { it.moveToFirst() } == true
            if (!exists) return candidate
            candidate = "$stem ($suffix).$extension"
            suffix++
        }
    }

    companion object {
        private const val BUFFER_SIZE = 256 * 1024
        private const val PROGRESS_INTERVAL_NS = 250_000_000L
        const val DOUYIN_RELATIVE_PATH = "DCIM/RCGallery/Douyin/"
    }
}

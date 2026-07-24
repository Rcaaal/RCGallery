package com.example.rcgallery.viewmodel

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rcgallery.data.ImportedMediaOutput
import com.example.rcgallery.data.MediaImportHistoryPlatform
import com.example.rcgallery.data.MediaImportHistoryRepository
import com.example.rcgallery.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

sealed interface XImportState {
    data object Idle : XImportState
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val currentIndex: Int = 1,
        val totalItems: Int = 1,
        val displayName: String = "",
    ) : XImportState
    data class Success(val displayName: String) : XImportState
    data class Error(val message: String) : XImportState
}

class XImportViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<XImportState>(XImportState.Idle)
    val state: StateFlow<XImportState> = _state.asStateFlow()
    private val downloadInProgress = AtomicBoolean(false)
    private val historyRepository = MediaImportHistoryRepository(application)

    fun reset() {
        if (_state.value !is XImportState.Downloading) _state.value = XImportState.Idle
    }

    fun download(
        sourceUrl: String,
        downloadUrl: String,
        userAgent: String,
        cookie: String?,
        referer: String?,
        contentDisposition: String?,
        advertisedMime: String?,
        onSaved: () -> Unit,
    ) {
        if (!downloadInProgress.compareAndSet(false, true)) {
            AppLogger.d("XImport", "duplicate web download ignored")
            return
        }
        viewModelScope.launch {
            try {
                val output = withContext(Dispatchers.IO) {
                    downloadToMediaStore(
                        downloadUrl = downloadUrl,
                        userAgent = userAgent,
                        cookie = cookie,
                        requestHeaders = buildMap {
                            referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
                        },
                        contentDisposition = contentDisposition,
                        advertisedMime = advertisedMime,
                        onProgress = { downloaded, total ->
                            _state.value = XImportState.Downloading(
                                progress = if (total > 0L) downloaded.toFloat() / total else -1f,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                            )
                        },
                    )
                }
                withContext(Dispatchers.IO) {
                    historyRepository.record(
                        platform = MediaImportHistoryPlatform.X,
                        sourceUrl = sourceUrl,
                        title = output.displayName.substringBeforeLast('.'),
                        author = null,
                        coverUrl = null,
                        successCount = 1,
                        failedCount = 0,
                        outputs = listOf(output),
                    )
                }
                _state.value = XImportState.Success(output.displayName)
                onSaved()
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (error: Exception) {
                AppLogger.e("XImport", "web download failed", error)
                _state.value = XImportState.Error("下载失败，请在网页中重新选择下载项")
            } finally {
                downloadInProgress.set(false)
            }
        }
    }

    private suspend fun downloadToMediaStore(
        downloadUrl: String,
        userAgent: String,
        cookie: String?,
        requestHeaders: Map<String, String>,
        contentDisposition: String?,
        advertisedMime: String?,
        onProgress: (Long, Long) -> Unit,
    ): ImportedMediaOutput {
        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("User-Agent", userAgent.ifBlank { DEFAULT_USER_AGENT })
            cookie?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
            requestHeaders.forEach { (name, value) ->
                if (name !in UNSAFE_PASSTHROUGH_HEADERS &&
                    !name.equals("User-Agent", ignoreCase = true) &&
                    value.isNotBlank()
                ) {
                    setRequestProperty(name, value)
                }
            }
        }
        var pendingUri: Uri? = null
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val authHint = if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                    "，X 登录授权可能已失效，请重新登录后再试"
                } else ""
                throw IllegalStateException("下载失败（HTTP $code$authHint）")
            }
            val mimeType = normalizeMime(connection.contentType ?: advertisedMime.orEmpty())
            if (mimeType.startsWith("text/") || mimeType.contains("html")) {
                throw IllegalStateException("解析站没有返回媒体文件，请在页面中选择下载链接")
            }
            val isVideo = mimeType.startsWith("video/")
            val resolver = getApplication<Application>().contentResolver
            val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val displayName = uniqueName(
                resolver,
                collection,
                fileName(contentDisposition, downloadUrl, mimeType),
            )
            pendingUri = resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, X_RELATIVE_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ) ?: throw IllegalStateException("无法创建本地媒体文件")
            val total = connection.contentLengthLong.coerceAtLeast(-1L)
            val uri = pendingUri
            resolver.openOutputStream(uri, "w")?.use { rawOutput ->
                BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                    BufferedOutputStream(rawOutput, BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var copied = 0L
                        var lastPublish = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (copied - lastPublish >= PROGRESS_STEP_BYTES) {
                                lastPublish = copied
                                onProgress(copied, total)
                            }
                        }
                        output.flush()
                        if (copied <= 0L) throw IllegalStateException("下载文件为空")
                        if (total > 0L && copied != total) throw IllegalStateException("下载不完整（$copied/$total）")
                        onProgress(copied, total)
                    }
                }
            } ?: throw IllegalStateException("无法写入本地媒体文件")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            pendingUri = null
            return ImportedMediaOutput(displayName, uri.toString())
        } finally {
            pendingUri?.let { getApplication<Application>().contentResolver.delete(it, null, null) }
            connection.disconnect()
        }
    }

    private fun normalizeMime(raw: String): String = when (val mime = raw.substringBefore(';').lowercase()) {
        "video/mp4", "video/webm", "image/jpeg", "image/png", "image/webp", "image/gif" -> mime
        else -> if (mime.startsWith("video/")) mime else "image/jpeg"
    }

    private fun fileName(contentDisposition: String?, downloadUrl: String, mimeType: String): String {
        val headerName = Regex("filename\\*?=(?:UTF-8''|\\\")?([^;\\\"]+)", RegexOption.IGNORE_CASE)
            .find(contentDisposition.orEmpty())?.groupValues?.getOrNull(1)
            ?.let { Uri.decode(it).trim() }
        val fromUrl = Uri.parse(downloadUrl).lastPathSegment?.substringBefore('?')
        val base = (headerName ?: fromUrl).orEmpty().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val fallback = "x_media_${System.currentTimeMillis()}"
        val stem = base.substringBeforeLast('.', base).ifBlank { fallback }
        val extension = base.substringAfterLast('.', extensionFor(mimeType))
        return "${stem.take(160)}.${extension.lowercase()}"
    }

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }

    private fun uniqueName(resolver: android.content.ContentResolver, collection: Uri, base: String): String {
        val stem = base.substringBeforeLast('.', base)
        val extension = base.substringAfterLast('.', "jpg")
        var suffix = 0
        while (true) {
            val candidate = if (suffix == 0) base else "$stem ($suffix).$extension"
            val exists = resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(candidate, X_RELATIVE_PATH),
                null,
            )?.use { it.moveToFirst() } == true
            if (!exists) return candidate
            suffix++
        }
    }

    private companion object {
        const val BUFFER_SIZE = 256 * 1024
        const val PROGRESS_STEP_BYTES = 256 * 1024L
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"
        const val X_RELATIVE_PATH = "DCIM/RCGallery/X/"
        val UNSAFE_PASSTHROUGH_HEADERS = setOf(
            "Cookie", "Host", "Connection", "Content-Length", "Accept-Encoding",
        )
    }
}

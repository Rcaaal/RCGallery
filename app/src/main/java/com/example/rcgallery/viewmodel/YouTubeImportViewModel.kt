package com.example.rcgallery.viewmodel

import android.app.Application
import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rcgallery.data.youtube.YouTubeCodecMode
import com.example.rcgallery.data.youtube.YouTubeAuthManager
import com.example.rcgallery.data.youtube.YouTubeDownloadHistory
import com.example.rcgallery.data.youtube.YouTubeImportState
import com.example.rcgallery.data.youtube.YouTubeParseException
import com.example.rcgallery.data.youtube.YouTubeParser
import com.example.rcgallery.data.youtube.YouTubeWorkInfo
import com.example.rcgallery.data.ImportedMediaOutput
import com.example.rcgallery.data.MediaImportHistoryPlatform
import com.example.rcgallery.data.MediaImportHistoryRepository
import com.example.rcgallery.util.AppLogger
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import kotlin.math.roundToLong

class YouTubeImportViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("youtube_prefs", 0)
    private val authManager = YouTubeAuthManager(application)
    private var parser = YouTubeParser(application, loadCookiePath(), authManager)
    private val _state = MutableStateFlow<YouTubeImportState>(YouTubeImportState.Idle)
    val state: StateFlow<YouTubeImportState> = _state.asStateFlow()

    private val _hasCookie = MutableStateFlow(loadCookiePath() != null)
    val hasCookie: StateFlow<Boolean> = _hasCookie.asStateFlow()
    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<YouTubeDownloadHistory>> = _history.asStateFlow()

    private var parseJob: Job? = null
    private val downloadMutex = Mutex()
    private val downloadJobs = ConcurrentHashMap.newKeySet<Job>()
    private val activeProcessIds = ConcurrentHashMap.newKeySet<String>()
    private val downloadInProgress = AtomicBoolean(false)
    private val historyRepository = MediaImportHistoryRepository(application)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.migrateLegacyYoutubeHistoryIfNeeded()
        }
    }

    // ── cookie ───────────────────────────────────────────────────────────

    fun setCookieFile(path: String) {
        prefs.edit().putString(KEY_COOKIE_PATH, path).apply()
        parser = YouTubeParser(getApplication(), path, authManager)
        _hasCookie.value = true
    }

    fun setVisitorData(value: String) = authManager.setVisitorData(value)

    fun clearCookie() {
        loadCookiePath()?.let { File(it).delete() }
        prefs.edit().remove(KEY_COOKIE_PATH).apply()
        authManager.clear()
        parser = YouTubeParser(getApplication(), null, authManager)
        _hasCookie.value = false
    }

    private fun loadCookiePath(): String? {
        val path = prefs.getString(KEY_COOKIE_PATH, null) ?: return null
        val persistentPath = File(
            getApplication<Application>().filesDir,
            "youtube_cookies.txt"
        ).absolutePath
        if (path != persistentPath) {
            // The previous build stored a filtered cookie file in cacheDir.
            // Do not report that stale file as a valid login after upgrading.
            prefs.edit().remove(KEY_COOKIE_PATH).apply()
            return null
        }
        return if (File(path).exists()) path else {
            prefs.edit().remove(KEY_COOKIE_PATH).apply(); null
        }
    }

    // ── parse ────────────────────────────────────────────────────────────

    fun parse(input: String) {
        if (input.isBlank()) {
            _state.value = YouTubeImportState.Error("请粘贴 YouTube 视频链接")
            return
        }
        parseJob?.cancel()
        parseJob = null
        parseJob = viewModelScope.launch {
            _state.value = YouTubeImportState.Initializing
            try {
                _state.value = YouTubeImportState.Parsing
                _state.value = YouTubeImportState.Ready(
                    withContext(Dispatchers.IO) { parser.parse(input) }
                )
            } catch (_: CancellationException) {
                return@launch
            } catch (e: YouTubeParseException) {
                _state.value = YouTubeImportState.Error(e.message ?: "YouTube 解析失败")
            } catch (e: Exception) {
                AppLogger.e("YouTubeImport", "parse failed", e)
                _state.value = YouTubeImportState.Error(
                    e.message?.takeIf { it.isNotBlank() } ?: "YouTube 解析失败"
                )
            }
        }
    }

    // ── download ──────────────────────────────────────────────────────────

    fun download(
        work: YouTubeWorkInfo,
        height: Int,
        codecMode: YouTubeCodecMode,
        onSaved: () -> Unit,
    ) {
        if (!downloadInProgress.compareAndSet(false, true)) {
            AppLogger.d("YouTubeImport", "duplicate download ignored id=${work.id}")
            return
        }
        val job = viewModelScope.launch {
            downloadMutex.withLock {
                _state.value = YouTubeImportState.Downloading(work, 0f, "")
                try {
                    val output = withContext(Dispatchers.IO) {
                        downloadVideo(work, height, codecMode)
                    }
                    withContext(Dispatchers.IO) {
                        historyRepository.record(
                            platform = MediaImportHistoryPlatform.YOUTUBE,
                            sourceUrl = work.webpageUrl,
                            title = work.title,
                            author = work.channelName,
                            coverUrl = work.thumbnailUrl,
                            successCount = 1,
                            failedCount = 0,
                            outputs = listOf(output),
                        )
                    }
                    _state.value = YouTubeImportState.Success(output.displayName)
                    onSaved()
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (e: Exception) {
                    AppLogger.e("YouTubeImport", "download failed id=${work.id}", e)
                    _state.value = YouTubeImportState.Error(
                        e.message?.takeIf { it.isNotBlank() } ?: "下载失败"
                    )
                }
            }
        }
        downloadJobs += job
        job.invokeOnCompletion {
            downloadJobs -= job
            downloadInProgress.set(false)
        }
    }

    // ── cancel / reset ──────────────────────────────────────────────────

    fun cancel() {
        parseJob?.cancel()
        parseJob = null
        downloadJobs.toList().forEach { it.cancel() }
        activeProcessIds.toList().forEach {
            kotlin.runCatching { YoutubeDL.getInstance().destroyProcessById(it) }
        }
        activeProcessIds.clear()
        val s = _state.value
        _state.value = when (s) {
            is YouTubeImportState.Downloading -> YouTubeImportState.Ready(s.work)
            is YouTubeImportState.Merging -> YouTubeImportState.Ready(s.work)
            YouTubeImportState.Initializing, YouTubeImportState.Parsing -> YouTubeImportState.Idle
            else -> s
        }
    }

    fun reset() {
        cancel()
        _state.value = YouTubeImportState.Idle
    }

    // ── download implementation ──────────────────────────────────────────

    private suspend fun downloadVideo(
        work: YouTubeWorkInfo,
        height: Int,
        codecMode: YouTubeCodecMode,
    ): ImportedMediaOutput {
        val tempDir = File(
            getApplication<Application>().cacheDir,
            "youtube_import/${work.id}_${System.nanoTime()}"
        )
        tempDir.mkdirs()
        return try {
            val formatSpec = buildFormatString(work, height, codecMode)
            // Cache-directory downloads mirror ytdlnis: select the merged artifact after execution.
            val downloadedOutput: File? = null
            val pid = "ytdl_${work.id}_${System.nanoTime()}"
            val authArgs = if (authManager.hasVisitorData()) {
                authManager.extractorArgs(work.id)
            } else null
            try {
                executeDownload(work, formatSpec, authArgs, tempDir, pid)
            } catch (error: Exception) {
                val detail = error.message.orEmpty()
                val formatUnavailable = detail.contains("Requested format is not available", true)
                val loginChallenge = detail.contains("Sign in", true) ||
                    detail.contains("confirm you're not a bot", true) ||
                    detail.contains("confirm you.re not a bot", true)
                if (!formatUnavailable && !(loginChallenge && authArgs != null)) {
                    throw error
                }
                tempDir.listFiles()?.forEach { it.delete() }
                try {
                    executeDownload(
                        work,
                        if (formatUnavailable) "bv*[height<=$height]+ba/b[height<=$height]/best" else formatSpec,
                        authArgs,
                        tempDir,
                        "${pid}_fallback",
                    )
                } catch (fallbackError: Exception) {
                    val fallbackDetail = fallbackError.message.orEmpty()
                    val fallbackFormatUnavailable = fallbackDetail.contains("Requested format is not available", true)
                    val fallbackLoginChallenge = fallbackDetail.contains("Sign in", true) ||
                        fallbackDetail.contains("confirm you're not a bot", true) ||
                        fallbackDetail.contains("confirm you.re not a bot", true)
                    if (authArgs == null || (!fallbackFormatUnavailable && !fallbackLoginChallenge)) {
                        throw fallbackError
                    }
                    tempDir.listFiles()?.forEach { it.delete() }
                    AppLogger.d("YouTubeImport", "retry download with cookies only id=${work.id}")
                    executeDownload(
                        work,
                        if (formatUnavailable) "bv*[height<=$height]+ba/b[height<=$height]/best" else formatSpec,
                        null,
                        tempDir,
                        "${pid}_fallback_plain",
                    )
                }
            }

            // ── find output file ──
            val files = tempDir.listFiles()
                ?.filter { it.isFile && it.name != "." && it.name != ".." }
                ?.sortedByDescending { it.lastModified() }
            val ignoredOutput = downloadedOutput?.takeIf { it.isFile && it.length() > 0L }
                ?: files?.firstOrNull {
                it.extension.equals("mp4", ignoreCase = true) &&
                    !it.name.endsWith(".part", ignoreCase = true) &&
                    !Regex(".*\\.f\\d+\\.mp4", RegexOption.IGNORE_CASE).matches(it.name)
                } ?: files?.firstOrNull {
                !it.name.endsWith(".part", ignoreCase = true) && it.length() > 0L
                }
                ?: throw YouTubeParseException("找不到下载的视频文件")
            val output = files?.firstOrNull {
                it.extension.equals("mp4", ignoreCase = true) &&
                    !it.name.endsWith(".part", ignoreCase = true) &&
                    !Regex(".*\\.f\\d+\\.mp4", RegexOption.IGNORE_CASE).matches(it.name)
            } ?: throw IllegalStateException("Download did not produce a merged MP4 file")
            if (output.length() <= 0L) {
                throw YouTubeParseException("下载文件为空")
            }

            validateMediaFile(output)

            // ── MediaStore ──
            val app = getApplication<Application>()
            val resolver = app.contentResolver
            val displayName = uniqueDisplayName(sanitizeFileName("${work.title}.mp4"))
            val relativePath = "${Environment.DIRECTORY_DCIM}/RCGallery/YouTube"

            var pendingUri: Uri? = null
            val result = try {
                pendingUri = resolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.IS_PENDING, if (Build.VERSION.SDK_INT >= 29) 1 else 0)
                        put(MediaStore.MediaColumns.SIZE, output.length())
                    }
                ) ?: throw IllegalStateException("无法创建本地媒体文件")

                val copiedBytes = resolver.openFileDescriptor(pendingUri, "rw")?.use { pfd ->
                    output.inputStream().use { inp ->
                        java.io.FileOutputStream(pfd.fileDescriptor).use { out ->
                            inp.copyTo(out, 256 * 1024)
                        }
                    }
                } ?: throw IllegalStateException("无法写入本地媒体文件")
                if (copiedBytes != output.length()) {
                    throw IllegalStateException("媒体文件写入不完整: $copiedBytes/${output.length()}")
                }

                if (Build.VERSION.SDK_INT >= 29) {
                    val published = resolver.update(
                        pendingUri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null, null,
                    )
                    if (published != 1) {
                        throw IllegalStateException("MediaStore 发布失败")
                    }
                }

                val publishedState = resolver.query(
                    pendingUri,
                    arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val pendingIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                    val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val pending = if (pendingIndex >= 0) cursor.getInt(pendingIndex) else 0
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
                    pending to size
                }
                if (publishedState == null || publishedState.first != 0 ||
                    publishedState.second != output.length()
                ) {
                    throw IllegalStateException("MediaStore 文件未正确发布")
                }
                val publishedUri = pendingUri
                pendingUri = null
                ImportedMediaOutput(displayName, publishedUri.toString())
            } finally {
                pendingUri?.let { resolver.delete(it, null, null) }
            }
            result
        } finally {
            tempDir.deleteRecursively()
            work.infoJsonPath?.let { File(it).delete() }
        }
    }

    private suspend fun executeDownload(
        work: YouTubeWorkInfo,
        formatSpec: String,
        authArgs: String?,
        tempDir: File,
        pid: String,
    ) {
        val cachedInfo = work.infoJsonPath?.let { path ->
            File(path).takeIf { it.isFile && it.length() > 128L }
        }
        val request = YoutubeDLRequest(work.webpageUrl).apply {
            addOption("-f", formatSpec)
            addOption("--merge-output-format", "mp4")
            addOption("--no-playlist")
            addOption("--no-part")
            addOption("--no-mtime")
            // Keep newline progress output enabled so the Android callback can update the UI.
            addOption("--newline")
            addOption("--no-warnings")
            addOption("--no-check-certificates")
            authArgs?.let { addOption("--extractor-args", "youtube:$it") }
            // YouTube CDN routes can stall on mobile networks. Match the parser's
            // IPv4 preference and allow yt-dlp enough time to recover a fragment.
            addOption("--force-ipv4")
            addOption("--retries", 10)
            addOption("--fragment-retries", 10)
            addOption("--retry-sleep", "exp=1:30")
            addOption("--socket-timeout", 90)
            addOption("-P", tempDir.absolutePath)
            addOption("-o", "%(title).100s.%(ext)s")
            cachedInfo?.let { file ->
                addOption("--load-info-json", file.absolutePath)
                AppLogger.d("YouTubeImport", "download using cached info json id=${work.id} bytes=${file.length()}")
            }
        }
        AppLogger.d(
            "YouTubeImport",
            "download request id=${work.id} source=${if (cachedInfo != null) "info-json" else "url"} format=$formatSpec"
        )
        activeProcessIds += pid
        try {
            runInterruptible {
                YoutubeDL.getInstance().execute(request, pid, false) { progress, _, line ->
                    val normalized = if (progress > 1f) progress / 100f else progress
                    _state.value = YouTubeImportState.Downloading(
                        work, normalized.coerceIn(0f, 1f), parseSpeed(line)
                    )
                }
            }
        } finally {
            activeProcessIds -= pid
        }
    }

    // ── format string (mirror of ytdlnis format selection) ────────────────

    private fun buildFormatString(
        work: YouTubeWorkInfo,
        height: Int,
        mode: YouTubeCodecMode,
    ): String {
        val candidates = work.videos.filter { it.height <= height }
            .ifEmpty { work.videos }
        val preferred = when (mode) {
            YouTubeCodecMode.COMPATIBLE -> candidates.filter { it.codec.startsWith("avc1", true) }
            YouTubeCodecMode.SPACE_SAVING -> candidates.filter {
                it.codec.startsWith("av01", true) || it.codec.startsWith("vp9", true) ||
                    it.codec.startsWith("hev", true) || it.codec.startsWith("hvc", true)
            }
            YouTubeCodecMode.AUTO -> candidates
        }.ifEmpty { candidates }
        val video = preferred.filter { it.formatId.matches(FORMAT_ID_REGEX) }
            .maxWithOrNull(compareBy({ it.height }, { it.bitrateKbps }))
            ?: return "best[height<=$height]/best"
        val audio = work.audios.filter { it.formatId.matches(FORMAT_ID_REGEX) }
            .maxByOrNull { it.bitrateKbps }
        val exact = if (audio != null) "${video.formatId}+${audio.formatId}" else video.formatId
        // The second branch handles a format list that changed between parse and download.
        return "$exact/bv*[height<=$height]+ba/b[height<=$height]/best"
    }

    // ── misc ──────────────────────────────────────────────────────────────

    private fun parseSpeed(line: String): String {
        val regex = Regex("""at\s+([\d.]+\s*[KMG]?i?B/s)""", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    private fun validateMediaFile(file: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO
            ) == "yes"
            val hasAudio = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
            ) == "yes"
            val duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            if (!hasVideo || !hasAudio || duration <= 0L) {
                throw YouTubeParseException(
                    when {
                        !hasVideo -> "下载文件缺少视频轨道"
                        !hasAudio -> "下载文件缺少音频轨道"
                        else -> "下载文件时长无效"
                    }
                )
            }
        } catch (e: YouTubeParseException) {
            throw e
        } catch (e: Exception) {
            throw YouTubeParseException("下载文件无法验证，可能未完成合并", e)
        } finally {
            retriever.release()
        }
    }

    private fun addHistory(work: YouTubeWorkInfo, displayName: String) {
        val now = System.currentTimeMillis()
        val entry = YouTubeDownloadHistory(
            id = now,
            title = work.title,
            webpageUrl = work.webpageUrl,
            displayName = displayName,
            cachedAt = now,
        )
        _history.update { current ->
            (listOf(entry) + current).take(MAX_HISTORY_SIZE).also(::saveHistory)
        }
    }

    private fun loadHistory(): List<YouTubeDownloadHistory> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        YouTubeDownloadHistory(
                            id = item.optLong("id"),
                            title = item.optString("title"),
                            webpageUrl = item.optString("url"),
                            displayName = item.optString("displayName"),
                            cachedAt = item.optLong("cachedAt"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveHistory(entries: List<YouTubeDownloadHistory>) {
        val array = JSONArray()
        entries.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("url", item.webpageUrl)
                    put("displayName", item.displayName)
                    put("cachedAt", item.cachedAt)
                }
            )
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun uniqueDisplayName(baseName: String): String {
        val resolver = getApplication<Application>().contentResolver
        var candidate = baseName
        val stem = baseName.substringBeforeLast('.', baseName)
        val ext = baseName.substringAfterLast('.', "mp4")
        var suffix = 1
        while (true) {
            val exists = resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(candidate, "${Environment.DIRECTORY_DCIM}/RCGallery/YouTube"),
                null,
            )?.use { it.moveToFirst() } == true
            if (!exists) return candidate
            candidate = "$stem ($suffix).$ext"
            suffix++
        }
    }

    private fun sanitizeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(180)
        .ifBlank { "youtube_video.mp4" }

    companion object {
        private const val KEY_COOKIE_PATH = "cookie_path"
        private const val KEY_HISTORY = "download_history"
        private const val MAX_HISTORY_SIZE = 100
        private val FORMAT_ID_REGEX = Regex("[0-9]+")
    }
}

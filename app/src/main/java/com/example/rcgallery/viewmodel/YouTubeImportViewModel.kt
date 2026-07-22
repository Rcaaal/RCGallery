package com.example.rcgallery.viewmodel

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rcgallery.data.youtube.YouTubeCodecMode
import com.example.rcgallery.data.youtube.YouTubeImportState
import com.example.rcgallery.data.youtube.YouTubeParseException
import com.example.rcgallery.data.youtube.YouTubeParser
import com.example.rcgallery.data.youtube.YouTubeWorkInfo
import com.example.rcgallery.util.AppLogger
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong

class YouTubeImportViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("youtube_prefs", 0)
    private var parser = YouTubeParser(application, loadCookiePath())
    private val _state = MutableStateFlow<YouTubeImportState>(YouTubeImportState.Idle)
    val state: StateFlow<YouTubeImportState> = _state.asStateFlow()

    private val _hasCookie = MutableStateFlow(loadCookiePath() != null)
    val hasCookie: StateFlow<Boolean> = _hasCookie.asStateFlow()

    private var parseJob: Job? = null
    private var downloadJob: Job? = null
    private var currentProcessId: String? = null

    // ── cookie ───────────────────────────────────────────────────────────

    fun setCookieFile(path: String) {
        prefs.edit().putString(KEY_COOKIE_PATH, path).apply()
        parser = YouTubeParser(getApplication(), path)
        _hasCookie.value = true
    }

    fun clearCookie() {
        loadCookiePath()?.let { File(it).delete() }
        prefs.edit().remove(KEY_COOKIE_PATH).apply()
        parser = YouTubeParser(getApplication(), null)
        _hasCookie.value = false
    }

    private fun loadCookiePath(): String? {
        val path = prefs.getString(KEY_COOKIE_PATH, null) ?: return null
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
        cancel()
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
        cancel()
        downloadJob = viewModelScope.launch {
            _state.value = YouTubeImportState.Downloading(work, 0f, "")
            try {
                val displayName = withContext(Dispatchers.IO) {
                    downloadVideo(work, height, codecMode)
                }
                _state.value = YouTubeImportState.Success(displayName)
                onSaved()
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Exception) {
                AppLogger.e("YouTubeImport", "download failed id=${work.id}", e)
                _state.value = YouTubeImportState.Error(
                    e.message?.takeIf { it.isNotBlank() } ?: "下载失败"
                )
            }
        }
    }

    // ── cancel / reset ──────────────────────────────────────────────────

    fun cancel() {
        parseJob?.cancel()
        parseJob = null
        downloadJob?.cancel()
        downloadJob = null
        currentProcessId?.let {
            kotlin.runCatching { YoutubeDL.getInstance().destroyProcessById(it) }
            currentProcessId = null
        }
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
    ): String {
        val tempDir = File(
            getApplication<Application>().cacheDir,
            "youtube_import/${work.id}_${System.nanoTime()}"
        )
        tempDir.mkdirs()
        return try {
            val formatSpec = buildFormatString(height, codecMode)
            val sortSpec = buildSortString(codecMode)

            val request = YoutubeDLRequest(work.webpageUrl).apply {
                addOption("-f", formatSpec)
                if (sortSpec.isNotBlank()) addOption("-S", sortSpec)
                addOption("--merge-output-format", "mp4")
                addOption("--no-playlist")
                addOption("--no-part")
                addOption("--no-mtime")
                addOption("--quiet")
                addOption("--no-warnings")
                addOption("--no-check-certificates")
                addOption("--no-check-formats")
                addOption("--ignore-errors")
                addOption("--retries", 3)
                addOption("--socket-timeout", 30)
                addOption("-P", tempDir.absolutePath)
                addOption("-o", "%(title).100s.%(ext)s")
            }

            val pid = "ytdl_${work.id}_${System.nanoTime()}"
            currentProcessId = pid

            val progressRegex = Regex("""([\d.]+)%""")
            runInterruptible {
                YoutubeDL.getInstance().execute(request, pid, false) { progress, _, line ->
                    val speed = parseSpeed(line)
                    _state.value = YouTubeImportState.Downloading(
                        work, progress.coerceIn(0f, 1f), speed
                    )
                }
            }

            // ── find output file ──
            val files = tempDir.listFiles()
                ?.filter { it.isFile && it.name != "." && it.name != ".." }
                ?.sortedByDescending { it.lastModified() }
            val output = files?.firstOrNull()
                ?: throw YouTubeParseException("找不到下载的视频文件")

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

                resolver.openFileDescriptor(pendingUri, "rw")?.use { pfd ->
                    output.inputStream().use { inp ->
                        java.io.FileOutputStream(pfd.fileDescriptor).use { out ->
                            inp.copyTo(out, 256 * 1024)
                        }
                    }
                } ?: throw IllegalStateException("无法写入本地媒体文件")

                if (Build.VERSION.SDK_INT >= 29) {
                    resolver.update(
                        pendingUri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null, null,
                    )
                }
                pendingUri = null
                displayName
            } finally {
                pendingUri?.let { resolver.delete(it, null, null) }
            }
            result
        } finally {
            tempDir.deleteRecursively()
            currentProcessId = null
        }
    }

    // ── format string (mirror of ytdlnis format selection) ────────────────

    private fun buildFormatString(height: Int, mode: YouTubeCodecMode): String {
        val baseVideo = "bv[height<=$height][ext=mp4]"
        val baseAudio = "ba[ext=m4a]"

        return when (mode) {
            YouTubeCodecMode.COMPATIBLE ->
                "$baseVideo[vcodec^=avc1]+$baseAudio/" +
                "$baseVideo+$baseAudio/" +
                "bv*+ba/b"
            YouTubeCodecMode.SPACE_SAVING ->
                "$baseVideo+$baseAudio/" +
                "bv*+ba/b"
            YouTubeCodecMode.AUTO ->
                "$baseVideo+$baseAudio/" +
                "bv*+ba/b"
        }
    }

    private fun buildSortString(mode: YouTubeCodecMode): String = when (mode) {
        YouTubeCodecMode.SPACE_SAVING ->
            "-vcodec:av01,+vcodec:hev1,+vcodec:hvc1,+vcodec:vp9"
        YouTubeCodecMode.COMPATIBLE ->
            "vcodec:avc1"
        YouTubeCodecMode.AUTO -> ""
    }

    // ── misc ──────────────────────────────────────────────────────────────

    private fun parseSpeed(line: String): String {
        val regex = Regex("""at\s+([\d.]+[KMG]?i?B/s])""")
        return regex.find(line)?.groupValues?.getOrNull(1)?.trim() ?: ""
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
    }
}

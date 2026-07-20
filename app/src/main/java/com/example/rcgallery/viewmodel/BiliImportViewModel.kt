package com.example.rcgallery.viewmodel

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.media.MediaCodecList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rcgallery.data.bilibili.BiliAudioTrack
import com.example.rcgallery.data.bilibili.BiliAuthRepository
import com.example.rcgallery.data.bilibili.BiliAuthState
import com.example.rcgallery.data.bilibili.BiliCredentialStore
import com.example.rcgallery.data.bilibili.BiliCodecMode
import com.example.rcgallery.data.bilibili.BiliImportState
import com.example.rcgallery.data.bilibili.BiliMuxer
import com.example.rcgallery.data.bilibili.BiliPage
import com.example.rcgallery.data.bilibili.BiliParseException
import com.example.rcgallery.data.bilibili.BiliParser
import com.example.rcgallery.data.bilibili.BiliQrPollResult
import com.example.rcgallery.data.bilibili.BiliVideoTrack
import com.example.rcgallery.data.bilibili.BiliWorkInfo
import com.example.rcgallery.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

class BiliImportViewModel(application: Application) : AndroidViewModel(application) {
    private val credentialStore = BiliCredentialStore(application)
    private val authRepository = BiliAuthRepository()
    private val parser = BiliParser { credentialStore.loadCookie() }
    private val _state = MutableStateFlow<BiliImportState>(BiliImportState.Idle)
    val state: StateFlow<BiliImportState> = _state.asStateFlow()
    private val _authState = MutableStateFlow<BiliAuthState>(BiliAuthState.LoggedOut)
    val authState: StateFlow<BiliAuthState> = _authState.asStateFlow()
    private var operationJob: Job? = null
    private var authJob: Job? = null
    private var lastInput: String = ""

    init {
        restoreAccount()
    }

    fun parse(text: String) {
        if (text.isBlank()) {
            _state.value = BiliImportState.Error("请粘贴哔哩哔哩视频链接、BV 号或 AV 号")
            return
        }
        lastInput = text
        cancel()
        operationJob = viewModelScope.launch {
            _state.value = BiliImportState.Parsing
            try {
                _state.value = BiliImportState.Ready(parser.parse(text))
            } catch (_: CancellationException) {
                return@launch
            } catch (error: BiliParseException) {
                _state.value = BiliImportState.Error(error.message ?: "解析失败")
            } catch (error: Exception) {
                _state.value = BiliImportState.Error(error.message?.takeIf(String::isNotBlank) ?: "网络请求失败")
            }
        }
    }

    fun startLogin() {
        authJob?.cancel()
        authJob = viewModelScope.launch {
            _authState.value = BiliAuthState.Loading
            try {
                val session = authRepository.generateQr()
                _authState.value = BiliAuthState.AwaitingScan(session.qrUrl, "请使用哔哩哔哩 App 扫码")
                while (true) {
                    delay(1_000L)
                    when (val result = authRepository.pollQr(session.qrKey)) {
                        BiliQrPollResult.Waiting -> {
                            _authState.value = BiliAuthState.AwaitingScan(session.qrUrl, "等待扫码")
                        }
                        BiliQrPollResult.Scanned -> {
                            _authState.value = BiliAuthState.AwaitingScan(session.qrUrl, "已扫码，请在手机上确认")
                        }
                        BiliQrPollResult.Expired -> {
                            _authState.value = BiliAuthState.Error("二维码已过期，请重新生成")
                            return@launch
                        }
                        is BiliQrPollResult.Success -> {
                            credentialStore.saveCookie(result.cookieHeader)
                            val account = authRepository.accountInfo(result.cookieHeader)
                            _authState.value = BiliAuthState.LoggedIn(account.userName, account.vipLabel)
                            if (lastInput.isNotBlank()) parse(lastInput)
                            return@launch
                        }
                    }
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Exception) {
                AppLogger.e("BiliAuth", "login failed", error)
                _authState.value = BiliAuthState.Error(error.message ?: "登录失败")
            }
        }
    }

    fun cancelLogin() {
        authJob?.cancel()
        authJob = null
        if (_authState.value !is BiliAuthState.LoggedIn) {
            _authState.value = BiliAuthState.LoggedOut
        }
    }

    fun logout() {
        authJob?.cancel()
        authJob = null
        credentialStore.clear()
        _authState.value = BiliAuthState.LoggedOut
        if (lastInput.isNotBlank()) parse(lastInput)
    }

    private fun restoreAccount() {
        val cookie = credentialStore.loadCookie() ?: return
        authJob = viewModelScope.launch {
            _authState.value = BiliAuthState.Loading
            try {
                val account = authRepository.accountInfo(cookie)
                _authState.value = BiliAuthState.LoggedIn(account.userName, account.vipLabel)
            } catch (error: Exception) {
                AppLogger.e("BiliAuth", "stored session invalid", error)
                credentialStore.clear()
                _authState.value = BiliAuthState.LoggedOut
            }
        }
    }

    fun download(
        work: BiliWorkInfo,
        selectedPageNumbers: Set<Int>,
        qualityId: Int,
        codecMode: BiliCodecMode,
        onSaved: () -> Unit,
    ) {
        val pages = work.pages.filter { it.pageNumber in selectedPageNumbers }
        if (pages.isEmpty()) {
            _state.value = BiliImportState.Error("请至少选择一个分P")
            return
        }
        cancel()
        operationJob = viewModelScope.launch {
            var saved = 0
            var failed = 0
            var firstName: String? = null
            var firstError: Throwable? = null
            try {
                pages.forEachIndexed { index, page ->
                    try {
                        val displayName = downloadPage(
                            work, page, qualityId, codecMode, index, pages.size
                        )
                        if (firstName == null) firstName = displayName
                        saved++
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        failed++
                        if (firstError == null) firstError = error
                        AppLogger.e(
                            "BiliImport",
                            "page failed bvid=${work.bvid} p=${page.pageNumber} title=${page.title}",
                            error,
                        )
                    }
                }
                _state.value = if (saved == 0) {
                    BiliImportState.Error(
                        firstError?.message?.takeIf { it.isNotBlank() }
                            ?.let { "下载失败：$it" }
                            ?: "下载失败，未保存任何文件"
                    )
                } else {
                    BiliImportState.Success(saved, failed, firstName)
                }
                if (saved > 0) onSaved()
            } catch (_: CancellationException) {
                return@launch
            }
        }
    }

    fun cancel() {
        val current = _state.value
        operationJob?.cancel()
        operationJob = null
        _state.value = when (current) {
            is BiliImportState.Downloading -> BiliImportState.Ready(current.work)
            BiliImportState.Parsing -> BiliImportState.Idle
            else -> current
        }
    }

    fun reset() {
        cancel()
        _state.value = BiliImportState.Idle
    }

    private suspend fun downloadPage(
        work: BiliWorkInfo,
        page: BiliPage,
        qualityId: Int,
        codecMode: BiliCodecMode,
        pageIndex: Int,
        pageCount: Int,
    ): String = withContext(Dispatchers.IO) {
        val tempDir = File(getApplication<Application>().cacheDir, "bili_import/${work.bvid}_${page.cid}")
        tempDir.mkdirs()
        val videoPart = File(tempDir, "video.m4s")
        val audioPart = File(tempDir, "audio.m4s")
        var pendingUri: Uri? = null
        try {
            AppLogger.d("BiliImport", "load streams bvid=${work.bvid} p=${page.pageNumber} qn=$qualityId")
            val streams = parser.loadStreams(work, page, qualityId)
            val video = chooseVideo(streams.videos, qualityId, codecMode)
            val audio = streams.audios.maxByOrNull { it.bandwidth }
                ?: throw IllegalStateException("没有可用的 AAC 音频轨道")
            AppLogger.d(
                "BiliImport",
                "selected mode=$codecMode video=${video.qualityId}/${video.codecs}/${video.bandwidth} " +
                    "audio=${audio.codecs}/${audio.bandwidth}",
            )
            downloadTrack(work, video.urls, videoPart, pageIndex, pageCount, "正在下载视频")
            downloadTrack(work, audio.urls, audioPart, pageIndex, pageCount, "正在下载音频")
            coroutineContext.ensureActive()

            _state.value = BiliImportState.Downloading(
                work, pageIndex + 1, pageCount, "正在合并音视频", 0L, 0L, 0L
            )
            val resolver = getApplication<Application>().contentResolver
            val displayName = uniqueDisplayName(buildDisplayName(work, page, video))
            pendingUri = resolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, BILIBILI_RELATIVE_PATH)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, work.publishTimeSeconds)
                    put(MediaStore.Video.Media.DATE_TAKEN, work.publishTimeSeconds * 1000L)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            ) ?: throw IllegalStateException("无法创建本地媒体文件")
            val uri = pendingUri
            resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                AppLogger.d(
                    "BiliImport",
                    "mux start video=${videoPart.length()} audio=${audioPart.length()} output=$displayName",
                )
                BiliMuxer.mux(videoPart, audioPart, descriptor.fileDescriptor)
                if (descriptor.statSize <= 0L) throw IllegalStateException("合并结果为空")
                AppLogger.d("BiliImport", "mux OK output=$displayName size=${descriptor.statSize}")
            } ?: throw IllegalStateException("无法写入本地媒体文件")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            pendingUri = null
            displayName
        } finally {
            pendingUri?.let { getApplication<Application>().contentResolver.delete(it, null, null) }
            videoPart.delete()
            audioPart.delete()
            tempDir.delete()
        }
    }

    private suspend fun downloadTrack(
        work: BiliWorkInfo,
        urls: List<String>,
        target: File,
        pageIndex: Int,
        pageCount: Int,
        stage: String,
    ) {
        var lastError: Throwable? = null
        for (url in urls) {
            coroutineContext.ensureActive()
            try {
                downloadTrackUrl(work, url, target, pageIndex, pageCount, stage)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
                target.delete()
            }
        }
        throw lastError ?: IllegalStateException("没有可用的下载地址")
    }

    private suspend fun downloadTrackUrl(
        work: BiliWorkInfo,
        url: String,
        target: File,
        pageIndex: Int,
        pageCount: Int,
        stage: String,
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", BiliParser.USER_AGENT)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Origin", "https://www.bilibili.com")
            setRequestProperty("Range", "bytes=0-")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            setRequestProperty("Connection", "keep-alive")
            setRequestProperty("Sec-Fetch-Site", "cross-site")
            setRequestProperty("Sec-Fetch-Mode", "cors")
            setRequestProperty("Sec-Fetch-Dest", "empty")
            credentialStore.loadCookie()?.takeIf { it.isNotBlank() }
                ?.let { setRequestProperty("Cookie", it) }
        }
        try {
            AppLogger.d("BiliImport", "$stage request host=${connection.url.host}")
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText().take(160) }
                }.getOrNull().orEmpty()
                AppLogger.e("BiliImport", "$stage rejected host=${connection.url.host} code=$code")
                throw IllegalStateException(
                    "CDN 下载失败（HTTP $code）" +
                        detail.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
                )
            }
            val total = connection.contentLengthLong
            AppLogger.d(
                "BiliImport",
                "$stage response=$code length=$total type=${connection.contentType} host=${connection.url.host}",
            )
            BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                BufferedOutputStream(target.outputStream(), BUFFER_SIZE).use { output ->
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
                            val seconds = (now - windowStart) / 1_000_000_000.0
                            val speed = if (seconds > 0) (windowBytes / seconds).toLong() else 0L
                            _state.value = BiliImportState.Downloading(
                                work, pageIndex + 1, pageCount, stage, downloaded, total, speed
                            )
                            windowStart = now
                            windowBytes = 0L
                            lastUpdate = now
                        }
                    }
                    output.flush()
                    if (downloaded <= 0L) throw IllegalStateException("下载结果为空")
                    if (total > 0L && downloaded != total) {
                        throw IllegalStateException("下载不完整（$downloaded/$total）")
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun chooseVideo(
        videos: List<BiliVideoTrack>,
        qualityId: Int,
        codecMode: BiliCodecMode,
    ): BiliVideoTrack {
        val qualityTracks = videos.filter { it.qualityId == qualityId }.ifEmpty {
            val bestQuality = videos.minByOrNull { BiliParser.qualityRank(it.qualityId) }?.qualityId
            videos.filter { it.qualityId == bestQuality }
        }
        val codecOrder = codecOrder(codecMode)
        return qualityTracks.sortedWith(
            compareBy<BiliVideoTrack> { codecRank(it.codecs, codecOrder) }
                .thenByDescending { it.bandwidth }
        ).firstOrNull() ?: throw IllegalStateException("没有可用的视频轨道")
    }

    private fun codecRank(codecs: String, order: List<Codec>): Int {
        val codec = when {
            codecs.startsWith("av01", true) -> Codec.AV1
            codecs.startsWith("hev", true) || codecs.startsWith("hvc", true) -> Codec.HEVC
            else -> Codec.AVC
        }
        return order.indexOf(codec).let { if (it >= 0) it else Int.MAX_VALUE }
    }

    private fun codecOrder(mode: BiliCodecMode): List<Codec> = when (mode) {
            BiliCodecMode.SPACE_SAVING -> listOf(Codec.AV1, Codec.HEVC, Codec.AVC)
            BiliCodecMode.COMPATIBLE -> listOf(Codec.AVC, Codec.HEVC, Codec.AV1)
            BiliCodecMode.AUTO -> automaticCodecOrder()
    }

    private fun automaticCodecOrder(): List<Codec> = when {
        hasHardwareDecoder("video/av01") -> listOf(Codec.AV1, Codec.HEVC, Codec.AVC)
        hasHardwareDecoder("video/hevc") -> listOf(Codec.HEVC, Codec.AVC, Codec.AV1)
        else -> listOf(Codec.AVC, Codec.HEVC, Codec.AV1)
    }

    private fun hasHardwareDecoder(mimeType: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.isHardwareAccelerated &&
                info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
    }.getOrDefault(false)

    private enum class Codec {
        AVC,
        HEVC,
        AV1,
    }

    private fun buildDisplayName(work: BiliWorkInfo, page: BiliPage, video: BiliVideoTrack): String {
        val pagePart = if (work.pages.size > 1) {
            " [P${page.pageNumber.toString().padStart(2, '0')}] ${page.title}"
        } else {
            ""
        }
        val quality = BiliParser.qualityLabel(video.qualityId)
        return sanitizeFileName("${work.title}$pagePart [$quality].mp4")
    }

    private fun sanitizeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(180)
        .ifBlank { "bilibili_video.mp4" }

    private fun uniqueDisplayName(baseName: String): String {
        val resolver = getApplication<Application>().contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        var candidate = baseName
        val stem = baseName.substringBeforeLast('.', baseName)
        val extension = baseName.substringAfterLast('.', "mp4")
        var suffix = 1
        while (true) {
            val exists = resolver.query(
                collection,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DISPLAY_NAME}=? AND ${MediaStore.Video.Media.RELATIVE_PATH}=?",
                arrayOf(candidate, BILIBILI_RELATIVE_PATH),
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
        const val BILIBILI_RELATIVE_PATH = "DCIM/RCGallery/Bilibili/"
    }
}

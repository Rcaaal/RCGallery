package com.example.rcgallery.data.bilibili

data class BiliPage(
    val cid: Long,
    val pageNumber: Int,
    val title: String,
    val durationSeconds: Int,
)

data class BiliQuality(
    val id: Int,
    val label: String,
    val width: Int,
    val height: Int,
)

enum class BiliCodecMode(val label: String, val description: String) {
    AUTO("自动", "按设备硬解能力选择"),
    SPACE_SAVING("节省空间", "优先 AV1 / HEVC"),
    COMPATIBLE("兼容优先", "优先 H.264"),
}

data class BiliWorkInfo(
    val bvid: String,
    val aid: Long,
    val title: String,
    val ownerName: String,
    val coverUrl: String?,
    val publishTimeSeconds: Long,
    val pages: List<BiliPage>,
    val qualities: List<BiliQuality>,
)

data class BiliVideoTrack(
    val qualityId: Int,
    val codecs: String,
    val width: Int,
    val height: Int,
    val frameRate: String,
    val bandwidth: Long,
    val urls: List<String>,
)

data class BiliAudioTrack(
    val codecs: String,
    val bandwidth: Long,
    val urls: List<String>,
)

data class BiliPageStreams(
    val videos: List<BiliVideoTrack>,
    val audios: List<BiliAudioTrack>,
    val qualityLabels: Map<Int, String>,
)

sealed interface BiliImportState {
    data object Idle : BiliImportState
    data object Parsing : BiliImportState
    data class Ready(val work: BiliWorkInfo) : BiliImportState
    data class Downloading(
        val work: BiliWorkInfo,
        val currentPage: Int,
        val pageCount: Int,
        val stage: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
    ) : BiliImportState
    data class Success(
        val savedCount: Int,
        val failedCount: Int,
        val firstDisplayName: String?,
    ) : BiliImportState
    data class Error(val message: String) : BiliImportState
}

class BiliParseException(message: String) : Exception(message)

sealed interface BiliAuthState {
    data object LoggedOut : BiliAuthState
    data object Loading : BiliAuthState
    data class AwaitingScan(
        val qrUrl: String,
        val status: String,
    ) : BiliAuthState
    data class LoggedIn(
        val userName: String,
        val vipLabel: String,
    ) : BiliAuthState
    data class Error(val message: String) : BiliAuthState
}

data class BiliQrSession(
    val qrUrl: String,
    val qrKey: String,
)

sealed interface BiliQrPollResult {
    data object Waiting : BiliQrPollResult
    data object Scanned : BiliQrPollResult
    data object Expired : BiliQrPollResult
    data class Success(val cookieHeader: String) : BiliQrPollResult
}

data class BiliAccountInfo(
    val userName: String,
    val vipLabel: String,
)

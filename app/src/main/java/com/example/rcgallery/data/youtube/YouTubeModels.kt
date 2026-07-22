package com.example.rcgallery.data.youtube

data class YouTubeQuality(val height: Int, val label: String)

enum class YouTubeCodecMode(val label: String, val description: String) {
    AUTO("自动", "按设备硬解能力选择"),
    SPACE_SAVING("节省空间", "优先 AV1 / HEVC"),
    COMPATIBLE("兼容优先", "优先 H.264"),
}

data class YouTubeVideoTrack(
    val formatId: String,
    val codec: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val estimatedBytes: Long,
)

data class YouTubeAudioTrack(
    val formatId: String,
    val codec: String,
    val bitrateKbps: Int,
    val estimatedBytes: Long,
)

data class YouTubeWorkInfo(
    val id: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String?,
    val durationSeconds: Int,
    val uploadDate: String?,
    val webpageUrl: String,
    val qualities: List<YouTubeQuality>,
    val videos: List<YouTubeVideoTrack>,
    val audios: List<YouTubeAudioTrack>,
)

sealed interface YouTubeImportState {
    data object Idle : YouTubeImportState
    data object Initializing : YouTubeImportState
    data object Parsing : YouTubeImportState
    data class Ready(val work: YouTubeWorkInfo) : YouTubeImportState
    data class Downloading(
        val work: YouTubeWorkInfo,
        val progress: Float,
        val speed: String,
    ) : YouTubeImportState
    data class Merging(val work: YouTubeWorkInfo) : YouTubeImportState
    data class Success(val displayName: String) : YouTubeImportState
    data class Error(val message: String) : YouTubeImportState
}

class YouTubeParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

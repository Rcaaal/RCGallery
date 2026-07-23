package com.example.rcgallery.data.douyin

data class DouyinWorkInfo(
    val workId: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val userAgent: String,
    val media: List<DouyinMediaResource>,
    val dynamicMediaStatus: DouyinDynamicMediaStatus = DouyinDynamicMediaStatus.NotChecked,
)

enum class DouyinDynamicMediaStatus {
    NotChecked,
    Available,
    None,
    LoginRequired,
    Failed,
}

sealed interface DouyinMediaResource {
    val index: Int
    val urls: List<String>

    data class Image(
        override val index: Int,
        override val urls: List<String>,
        val sourceKey: String? = null,
    ) : DouyinMediaResource

    data class AnimatedImage(
        override val index: Int,
        override val urls: List<String>,
        val animatedUrls: List<String>,
        val sourceKey: String? = null,
    ) : DouyinMediaResource

    data class Video(
        override val index: Int,
        override val urls: List<String>,
    ) : DouyinMediaResource
}

sealed interface DouyinImportState {
    data object Idle : DouyinImportState
    data object Parsing : DouyinImportState
    data class Ready(val work: DouyinWorkInfo) : DouyinImportState
    data class Downloading(
        val work: DouyinWorkInfo,
        val currentIndex: Int,
        val itemCount: Int,
        val completedCount: Int,
        val failedCount: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
    ) : DouyinImportState
    data class Success(
        val savedCount: Int,
        val failedCount: Int,
        val firstDisplayName: String?,
    ) : DouyinImportState
    data class Error(val message: String) : DouyinImportState
}

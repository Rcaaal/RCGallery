package com.example.rcgallery.data.baidu

data class BaiduToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long
)

data class BaiduCloudEntry(
    val fsId: Long,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAtMillis: Long,
    val category: Int,
    val thumbnailUrl: String = ""
) {
    val isVideo: Boolean get() = category == 1
    val isImage: Boolean get() = category == 3
    val isMedia: Boolean get() = isVideo || isImage
}

sealed interface BaiduBrowseState {
    data object SignedOut : BaiduBrowseState
    data class Loading(val message: String) : BaiduBrowseState
    data class Folder(
        val path: String,
        val entries: List<BaiduCloudEntry>
    ) : BaiduBrowseState
    data class Error(val message: String) : BaiduBrowseState
}

data class BaiduDownloadProgress(
    val fileName: String,
    val bytesCopied: Long,
    val totalBytes: Long
)

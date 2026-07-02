package com.example.rcgallery.model

import android.net.Uri

/**
 * 媒体项数据模型，对应 MediaStore 中的一条记录。
 */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val filePath: String = "",      // 文件绝对路径，用于直接 BitmapFactory.decodeFile()
    val thumbnailUri: Uri? = null,
    val fileName: String,
    val mimeType: String,
    val dateAdded: Long,
    val dateModified: Long,
    val size: Long,
    val albumId: String? = null,
    val albumName: String? = null,
    val duration: Long = 0L,
    val width: Int = 0,
    val height: Int = 0
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isGif: Boolean get() = mimeType.equals("image/gif", ignoreCase = true)
}

package com.example.rcgallery.model

/**
 * 回收站条目——记录被「快删」的文件元信息。
 * 文件本身留在原位（逻辑删除），通过此 JSON 索引管理。
 */
data class TrashEntry(
    val uri: String,                    // content:// URI，用于恢复/显示缩略图
    val filePath: String = "",          // 绝对路径，用于 info 显示
    val fileName: String,               // 文件名
    val deleteTime: Long,               // 删除时间戳（毫秒）
    val originalAlbumId: String? = null, // 来源相册 bucketId
    val originalAlbumName: String? = null, // 来源相册名
    val mimeType: String = ""           // MIME 类型
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

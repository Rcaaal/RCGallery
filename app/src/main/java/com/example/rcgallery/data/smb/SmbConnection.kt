package com.example.rcgallery.data.smb

/**
 * 已保存的 SMB 设备连接信息。
 */
data class SmbDevice(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    val host: String,
    val displayName: String = ""
)

/**
 * SMB 共享目录信息（对应 Windows 共享名）。
 */
data class SmbShare(
    val name: String,
    val path: String
)

/**
 * 文件夹（作为"子相册"展示）。
 */
data class SmbSubFolder(
    val name: String,
    val path: String,
    var coverPath: String = "",   // 该文件夹内第一张图片的路径
    var mediaCount: Int = 0       // 该文件夹直接包含的媒体文件数（不含递归子目录）
)

/**
 * SMB 媒体文件信息（只含图片/视频）。
 */
data class SmbFileInfo(
    val name: String,
    val path: String,
    val size: Long = 0L,
    val isVideo: Boolean = false
) {
    val isImage: Boolean get() = !isVideo
}

/**
 * SMB 浏览状态。
 */
sealed class SmbBrowseState {
    /** 设备列表 */
    data object DeviceList : SmbBrowseState()

    /** 正在连接 */
    data class Connecting(
        val host: String,
        val progressMessage: String = "正在连接..."
    ) : SmbBrowseState()

    /** 连接失败 */
    data class Error(val message: String) : SmbBrowseState()

    /** 共享列表（作为根级相册） */
    data class ShareList(
        val host: String,
        val shares: List<SmbShare>
    ) : SmbBrowseState()

    /** 文件夹内容（混合：子文件夹 + 直接媒体文件） */
    data class FolderContent(
        val host: String,
        val currentPath: String,
        val folderName: String,
        val subFolders: List<SmbSubFolder>,
        val mediaFiles: List<SmbFileInfo>
    ) : SmbBrowseState()
}

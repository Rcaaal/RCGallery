package com.example.rcgallery.data.smb

/**
 * SMB 文件操作历史记录。
 *
 * 每次在 SMB 文件中执行 COPY/MOVE 后生成一条记录，
 * 用于在"操作历史"页面展示，并支持导出。
 */
data class SmbFileOperationRecord(
    /** 唯一标识（UUID 前 8 位） */
    val id: String = java.util.UUID.randomUUID().toString().take(8),

    /** 操作完成时间（epoch ms） */
    val timestamp: Long = System.currentTimeMillis(),

    /** 操作模式："COPY" 或 "MOVE" */
    val mode: String,

    /** 源主机（如 "192.168.1.100"） */
    val sourceHost: String,

    /** 源文件夹 smb:// 完整路径 */
    val sourcePath: String,

    /** 源文件夹显示名称 */
    val sourceFolderName: String,

    /** 目标文件夹 smb:// 完整路径 */
    val targetPath: String,

    /** 目标主机（如 "192.168.1.100"） */
    val targetHost: String = "",

    /** 目标文件夹显示名称 */
    val targetFolderName: String,

    /** 尝试处理的文件总数 */
    val fileCount: Int,

    /** 成功处理的文件数 */
    val successCount: Int
)

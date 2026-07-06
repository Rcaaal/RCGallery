package com.example.rcgallery.model

import android.net.Uri

/**
 * 相册分组数据模型，对应 MediaStore 中的 Bucket。
 */
data class Album(
    val bucketId: String,
    val bucketName: String,
    val coverUri: Uri,
    val count: Int,
    val totalSize: Long = 0L,
    val imageCount: Int = 0,
    val videoCount: Int = 0,
    val gifCount: Int = 0,
    val directoryPath: String = "",
    val dateAdded: Long = 0L   // 相册中最新的文件创建时间（排序用）
)

package com.example.rcgallery.data

import android.net.Uri
import android.provider.MediaStore

/**
 * MediaStore 投影封装。
 * 将 Image/Video 两组投影的 URI、列数组和列索引统一到 sealed interface，
 * 消除 queryBuckets/queryMediaItems 的参数重复。
 */
sealed interface MediaProjection {
    val uri: Uri
    val projection: Array<String>
    val indexId: Int
    val indexName: Int
    val indexMime: Int
    val indexDateAdded: Int
    val indexDateModified: Int
    val indexSize: Int
    val indexBucketId: Int
    val indexBucketName: Int
    val indexWidth: Int
    val indexHeight: Int
    val indexData: Int
    val indexRelativePath: Int
    val indexDuration: Int  // 仅视频有，图片返回 -1
    val isVideo: Boolean

    data object Images : MediaProjection {
        override val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        override val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        override val indexId = 0
        override val indexName = 1
        override val indexMime = 2
        override val indexDateAdded = 3
        override val indexDateModified = 4
        override val indexSize = 5
        override val indexBucketId = 6
        override val indexBucketName = 7
        override val indexWidth = 8
        override val indexHeight = 9
        override val indexData = 10
        override val indexRelativePath = 11
        override val indexDuration = -1
        override val isVideo = false
    }

    data object Videos : MediaProjection {
        override val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        override val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.RELATIVE_PATH
        )
        override val indexId = 0
        override val indexName = 1
        override val indexMime = 2
        override val indexDateAdded = 3
        override val indexDateModified = 4
        override val indexSize = 5
        override val indexBucketId = 6
        override val indexBucketName = 7
        override val indexWidth = 8
        override val indexHeight = 9
        override val indexDuration = 10
        override val indexData = 11
        override val indexRelativePath = 12
        override val isVideo = true
    }
}

package com.example.rcgallery.data

import android.provider.MediaStore

/**
 * MediaStore 查询常量集合。
 */
object MediaStoreQuery {

    // ── URI ──
    val IMAGES_URI = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val VIDEOS_URI = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    // ── 图片投影 ──
    val IMAGE_PROJECTION = arrayOf(
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
    const val IMAGE_INDEX_ID = 0
    const val IMAGE_INDEX_NAME = 1
    const val IMAGE_INDEX_MIME = 2
    const val IMAGE_INDEX_DATE_ADDED = 3
    const val IMAGE_INDEX_DATE_MODIFIED = 4
    const val IMAGE_INDEX_SIZE = 5
    const val IMAGE_INDEX_BUCKET_ID = 6
    const val IMAGE_INDEX_BUCKET_NAME = 7
    const val IMAGE_INDEX_WIDTH = 8
    const val IMAGE_INDEX_HEIGHT = 9
    const val IMAGE_INDEX_DATA = 10
    const val IMAGE_INDEX_RELATIVE_PATH = 11

    // ── 视频投影 ──
    val VIDEO_PROJECTION = arrayOf(
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
    const val VIDEO_INDEX_ID = 0
    const val VIDEO_INDEX_NAME = 1
    const val VIDEO_INDEX_MIME = 2
    const val VIDEO_INDEX_DATE_ADDED = 3
    const val VIDEO_INDEX_DATE_MODIFIED = 4
    const val VIDEO_INDEX_SIZE = 5
    const val VIDEO_INDEX_BUCKET_ID = 6
    const val VIDEO_INDEX_BUCKET_NAME = 7
    const val VIDEO_INDEX_WIDTH = 8
    const val VIDEO_INDEX_HEIGHT = 9
    const val VIDEO_INDEX_DURATION = 10
    const val VIDEO_INDEX_DATA = 11
    const val VIDEO_INDEX_RELATIVE_PATH = 12

    // ── 相册分组排序（按最新时间降序） ──
    const val ALBUM_ORDER = "${MediaStore.Images.ImageColumns.BUCKET_ID} ASC, ${MediaStore.Images.ImageColumns.DATE_ADDED} DESC"
}

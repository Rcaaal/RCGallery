package com.example.rcgallery.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.example.rcgallery.model.Album
import com.example.rcgallery.model.MediaItem

/**
 * 媒体仓库 — 封装 MediaStore CRUD 操作。
 */
class MediaRepository(private val context: Context) {

    // ── 相册列表 ──

    /**
     * 加载所有相册（Bucket 分组），按最新时间降序，跳过数量为 0 的。
     */
    fun loadAlbums(): Result<List<Album>> = runCatching {
        val albumMap = LinkedHashMap<String, AlbumBuilder>()

        queryBuckets(MediaProjection.Images, albumMap)
        queryBuckets(MediaProjection.Videos, albumMap)

        albumMap.map { (_, b) ->
            Album(
                bucketId = b.bucketId,
                bucketName = b.bucketName,
                coverUri = b.coverUri,
                count = b.count,
                totalSize = b.totalSize,
                imageCount = b.imageCount,
                videoCount = b.videoCount,
                gifCount = b.gifCount,
                directoryPath = b.directoryPath,
                dateAdded = b.latestDate
            )
        }.sortedByDescending { it.count }
    }

    private fun queryBuckets(
        proj: MediaProjection,
        albumMap: MutableMap<String, AlbumBuilder>
    ) {
        val cursor = context.contentResolver.query(
            proj.uri, proj.projection, null, null, "date_added DESC"
        )
        cursor?.use { c ->
            while (c.moveToNext()) {
                val bucketId = c.getString(proj.indexBucketId) ?: continue
                val bucketName = c.getString(proj.indexBucketName) ?: "Unknown"
                val id = c.getLong(proj.indexId)
                val dateAdded = c.getLong(proj.indexDateAdded)
                val mimeType = c.getString(proj.indexMime) ?: ""
                val size = c.getLong(proj.indexSize)
                val filePath = c.getString(proj.indexData) ?: ""
                val displayName = c.getString(proj.indexName) ?: ""

                // Keep album totals aligned with the media list's visibility rules.
                if (displayName.startsWith(".")) continue
                if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) continue

                val imageUri = Uri.withAppendedPath(proj.uri, id.toString())

                val existing = albumMap[bucketId]
                if (existing != null) {
                    existing.count++
                    existing.totalSize += size
                    when {
                        proj.isVideo -> existing.videoCount++
                        mimeType.equals("image/gif", ignoreCase = true) -> existing.gifCount++
                        else -> existing.imageCount++
                    }
                    if (dateAdded > existing.latestDate) {
                        existing.latestDate = dateAdded
                        existing.coverUri = imageUri
                        if (filePath.isNotEmpty()) {
                            existing.directoryPath = filePath.substringBeforeLast("/")
                        }
                    }
                } else {
                    val dirPath = filePath.substringBeforeLast("/")
                    albumMap[bucketId] = AlbumBuilder(
                        bucketId = bucketId,
                        bucketName = bucketName,
                        coverUri = imageUri,
                        count = 1,
                        latestDate = dateAdded,
                        totalSize = size,
                        imageCount = if (proj.isVideo) 0 else if (mimeType.equals("image/gif", ignoreCase = true)) 0 else 1,
                        videoCount = if (proj.isVideo) 1 else 0,
                        gifCount = if (mimeType.equals("image/gif", ignoreCase = true)) 1 else 0,
                        directoryPath = dirPath
                    )
                }
            }
        }
    }

    private data class AlbumBuilder(
        val bucketId: String,
        val bucketName: String,
        var coverUri: Uri,
        var count: Int,
        var latestDate: Long,
        var totalSize: Long = 0L,
        var imageCount: Int = 0,
        var videoCount: Int = 0,
        var gifCount: Int = 0,
        var directoryPath: String = ""
    )

    // ── 媒体列表（分页） ──

    /**
     * 加载某个相册的媒体项，支持分页。
     * @param albumId 相册 bucketId，传入 null 则加载全部
     * @param pageSize 每页数量（默认 200）
     * @param offset 偏移量（从 0 开始）
     */
    fun loadMediaItems(
        albumId: String? = null,
        pageSize: Int = 200,
        offset: Int = 0
    ): Result<List<MediaItem>> = runCatching {
        val items = mutableListOf<MediaItem>()
        val selection = if (albumId != null) "bucket_id = ?" else null
        val selectionArgs = if (albumId != null) arrayOf(albumId) else null

        queryMediaItems(MediaProjection.Images, selection, selectionArgs, pageSize, offset, items)
        queryMediaItems(MediaProjection.Videos, selection, selectionArgs, pageSize, offset, items)

        items.sortedByDescending { it.dateAdded }
    }

    private fun queryMediaItems(
        proj: MediaProjection,
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int,
        offset: Int,
        items: MutableList<MediaItem>
    ) {
        val extras = Bundle().apply {
            if (selection != null) {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            }
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }
        val cursor = context.contentResolver.query(proj.uri, proj.projection, extras, null)
        cursor?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(proj.indexId)
                val displayName = c.getString(proj.indexName) ?: ""
                val mimeType = c.getString(proj.indexMime) ?: ""
                val dateAdded = c.getLong(proj.indexDateAdded)
                val dateModified = c.getLong(proj.indexDateModified)
                val size = c.getLong(proj.indexSize)
                val bucketId = c.getString(proj.indexBucketId)
                val bucketName = c.getString(proj.indexBucketName)
                val width = c.getInt(proj.indexWidth)
                val height = c.getInt(proj.indexHeight)
                val duration = if (proj.isVideo) c.getLong(proj.indexDuration) else 0L
                val filePath = c.getString(proj.indexData) ?: ""
                val relativePath = c.getString(proj.indexRelativePath) ?: ""

                if (displayName.startsWith(".")) continue
                if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) continue

                val mediaUri = ContentUriBuilder.getContentUri(id, proj.isVideo)

                items.add(
                    MediaItem(
                        id = id,
                        uri = mediaUri,
                        filePath = filePath,
                        fileName = displayName,
                        mimeType = mimeType,
                        dateAdded = dateAdded,
                        dateModified = dateModified,
                        size = size,
                        albumId = bucketId,
                        albumName = bucketName,
                        duration = duration,
                        width = width,
                        height = height,
                        relativePath = relativePath
                    )
                )
            }
        }
    }
    /**
     * 按文件路径列表加载媒体项（跨相册 TAG 搜索用）。
     * 每批最多 100 个路径，分批查询后合并。
     */
    fun loadMediaItemsByPaths(filePaths: List<String>): Result<List<MediaItem>> = runCatching {
        val items = mutableListOf<MediaItem>()
        val chunked = filePaths.chunked(100)
        for (chunk in chunked) {
            val placeholders = chunk.joinToString(",") { "?" }
            val selection = "${MediaStore.MediaColumns.DATA} IN ($placeholders)"
            val selectionArgs = chunk.toTypedArray()
            queryMediaItemsBySelection(MediaProjection.Images, selection, selectionArgs, items)
            queryMediaItemsBySelection(MediaProjection.Videos, selection, selectionArgs, items)
        }
        items.sortedByDescending { it.dateAdded }
    }

    private fun queryMediaItemsBySelection(
        proj: MediaProjection,
        selection: String,
        selectionArgs: Array<String>,
        items: MutableList<MediaItem>
    ) {
        val cursor = context.contentResolver.query(
            proj.uri, proj.projection, selection, selectionArgs, "date_added DESC"
        )
        cursor?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(proj.indexId)
                val displayName = c.getString(proj.indexName) ?: ""
                val mimeType = c.getString(proj.indexMime) ?: ""
                val dateAdded = c.getLong(proj.indexDateAdded)
                val dateModified = c.getLong(proj.indexDateModified)
                val size = c.getLong(proj.indexSize)
                val bucketId = c.getString(proj.indexBucketId)
                val bucketName = c.getString(proj.indexBucketName)
                val width = c.getInt(proj.indexWidth)
                val height = c.getInt(proj.indexHeight)
                val duration = if (proj.isVideo) c.getLong(proj.indexDuration) else 0L
                val filePath = c.getString(proj.indexData) ?: ""
                val relativePath = c.getString(proj.indexRelativePath) ?: ""

                if (displayName.startsWith(".")) continue
                if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) continue

                val mediaUri = ContentUriBuilder.getContentUri(id, proj.isVideo)

                items.add(
                    MediaItem(
                        id = id,
                        uri = mediaUri,
                        filePath = filePath,
                        fileName = displayName,
                        mimeType = mimeType,
                        dateAdded = dateAdded,
                        dateModified = dateModified,
                        size = size,
                        albumId = bucketId,
                        albumName = bucketName,
                        duration = duration,
                        width = width,
                        height = height,
                        relativePath = relativePath
                    )
                )
            }
        }
    }

    // ── 内部 URI 构建 ──

    private object ContentUriBuilder {
        fun getContentUri(id: Long, isVideo: Boolean): Uri {
            val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                       else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            return Uri.withAppendedPath(base, id.toString())
        }
    }
}

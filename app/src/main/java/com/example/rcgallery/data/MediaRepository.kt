package com.example.rcgallery.data

import android.content.Context
import android.net.Uri
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

        // 查询图片
        queryBuckets(
            uri = MediaStoreQuery.IMAGES_URI,
            projection = MediaStoreQuery.IMAGE_PROJECTION,
            idIndex = MediaStoreQuery.IMAGE_INDEX_ID,
            bucketIdIndex = MediaStoreQuery.IMAGE_INDEX_BUCKET_ID,
            bucketNameIndex = MediaStoreQuery.IMAGE_INDEX_BUCKET_NAME,
            dateAddedIndex = MediaStoreQuery.IMAGE_INDEX_DATE_ADDED,
            mimeTypeIndex = MediaStoreQuery.IMAGE_INDEX_MIME,
            sizeIndex = MediaStoreQuery.IMAGE_INDEX_SIZE,
            dataIndex = MediaStoreQuery.IMAGE_INDEX_DATA,
            isVideo = false,
            albumMap = albumMap
        )

        // 查询视频
        queryBuckets(
            uri = MediaStoreQuery.VIDEOS_URI,
            projection = MediaStoreQuery.VIDEO_PROJECTION,
            idIndex = MediaStoreQuery.VIDEO_INDEX_ID,
            bucketIdIndex = MediaStoreQuery.VIDEO_INDEX_BUCKET_ID,
            bucketNameIndex = MediaStoreQuery.VIDEO_INDEX_BUCKET_NAME,
            dateAddedIndex = MediaStoreQuery.VIDEO_INDEX_DATE_ADDED,
            mimeTypeIndex = MediaStoreQuery.VIDEO_INDEX_MIME,
            sizeIndex = MediaStoreQuery.VIDEO_INDEX_SIZE,
            dataIndex = MediaStoreQuery.VIDEO_INDEX_DATA,
            isVideo = true,
            albumMap = albumMap
        )

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
                directoryPath = b.directoryPath
            )
        }.sortedByDescending { it.count }
    }

    private fun queryBuckets(
        uri: Uri,
        projection: Array<String>,
        idIndex: Int,
        bucketIdIndex: Int,
        bucketNameIndex: Int,
        dateAddedIndex: Int,
        mimeTypeIndex: Int,
        sizeIndex: Int,
        dataIndex: Int,
        isVideo: Boolean,
        albumMap: MutableMap<String, AlbumBuilder>
    ) {
        val cursor = context.contentResolver.query(
            uri, projection, null, null, "date_added DESC"
        )
        cursor?.use { c ->
            while (c.moveToNext()) {
                val bucketId = c.getString(bucketIdIndex) ?: continue
                val bucketName = c.getString(bucketNameIndex) ?: "Unknown"
                val id = c.getLong(idIndex)
                val dateAdded = c.getLong(dateAddedIndex)
                val mimeType = c.getString(mimeTypeIndex) ?: ""
                val size = c.getLong(sizeIndex)
                val filePath = c.getString(dataIndex) ?: ""
                val imageUri = Uri.withAppendedPath(uri, id.toString())

                val existing = albumMap[bucketId]
                if (existing != null) {
                    existing.count++
                    existing.totalSize += size
                    when {
                        isVideo -> existing.videoCount++
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
                        imageCount = if (isVideo) 0 else if (mimeType.equals("image/gif", ignoreCase = true)) 0 else 1,
                        videoCount = if (isVideo) 1 else 0,
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
     * @param pageSize 每页数量（默认 60）
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
        val limit = "$pageSize OFFSET $offset"

        // 查询图片
        queryMediaItems(
            uri = MediaStoreQuery.IMAGES_URI,
            projection = MediaStoreQuery.IMAGE_PROJECTION,
            selection = selection,
            selectionArgs = selectionArgs,
            limit = limit,
            isVideo = false,
            items = items
        )

        // 查询视频
        queryMediaItems(
            uri = MediaStoreQuery.VIDEOS_URI,
            projection = MediaStoreQuery.VIDEO_PROJECTION,
            selection = selection,
            selectionArgs = selectionArgs,
            limit = limit,
            isVideo = true,
            items = items
        )

        // 按 dateAdded 降序合并
        items.sortedByDescending { it.dateAdded }
    }

    private fun queryMediaItems(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        limit: String,
        isVideo: Boolean,
        items: MutableList<MediaItem>
    ) {
        val cursor = context.contentResolver.query(
            uri, projection, selection, selectionArgs, "date_added DESC"
        )
        cursor?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(if (isVideo) MediaStoreQuery.VIDEO_INDEX_ID else MediaStoreQuery.IMAGE_INDEX_ID)
                val displayName = c.getString(if (isVideo) MediaStoreQuery.VIDEO_INDEX_NAME else MediaStoreQuery.IMAGE_INDEX_NAME) ?: ""
                val mimeType = c.getString(if (isVideo) MediaStoreQuery.VIDEO_INDEX_MIME else MediaStoreQuery.IMAGE_INDEX_MIME) ?: ""
                val dateAdded = c.getLong(if (isVideo) MediaStoreQuery.VIDEO_INDEX_DATE_ADDED else MediaStoreQuery.IMAGE_INDEX_DATE_ADDED)
                val dateModified = c.getLong(if (isVideo) MediaStoreQuery.VIDEO_INDEX_DATE_MODIFIED else MediaStoreQuery.IMAGE_INDEX_DATE_MODIFIED)
                val size = c.getLong(if (isVideo) MediaStoreQuery.VIDEO_INDEX_SIZE else MediaStoreQuery.IMAGE_INDEX_SIZE)
                val bucketId = c.getString(if (isVideo) MediaStoreQuery.VIDEO_INDEX_BUCKET_ID else MediaStoreQuery.IMAGE_INDEX_BUCKET_ID)
                val bucketName = c.getString(if (isVideo) MediaStoreQuery.VIDEO_INDEX_BUCKET_NAME else MediaStoreQuery.IMAGE_INDEX_BUCKET_NAME)
                val width = c.getInt(if (isVideo) MediaStoreQuery.VIDEO_INDEX_WIDTH else MediaStoreQuery.IMAGE_INDEX_WIDTH)
                val height = c.getInt(if (isVideo) MediaStoreQuery.VIDEO_INDEX_HEIGHT else MediaStoreQuery.IMAGE_INDEX_HEIGHT)
                val duration = if (isVideo) c.getLong(MediaStoreQuery.VIDEO_INDEX_DURATION) else 0L
                val filePath = c.getString(if (isVideo) MediaStoreQuery.VIDEO_INDEX_DATA else MediaStoreQuery.IMAGE_INDEX_DATA) ?: ""

                // 过滤无用格式（如 .nomedia）
                if (displayName.startsWith(".")) continue
                if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) continue

                val mediaUri = ContentUriBuilder.getContentUri(id, isVideo)
                val thumbnailUri = ThumbnailUriBuilder.getThumbnailUri(id, isVideo)

                items.add(
                    MediaItem(
                        id = id,
                        uri = mediaUri,
                        filePath = filePath,
                        thumbnailUri = thumbnailUri,
                        fileName = displayName,
                        mimeType = mimeType,
                        dateAdded = dateAdded,
                        dateModified = dateModified,
                        size = size,
                        albumId = bucketId,
                        albumName = bucketName,
                        duration = duration,
                        width = width,
                        height = height
                    )
                )
            }
        }
    }

    // ── 删除 ──

    /**
     * 删除指定的媒体文件（同步 MediaStore）。
     */
    fun deleteMediaItems(uris: List<Uri>): Result<Unit> = runCatching {
        for (uri in uris) {
            context.contentResolver.delete(uri, null, null)
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

    private object ThumbnailUriBuilder {
        fun getThumbnailUri(id: Long, isVideo: Boolean): Uri? {
            // Coil 可以直接用 content://media/external/images/media/{id} 自己生成缩略图，
            // 这里返回 null 让 Coil 自动处理
            return null
        }
    }
}

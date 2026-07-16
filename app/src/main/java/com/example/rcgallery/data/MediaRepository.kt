package com.example.rcgallery.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.example.rcgallery.model.Album
import com.example.rcgallery.model.MediaItem
import java.io.File
import java.util.Locale

/**
 * 媒体仓库 — 封装 MediaStore CRUD 操作。
 */
class MediaRepository(private val context: Context) {

    /** Build album rows for folders that intentionally no longer exist in MediaStore. */
    fun loadFileSystemAlbums(directoryPaths: Set<String>): Result<List<Album>> = runCatching {
        directoryPaths.mapNotNull { path ->
            val directory = File(path)
            val items = mediaFiles(directory).map { fileToMediaItem(it, directory) }
            if (items.isEmpty()) return@mapNotNull null
            val cover = items.maxByOrNull { it.dateAdded } ?: return@mapNotNull null
            Album(
                bucketId = bucketIdForDirectory(directory),
                bucketName = directory.name,
                coverUri = cover.uri,
                count = items.size,
                totalSize = items.sumOf { it.size },
                imageCount = items.count { it.isImage && !it.isGif },
                videoCount = items.count { it.isVideo },
                gifCount = items.count { it.isGif },
                directoryPath = canonicalPath(directory),
                dateAdded = cover.dateAdded
            )
        }
    }

    fun loadMediaItemsFromDirectory(directoryPath: String): Result<List<MediaItem>> = runCatching {
        val directory = File(directoryPath)
        mediaFiles(directory)
            .map { fileToMediaItem(it, directory) }
            .sortedByDescending { it.dateAdded }
    }

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

    /** Load one globally date-sorted page containing both images and videos. */
    fun loadAllMediaPage(pageSize: Int, offset: Int): Result<List<MediaItem>> = runCatching {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.Video.Media.DURATION,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val extras = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
                )
            )
            // Use an explicit SQL order here. Some MediaStore Files providers do not
            // reliably honor multi-column QUERY_ARG_SORT_COLUMNS, which can make the
            // first page contain old media and insert newer rows above it later.
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.Files.FileColumns._ID} DESC"
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, pageSize)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }
        val result = ArrayList<MediaItem>(pageSize)
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            projection,
            extras,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val mediaType = cursor.getInt(1)
                val displayName = cursor.getString(2) ?: ""
                val mimeType = cursor.getString(3) ?: ""
                if (displayName.startsWith(".")) continue
                if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) continue
                val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                result += MediaItem(
                    id = id,
                    uri = ContentUriBuilder.getContentUri(id, isVideo),
                    filePath = cursor.getString(12) ?: "",
                    fileName = displayName,
                    mimeType = mimeType,
                    dateAdded = cursor.getLong(4),
                    dateModified = cursor.getLong(5),
                    size = cursor.getLong(6),
                    albumId = cursor.getString(7),
                    albumName = cursor.getString(8),
                    width = cursor.getInt(9),
                    height = cursor.getInt(10),
                    duration = if (isVideo) cursor.getLong(11) else 0L,
                    relativePath = cursor.getString(13) ?: ""
                )
            }
        }
        result
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
        // Files under a .nomedia directory are intentionally absent from MediaStore.
        val foundPaths = items.mapTo(HashSet()) { canonicalPath(File(it.filePath)) }
        filePaths.forEach { path ->
            val file = File(path)
            if (file.isFile && canonicalPath(file) !in foundPaths) {
                fileToMediaItemOrNull(file)?.let(items::add)
            }
        }
        items.sortedByDescending { it.dateAdded }
    }

    private fun mediaFiles(directory: File): List<File> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles().orEmpty().filter { file ->
            file.isFile && !file.name.startsWith(".") && resolveMimeType(file) != null
        }
    }

    private fun fileToMediaItemOrNull(file: File): MediaItem? {
        val parent = file.parentFile ?: return null
        return resolveMimeType(file)?.let { fileToMediaItem(file, parent, it) }
    }

    private fun fileToMediaItem(file: File, directory: File): MediaItem {
        val mimeType = resolveMimeType(file)
            ?: throw IllegalArgumentException("Unsupported media file: ${file.absolutePath}")
        return fileToMediaItem(file, directory, mimeType)
    }

    private fun fileToMediaItem(file: File, directory: File, mimeType: String): MediaItem {
        val path = canonicalPath(file)
        val directoryPath = canonicalPath(directory)
        val modifiedSeconds = file.lastModified().coerceAtLeast(0L) / 1000L
        val externalRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd(File.separatorChar)
        val relativeDirectory = directoryPath.removePrefix(externalRoot).trimStart(File.separatorChar)
        return MediaItem(
            id = path.lowercase(Locale.ROOT).hashCode().toLong(),
            uri = Uri.fromFile(file),
            filePath = path,
            fileName = file.name,
            mimeType = mimeType,
            dateAdded = modifiedSeconds,
            dateModified = modifiedSeconds,
            size = file.length(),
            albumId = bucketIdForDirectory(directory),
            albumName = directory.name,
            relativePath = if (relativeDirectory.isEmpty()) "" else "$relativeDirectory/"
        )
    }

    private fun resolveMimeType(file: File): String? {
        val extension = file.extension.lowercase(Locale.ROOT)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        if (mime?.startsWith("image/") == true || mime?.startsWith("video/") == true) return mime
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heif"
            "avif" -> "image/avif"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            else -> null
        }
    }

    private fun bucketIdForDirectory(directory: File): String =
        canonicalPath(directory).lowercase(Locale.ROOT).hashCode().toString()

    private fun canonicalPath(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

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

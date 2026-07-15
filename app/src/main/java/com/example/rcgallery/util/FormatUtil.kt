package com.example.rcgallery.util

/**
 * 通用格式化工具函数。
 */
object FormatUtil {

    /** Shorten shared-storage paths for display without changing the real path. */
    fun formatDisplayPath(path: String): String = when {
        path.equals("/sdcard", ignoreCase = true) -> "/0"
        path.startsWith("/sdcard/", ignoreCase = true) -> "/0/${path.substring(8)}"
        path.equals("/storage/emulated", ignoreCase = true) -> "/"
        path.startsWith("/storage/emulated/", ignoreCase = true) ->
            path.substring("/storage/emulated".length)
        else -> path
    }

    /**
     * 格式化文件大小（B / KB / MB / GB）。
     */
    fun formatFileSize(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024f)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
        else -> "%.2f GB".format(bytes / (1024f * 1024f * 1024f))
    }
}

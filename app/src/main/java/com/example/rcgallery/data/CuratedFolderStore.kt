package com.example.rcgallery.data

import android.content.Context
import android.os.Environment
import java.io.File

/** 迁移目录选择器的精选快捷入口；只保存 UI 路径，不操作真实文件夹。 */
class CuratedFolderStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<String> {
        if (!prefs.contains(KEY_PATHS)) return defaultPaths()
        return prefs.getStringSet(KEY_PATHS, emptySet()).orEmpty()
            .map(::canonicalPath)
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun save(paths: Collection<String>): List<String> {
        val normalized = paths.map(::canonicalPath)
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        prefs.edit().putStringSet(KEY_PATHS, normalized.toSet()).apply()
        return normalized
    }

    fun reset(): List<String> = save(defaultPaths())

    private fun defaultPaths(): List<String> {
        val root = Environment.getExternalStorageDirectory()
        return DEFAULT_NAMES.map { canonicalPath(File(root, it).path) }
    }

    private fun canonicalPath(path: String): String =
        runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }

    private companion object {
        const val PREFS_NAME = "rcgallery_prefs"
        const val KEY_PATHS = "curated_migration_folders"
        val DEFAULT_NAMES = listOf(
            "Bilibili",
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_PICTURES
        )
    }
}

package com.example.rcgallery.data

import android.content.Context
import com.example.rcgallery.data.db.AppDatabase
import com.example.rcgallery.data.db.MediaImportHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

enum class MediaImportHistoryPlatform(val label: String) {
    DOUYIN("抖音"),
    BILIBILI("哔哩哔哩"),
    YOUTUBE("YouTube"),
    X("X"),
}

data class ImportedMediaOutput(
    val displayName: String,
    val uri: String? = null,
)

data class MediaImportHistoryItem(
    val id: Long,
    val platform: MediaImportHistoryPlatform,
    val sourceUrl: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val downloadedAt: Long,
    val successCount: Int,
    val failedCount: Int,
    val outputs: List<ImportedMediaOutput>,
)

class MediaImportHistoryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(appContext).mediaImportHistoryDao()
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun observeAll(): Flow<List<MediaImportHistoryItem>> = dao.observeAll().map { entries ->
        entries.mapNotNull(::toItem)
    }

    suspend fun record(
        platform: MediaImportHistoryPlatform,
        sourceUrl: String,
        title: String,
        author: String?,
        coverUrl: String?,
        successCount: Int,
        failedCount: Int,
        outputs: List<ImportedMediaOutput>,
    ) {
        dao.insert(
            MediaImportHistoryEntity(
                platform = platform.name,
                sourceUrl = sourceUrl,
                title = title,
                author = author,
                coverUrl = coverUrl,
                downloadedAt = System.currentTimeMillis(),
                successCount = successCount,
                failedCount = failedCount,
                outputsJson = outputsToJson(outputs),
            )
        )
    }

    suspend fun delete(ids: Collection<Long>) {
        if (ids.isNotEmpty()) dao.deleteByIds(ids.toList())
    }

    suspend fun clear() = dao.clear()

    /** One-time import keeps the YouTube history created by earlier APK versions visible. */
    suspend fun migrateLegacyYoutubeHistoryIfNeeded() {
        if (prefs.getBoolean(KEY_LEGACY_YOUTUBE_MIGRATED, false)) return
        val legacyPrefs = appContext.getSharedPreferences(LEGACY_YOUTUBE_PREFS, Context.MODE_PRIVATE)
        val raw = legacyPrefs.getString(LEGACY_YOUTUBE_HISTORY, null)
        if (raw != null) {
            runCatching {
                val records = JSONArray(raw)
                for (index in 0 until records.length()) {
                    val value = records.optJSONObject(index) ?: continue
                    dao.insert(
                        MediaImportHistoryEntity(
                            platform = MediaImportHistoryPlatform.YOUTUBE.name,
                            sourceUrl = value.optString("url"),
                            title = value.optString("title"),
                            author = null,
                            coverUrl = null,
                            downloadedAt = value.optLong("cachedAt", System.currentTimeMillis()),
                            successCount = 1,
                            failedCount = 0,
                            outputsJson = outputsToJson(
                                listOf(ImportedMediaOutput(value.optString("displayName")))
                            ),
                        )
                    )
                }
            }
        }
        prefs.edit().putBoolean(KEY_LEGACY_YOUTUBE_MIGRATED, true).apply()
    }

    private fun toItem(entity: MediaImportHistoryEntity): MediaImportHistoryItem? = runCatching {
        MediaImportHistoryItem(
            id = entity.id,
            platform = MediaImportHistoryPlatform.valueOf(entity.platform),
            sourceUrl = entity.sourceUrl,
            title = entity.title,
            author = entity.author,
            coverUrl = entity.coverUrl,
            downloadedAt = entity.downloadedAt,
            successCount = entity.successCount,
            failedCount = entity.failedCount,
            outputs = outputsFromJson(entity.outputsJson),
        )
    }.getOrNull()

    private fun outputsToJson(outputs: List<ImportedMediaOutput>): String = JSONArray().apply {
        outputs.forEach { output ->
            put(JSONObject().apply {
                put("displayName", output.displayName)
                output.uri?.let { put("uri", it) }
            })
        }
    }.toString()

    private fun outputsFromJson(raw: String): List<ImportedMediaOutput> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val value = array.optJSONObject(index) ?: continue
                add(ImportedMediaOutput(value.optString("displayName"), value.optString("uri").ifBlank { null }))
            }
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val PREFS_NAME = "media_import_history_prefs"
        const val KEY_LEGACY_YOUTUBE_MIGRATED = "legacy_youtube_migrated"
        const val LEGACY_YOUTUBE_PREFS = "youtube_prefs"
        const val LEGACY_YOUTUBE_HISTORY = "download_history"
    }
}

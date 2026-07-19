package com.example.rcgallery.data

import android.content.Context
import com.example.rcgallery.data.db.AppDatabase
import com.example.rcgallery.data.db.WatchLaterEntity
import com.example.rcgallery.model.MediaItem
import kotlinx.coroutines.flow.Flow

class WatchLaterRepository(context: Context) {
    private val dao = AppDatabase.getInstance(context).watchLaterDao()

    fun observeAll(): Flow<List<WatchLaterEntity>> = dao.observeAll()

    suspend fun add(items: List<MediaItem>) {
        dao.insertAll(items.distinctBy { it.filePath }.map { item ->
            WatchLaterEntity(
                targetKey = item.filePath,
                mediaUri = item.uri.toString(),
                mediaType = if (item.isVideo) WatchLaterEntity.TYPE_VIDEO else WatchLaterEntity.TYPE_IMAGE
            )
        })
    }

    suspend fun remove(targetKeys: List<String>) {
        if (targetKeys.isNotEmpty()) dao.deleteByKeys(targetKeys)
    }

    suspend fun remove(targetKey: String) = dao.deleteByKey(targetKey)

    suspend fun markWatched(targetKey: String) {
        dao.markWatched(targetKey, System.currentTimeMillis())
    }

    suspend fun updateTarget(oldKey: String, newKey: String, newUri: String) {
        if (oldKey != newKey) dao.updateTarget(oldKey, newKey, newUri)
    }
}

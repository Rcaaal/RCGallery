package com.example.rcgallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchLaterDao {
    @Query("SELECT * FROM watch_later ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<WatchLaterEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<WatchLaterEntity>)

    @Query("DELETE FROM watch_later WHERE targetKey IN (:targetKeys)")
    suspend fun deleteByKeys(targetKeys: List<String>)

    @Query("DELETE FROM watch_later WHERE targetKey = :targetKey")
    suspend fun deleteByKey(targetKey: String)

    @Query("UPDATE watch_later SET watchedAt = :watchedAt WHERE targetKey = :targetKey AND mediaType = 2 AND watchedAt IS NULL")
    suspend fun markWatched(targetKey: String, watchedAt: Long)

    @Query("UPDATE OR IGNORE watch_later SET targetKey = :newKey, mediaUri = :newUri WHERE targetKey = :oldKey")
    suspend fun updateTarget(oldKey: String, newKey: String, newUri: String)
}

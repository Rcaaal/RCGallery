package com.example.rcgallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaImportHistoryDao {
    @Query("SELECT * FROM media_import_history ORDER BY downloadedAt DESC, id DESC")
    fun observeAll(): Flow<List<MediaImportHistoryEntity>>

    @Insert
    suspend fun insert(entity: MediaImportHistoryEntity): Long

    @Query("DELETE FROM media_import_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM media_import_history")
    suspend fun clear()
}

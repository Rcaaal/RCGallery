package com.example.rcgallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SystemAlbumRuleDao {
    @Query("SELECT * FROM system_album_rules ORDER BY createdAt ASC")
    fun getAllFlow(): Flow<List<SystemAlbumRuleEntity>>

    @Query("SELECT * FROM system_album_rules WHERE directoryPath = :directoryPath LIMIT 1")
    suspend fun getByPath(directoryPath: String): SystemAlbumRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: SystemAlbumRuleEntity)

    @Query("DELETE FROM system_album_rules WHERE directoryPath = :directoryPath")
    suspend fun deleteByPath(directoryPath: String)
}

package com.example.rcgallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HierarchySystemDao {
    @Query("SELECT * FROM parent_hid_rules ORDER BY createdAt ASC")
    fun getParentHidRulesFlow(): Flow<List<ParentHidRuleEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM parent_hid_rules WHERE parentId = :parentId)")
    suspend fun isParentHidEnabled(parentId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParentHidRule(rule: ParentHidRuleEntity)

    @Query("DELETE FROM parent_hid_rules WHERE parentId = :parentId")
    suspend fun deleteParentHidRule(parentId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHideSource(source: SystemAlbumHideSourceEntity)

    @Query("SELECT * FROM system_album_hide_sources WHERE directoryPath = :path")
    suspend fun getHideSources(path: String): List<SystemAlbumHideSourceEntity>

    @Query("SELECT COUNT(*) FROM system_album_hide_sources WHERE directoryPath = :path")
    suspend fun countHideSources(path: String): Int

    @Query("DELETE FROM system_album_hide_sources WHERE directoryPath = :path AND sourceType = :type AND sourceId = :sourceId")
    suspend fun deleteHideSource(path: String, type: String, sourceId: String)

    @Query("DELETE FROM system_album_hide_sources WHERE directoryPath = :path")
    suspend fun deleteAllHideSources(path: String)

    @Query("DELETE FROM system_album_hide_sources WHERE sourceType = 'PARENT' AND sourceId = :parentId")
    suspend fun deleteParentHideSources(parentId: String)

    @Query("SELECT * FROM system_album_hide_sources WHERE sourceType = 'PARENT' AND sourceId = :parentId")
    suspend fun getParentHideSources(parentId: String): List<SystemAlbumHideSourceEntity>

    @Query("UPDATE system_album_hide_sources SET directoryPath = :newPath WHERE directoryPath = :oldPath")
    suspend fun replaceHideSourcePath(oldPath: String, newPath: String)

    @Query("SELECT * FROM parent_migration_states ORDER BY updatedAt DESC")
    fun getMigrationStatesFlow(): Flow<List<ParentMigrationStateEntity>>

    @Query("SELECT * FROM parent_migration_states WHERE parentId = :parentId LIMIT 1")
    suspend fun getMigrationState(parentId: Long): ParentMigrationStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMigrationState(state: ParentMigrationStateEntity)

    @Query("DELETE FROM parent_migration_states WHERE parentId = :parentId")
    suspend fun deleteMigrationState(parentId: Long)
}

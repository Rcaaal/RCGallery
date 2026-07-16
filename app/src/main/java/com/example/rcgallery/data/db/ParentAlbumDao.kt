package com.example.rcgallery.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ParentAlbumDao {

    // ── 父级 CRUD ──

    @Query("SELECT * FROM parent_albums ORDER BY createdAt ASC")
    fun getAllParents(): Flow<List<ParentAlbumEntity>>

    @Query("SELECT * FROM parent_albums ORDER BY createdAt ASC")
    suspend fun getAllParentsOnce(): List<ParentAlbumEntity>

    @Query("SELECT * FROM parent_albums WHERE id = :id")
    suspend fun getParentById(id: Long): ParentAlbumEntity?

    @Query("SELECT * FROM parent_albums WHERE name = :name LIMIT 1")
    suspend fun getParentByName(name: String): ParentAlbumEntity?

    @Query("SELECT COUNT(*) FROM parent_albums WHERE name = :name")
    suspend fun countByName(name: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertParent(parent: ParentAlbumEntity): Long

    @Update
    suspend fun updateParent(parent: ParentAlbumEntity)

    @Delete
    suspend fun deleteParent(parent: ParentAlbumEntity)

    @Query("DELETE FROM parent_albums WHERE id = :id")
    suspend fun deleteParentById(id: Long)

    @Query("UPDATE parent_albums SET name = :newName WHERE id = :id")
    suspend fun renameParent(id: Long, newName: String)

    // ── 子级关系 CRUD ──

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun addChild(child: ParentChildEntity): Long

    @Delete
    suspend fun removeChild(child: ParentChildEntity)

    @Query("DELETE FROM parent_children WHERE childBucketId = :childBucketId AND parentId = :parentId")
    suspend fun removeChildByBucketId(parentId: Long, childBucketId: String)

    @Query("DELETE FROM parent_children WHERE parentId = :parentId")
    suspend fun removeAllChildren(parentId: Long)

    @Query("SELECT * FROM parent_children WHERE parentId = :parentId ORDER BY orderIndex ASC")
    suspend fun getChildrenForParent(parentId: Long): List<ParentChildEntity>

    @Query("SELECT * FROM parent_children ORDER BY parentId, orderIndex ASC")
    fun getAllChildrenFlow(): Flow<List<ParentChildEntity>>

    @Query("SELECT * FROM parent_children ORDER BY parentId, orderIndex ASC")
    suspend fun getAllChildrenOnce(): List<ParentChildEntity>

    @Query("SELECT childBucketId FROM parent_children WHERE parentId = :parentId ORDER BY orderIndex ASC")
    suspend fun getChildBucketIdsForParent(parentId: Long): List<String>

    /** 获取所有子相册的 bucketId（用于根层过滤） */
    @Query("SELECT childBucketId FROM parent_children")
    suspend fun getAllChildBucketIds(): List<String>

    /** 获取某个子相册所属的父级 ID（null = 不属于任何父级） */
    @Query("SELECT parentId FROM parent_children WHERE childBucketId = :childBucketId LIMIT 1")
    suspend fun getParentIdForChild(childBucketId: String): Long?

    /** 相册移动到新目录后同步 MediaStore bucketId，保留父级归属。 */
    @Query("UPDATE parent_children SET childBucketId = :newBucketId WHERE childBucketId = :oldBucketId")
    suspend fun replaceChildBucketId(oldBucketId: String, newBucketId: String)

    /** 批量添加子级 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addChildren(children: List<ParentChildEntity>)
}

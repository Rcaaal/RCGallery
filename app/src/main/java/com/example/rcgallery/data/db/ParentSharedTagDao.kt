package com.example.rcgallery.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ParentSharedTagDao {

    /** 获取某个父级的所有共享 TAG 绑定 */
    @Query("SELECT * FROM parent_shared_tags WHERE parentId = :parentId ORDER BY createdAt ASC")
    suspend fun getTagsForParent(parentId: Long): List<ParentSharedTagEntity>

    /** 所有共享 TAG 绑定（用于构建 parentSharedTagMap） */
    @Query("SELECT * FROM parent_shared_tags ORDER BY parentId, createdAt ASC")
    fun getAllFlow(): Flow<List<ParentSharedTagEntity>>

    /** 一次性查询所有 */
    @Query("SELECT * FROM parent_shared_tags")
    suspend fun getAllOnce(): List<ParentSharedTagEntity>

    /** 获取某个父级绑定了哪些不同的 tagId（去重） */
    @Query("SELECT DISTINCT tagId FROM parent_shared_tags WHERE parentId = :parentId")
    suspend fun getDistinctTagIdsForParent(parentId: Long): List<Long>

    /** 获取某个父级某个共享 TAG 影响到的所有子级 bucketId */
    @Query("SELECT childBucketId FROM parent_shared_tags WHERE parentId = :parentId AND tagId = :tagId")
    suspend fun getChildBucketIdsForSharedTag(parentId: Long, tagId: Long): List<String>

    /** 批量插入（事务中使用） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<ParentSharedTagEntity>)

    /** 插入单条 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ParentSharedTagEntity): Long

    /** 删除某父级某共享 TAG 的指定子级绑定 */
    @Query("DELETE FROM parent_shared_tags WHERE parentId = :parentId AND tagId = :tagId AND childBucketId = :childBucketId")
    suspend fun remove(parentId: Long, tagId: Long, childBucketId: String)

    /** 删除某父级的某个共享 TAG（所有子级绑定） */
    @Query("DELETE FROM parent_shared_tags WHERE parentId = :parentId AND tagId = :tagId")
    suspend fun removeTagFromParent(parentId: Long, tagId: Long)

    /** 删除某父级的所有共享 TAG 绑定 */
    @Query("DELETE FROM parent_shared_tags WHERE parentId = :parentId")
    suspend fun removeAllTagsForParent(parentId: Long)

    /** 删除某个 TAG 的所有父级共享绑定（deleteTag 时调用） */
    @Query("DELETE FROM parent_shared_tags WHERE tagId = :tagId")
    suspend fun removeByTagId(tagId: Long)

    /** 相册移动到新目录后同步共享 TAG 的来源 bucketId。 */
    @Query("UPDATE parent_shared_tags SET childBucketId = :newBucketId WHERE childBucketId = :oldBucketId")
    suspend fun replaceChildBucketId(oldBucketId: String, newBucketId: String)
}

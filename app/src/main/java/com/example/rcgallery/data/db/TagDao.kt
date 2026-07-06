package com.example.rcgallery.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    // ── TAG 定义 CRUD ──

    @Query("SELECT * FROM tag_defs ORDER BY name ASC")
    fun getAllTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tag_defs ORDER BY name ASC")
    suspend fun getAllTagsOnce(): List<TagEntity>

    @Query("SELECT * FROM tag_defs WHERE id = :tagId")
    suspend fun getTagById(tagId: Long): TagEntity?

    @Query("SELECT * FROM tag_defs WHERE name = :name LIMIT 1")
    suspend fun getTagByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Delete
    suspend fun deleteTag(tag: TagEntity)

    @Query("DELETE FROM tag_defs WHERE id = :tagId")
    suspend fun deleteTagById(tagId: Long)

    /** 搜索 TAG（用于输入补全） */
    @Query("SELECT * FROM tag_defs WHERE name LIKE '%' || :query || '%' ORDER BY name ASC LIMIT 20")
    suspend fun searchTags(query: String): List<TagEntity>

    /** 获取最近使用的 TAG（按最后标记时间） */
    @Query("""
        SELECT DISTINCT d.* FROM tag_defs d
        INNER JOIN tag_targets t ON d.id = t.tagId
        ORDER BY t.id DESC LIMIT 20
    """)
    suspend fun getRecentTags(): List<TagEntity>

    // ── TAG-目标关联 CRUD ──

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTarget(target: TagTargetEntity): Long

    @Delete
    suspend fun removeTarget(target: TagTargetEntity)

    @Query("DELETE FROM tag_targets WHERE tagId = :tagId AND targetKey = :targetKey")
    suspend fun removeTargetByKey(tagId: Long, targetKey: String)

    @Query("SELECT t.* FROM tag_targets t WHERE t.targetKey = :targetKey ORDER BY t.id ASC")
    fun getTagsForTarget(targetKey: String): Flow<List<TagTargetEntity>>

    @Query("SELECT t.* FROM tag_targets t WHERE t.targetKey = :targetKey ORDER BY t.id ASC")
    suspend fun getTagsForTargetOnce(targetKey: String): List<TagTargetEntity>

    /** 获取目标的所有 tagId 列表 */
    @Query("SELECT tagId FROM tag_targets WHERE targetKey = :targetKey")
    suspend fun getTagIdsForTarget(targetKey: String): List<Long>

    /** 获取某个 TAG 标记的所有目标 */
    @Query("SELECT * FROM tag_targets WHERE tagId = :tagId ORDER BY targetKey ASC")
    suspend fun getTargetsForTag(tagId: Long): List<TagTargetEntity>

    /** 多 TAG AND 查询：同时拥有所有指定 tagId 的目标 */
    @Query("""
        SELECT targetKey, targetType FROM tag_targets
        WHERE tagId IN (:tagIds)
        GROUP BY targetKey, targetType
        HAVING COUNT(DISTINCT tagId) = :tagCount
    """)
    suspend fun findTargetsWithAllTags(tagIds: List<Long>, tagCount: Int): List<TargetResult>

    /** 获取所有相册的 TAG 数量统计（用于列表批量显示） */
    @Query("""
        SELECT t.targetKey, GROUP_CONCAT(t.tagId) as tagIds
        FROM tag_targets t
        WHERE t.targetType = 0
        GROUP BY t.targetKey
    """)
    fun getAllAlbumTags(): Flow<List<AlbumTagsResult>>

    /** 根据 targetType 获取所有关联 */
    @Query("SELECT * FROM tag_targets WHERE targetType = :targetType")
    suspend fun getTargetsByType(targetType: Int): List<TagTargetEntity>

    /** 更新 targetKey（改名时迁移用） */
    @Query("UPDATE tag_targets SET targetKey = :newKey WHERE targetKey = :oldKey")
    suspend fun updateTargetKey(oldKey: String, newKey: String)

    // ── 统计 ──

    @Query("SELECT COUNT(*) FROM tag_defs")
    fun getTagCount(): Flow<Int>
}

/** AND 查询结果 */
data class TargetResult(
    val targetKey: String,
    val targetType: Int
)

/** 批量查询结果：每个目标对应的 tagId 列表 */
data class AlbumTagsResult(
    val targetKey: String,
    val tagIds: String  // GROUP_CONCAT 逗号分隔
)

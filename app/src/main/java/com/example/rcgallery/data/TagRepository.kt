package com.example.rcgallery.data

import android.content.Context
import com.example.rcgallery.data.db.*
import com.example.rcgallery.model.MediaItem
import kotlinx.coroutines.flow.Flow

/**
 * TAG 数据仓库——封装 Room DAO，提供应用层接口。
 */
class TagRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).tagDao()

    // ── TAG 定义 ──

    /** 所有 TAG（实时 Flow） */
    fun getAllTags(): Flow<List<TagEntity>> = dao.getAllTags()

    /** 所有 TAG（一次性查询） */
    suspend fun getAllTagsOnce(): List<TagEntity> = dao.getAllTagsOnce()

    /** 按名称搜索 TAG（自动补全用） */
    suspend fun searchTags(query: String): List<TagEntity> = dao.searchTags(query)

    /** 最近使用的 TAG */
    suspend fun getRecentTags(): List<TagEntity> = dao.getRecentTags()

    /** 获取或创建 TAG（已存在则返回，不存在则创建） */
    suspend fun getOrCreateTag(name: String): TagEntity {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Tag name cannot be empty")
        val existing = dao.getTagByName(trimmed)
        if (existing != null) return existing
        val id = dao.insertTag(TagEntity(name = trimmed))
        return dao.getTagById(id) ?: TagEntity(id = id, name = trimmed)
    }

    /** 删除 TAG（级联删除关联） */
    suspend fun deleteTag(tagId: Long) = dao.deleteTagById(tagId)

    /** TAG 总数 */
    fun getTagCount(): Flow<Int> = dao.getTagCount()

    // ── 关联管理 ──

    /** 为目标添加 TAG */
    suspend fun addTagToTarget(tagId: Long, targetKey: String, targetType: Int) {
        dao.addTarget(
            TagTargetEntity(tagId = tagId, targetKey = targetKey, targetType = targetType)
        )
    }

    /** 为目标移除 TAG */
    suspend fun removeTagFromTarget(tagId: Long, targetKey: String) {
        dao.removeTargetByKey(tagId, targetKey)
    }

    /** 获取目标的 TAG（实时 Flow，用于 UI 观察变化） */
    fun getTagsForTarget(targetKey: String): Flow<List<TagTargetEntity>> =
        dao.getTagsForTarget(targetKey)

    /** 获取目标的 TAG（一次性查询） */
    suspend fun getTagsForTargetOnce(targetKey: String): List<TagTargetEntity> =
        dao.getTagsForTargetOnce(targetKey)

    /** 获取目标的所有 tag ID */
    suspend fun getTagIdsForTarget(targetKey: String): List<Long> =
        dao.getTagIdsForTarget(targetKey)

    /** 为目标的每个 TAG 返回完整的 TagEntity */
    suspend fun getTagEntitiesForTarget(targetKey: String): List<TagEntity> {
        val tagIds = dao.getTagIdsForTarget(targetKey)
        if (tagIds.isEmpty()) return emptyList()
        return dao.getAllTagsOnce().filter { it.id in tagIds }
    }

    /** 多 TAG AND 查询：必须同时拥有所有指定的 TAG */
    suspend fun findTargetsWithAllTags(tagNames: List<String>): List<TargetResult> {
        if (tagNames.isEmpty()) return emptyList()
        val tags = dao.getAllTagsOnce()
        val matched = tags.filter { it.name in tagNames }
        if (matched.size != tagNames.size) return emptyList() // 有的 TAG 不存在
        val tagIds = matched.map { it.id }
        return dao.findTargetsWithAllTags(tagIds, tagIds.size)
    }

    /** 获取相册类型的所有 TAG 关联（批量展示用） */
    fun getAllAlbumTags(): Flow<List<AlbumTagsResult>> = dao.getAllAlbumTags()

    /** 获取媒体类型的所有 TAG 关联（批量展示用） */
    fun getAllMediaTags(): Flow<List<AlbumTagsResult>> = dao.getAllMediaTags()

    /** 获取某个 TAG 标记的所有目标 */
    suspend fun getTargetsForTag(tagId: Long): List<TagTargetEntity> =
        dao.getTargetsForTag(tagId)

    /** 获取所有关联 */
    suspend fun getTargetsByType(targetType: Int): List<TagTargetEntity> =
        dao.getTargetsByType(targetType)

    // ── 改名同步 ──

    /**
     * 更新目标 key（文件/相册改名时调用）。
     * @param oldKey 旧 directoryPath/filePath
     * @param newKey 新 directoryPath/filePath
     */
    suspend fun updateTargetKey(oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        dao.updateTargetKey(newKey, oldKey)
    }

    /** 为相册目录路径构建 targetKey */
    fun albumKey(directoryPath: String): String = "album:$directoryPath"

    /** 为媒体文件路径构建 targetKey */
    fun mediaKey(filePath: String): String = "media:$filePath"

    /** 从 MediaItem 构建 targetKey */
    fun mediaKey(item: MediaItem): String = mediaKey(item.filePath)

    companion object {
        const val TYPE_ALBUM = 0
        const val TYPE_MEDIA = 1
    }
}

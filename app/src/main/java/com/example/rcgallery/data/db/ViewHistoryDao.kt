package com.example.rcgallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ViewHistoryDao {

    /** 插入或替换（同 targetKey 更新时间戳） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ViewHistoryEntity)

    /** 按类型查询最近浏览，限制数量 */
    @Query("SELECT * FROM view_history WHERE targetType = :type ORDER BY viewedAt DESC LIMIT :limit")
    suspend fun getRecentByType(type: Int, limit: Int): List<ViewHistoryEntity>

    /** 按类型统计行数 */
    @Query("SELECT COUNT(*) FROM view_history WHERE targetType = :type")
    suspend fun countByType(type: Int): Int

    /** 删除指定类型中最旧的多余行，仅保留 keepCount 条 */
    @Query("""
        DELETE FROM view_history
        WHERE targetType = :type AND targetKey NOT IN (
            SELECT targetKey FROM view_history
            WHERE targetType = :type
            ORDER BY viewedAt DESC
            LIMIT :keepCount
        )
    """)
    suspend fun deleteOlderThan(type: Int, keepCount: Int)

    /** 删除指定 targetKey 的记录（文件被删除时清理） */
    @Query("DELETE FROM view_history WHERE targetKey = :targetKey")
    suspend fun deleteByKey(targetKey: String)

    @Query("DELETE FROM view_history WHERE targetKey IN (:targetKeys)")
    suspend fun deleteByKeys(targetKeys: List<String>)

    /** 仅迁移路径键，不改变 viewedAt，避免移动文件改变最近访问顺序。 */
    @Query("UPDATE OR IGNORE view_history SET targetKey = :newKey WHERE targetKey = :oldKey")
    suspend fun updateTargetKey(oldKey: String, newKey: String)
}

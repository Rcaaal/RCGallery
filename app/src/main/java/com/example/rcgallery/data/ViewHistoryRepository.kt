package com.example.rcgallery.data

import android.content.Context
import com.example.rcgallery.data.db.AppDatabase
import com.example.rcgallery.data.db.ViewHistoryDao
import com.example.rcgallery.data.db.ViewHistoryEntity

/**
 * 浏览历史数据仓库——封装 Room DAO。
 *
 * 提供记录浏览和按类型查询的接口，自动维护每种类型的数量上限。
 */
class ViewHistoryRepository(context: Context) {

    private val dao: ViewHistoryDao = AppDatabase.getInstance(context).viewHistoryDao()

    companion object {
        const val MAX_ALBUMS = 20
        const val MAX_IMAGES = 100
        const val MAX_VIDEOS = 50
    }

    /** 记录浏览，自动裁剪超标条目 */
    suspend fun recordView(targetKey: String, id: Long, targetType: Int) {
        dao.insert(ViewHistoryEntity(targetKey = targetKey, id = id, targetType = targetType))
        // 裁剪超限条目
        val maxRows = when (targetType) {
            ViewHistoryEntity.TYPE_ALBUM -> MAX_ALBUMS
            ViewHistoryEntity.TYPE_IMAGE -> MAX_IMAGES
            ViewHistoryEntity.TYPE_VIDEO -> MAX_VIDEOS
            else -> return
        }
        dao.deleteOlderThan(targetType, maxRows)
    }

    /** 获取最近浏览的相册 */
    suspend fun getRecentAlbums(): List<ViewHistoryEntity> =
        dao.getRecentByType(ViewHistoryEntity.TYPE_ALBUM, MAX_ALBUMS)

    /** 获取最近浏览的图片 */
    suspend fun getRecentImages(): List<ViewHistoryEntity> =
        dao.getRecentByType(ViewHistoryEntity.TYPE_IMAGE, MAX_IMAGES)

    /** 获取最近浏览的视频 */
    suspend fun getRecentVideos(): List<ViewHistoryEntity> =
        dao.getRecentByType(ViewHistoryEntity.TYPE_VIDEO, MAX_VIDEOS)

    /** 删除指定 targetKey 的记录（文件被删除/改名时清理） */
    suspend fun deleteByKey(targetKey: String) {
        dao.deleteByKey(targetKey)
    }

    suspend fun deleteByKeys(targetKeys: List<String>) {
        if (targetKeys.isNotEmpty()) dao.deleteByKeys(targetKeys)
    }
}

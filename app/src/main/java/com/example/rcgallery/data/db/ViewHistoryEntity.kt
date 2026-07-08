package com.example.rcgallery.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 浏览历史记录表——记录最近查看的相册/图片/视频。
 *
 * targetKey 作为主键，同一条目再次浏览只更新时间戳（upsert）。
 * targetType: 0=album, 1=image, 2=video
 * 每种类型的数量上限：album 20, image 100, video 50
 */
@Entity(
    tableName = "view_history",
    indices = [Index(value = ["viewedAt"])]
)
data class ViewHistoryEntity(
    @PrimaryKey
    val targetKey: String,
    val id: Long,
    val targetType: Int,
    val viewedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_ALBUM = 0
        const val TYPE_IMAGE = 1
        const val TYPE_VIDEO = 2
    }
}

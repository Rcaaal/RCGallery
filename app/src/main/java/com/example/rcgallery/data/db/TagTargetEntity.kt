package com.example.rcgallery.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * TAG-目标关联表——记录某个 TAG 标记了哪个相册或文件。
 *
 * target_type: 0=album, 1=media
 * target_key: 相册用 directoryPath，媒体用 filePath
 *   （改名时通过 TagRepository.updateTargetKey 同步更新）
 */
@Entity(
    tableName = "tag_targets",
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["tagId"]),
        Index(value = ["targetKey"]),
        Index(value = ["tagId", "targetKey"], unique = true)
    ]
)
data class TagTargetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tagId: Long,
    /** 目标标识：相册用 directoryPath，媒体用 filePath */
    val targetKey: String,
    /** 0=album, 1=media */
    val targetType: Int
)

package com.example.rcgallery.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 父级共享 TAG 绑定表——记录父级绑定了哪个共享 TAG、影响到了哪些子级。
 *
 * 语义不是"父级有 TAG"，而是"父级通过此 TAG 与子级共享"。
 * 子级可以独立管理自己已有的同名 TAG，父级不强制删除子级上的 TAG（删除共享绑定时不清理子级真实 TAG）。
 *
 * 外键：
 *   tagId → tag_defs(id) CASCADE：删除 TAG 定义时自动清理共享关系。
 *   parentId 不设外键：由 deleteParentAlbum() 手动控制清理顺序。
 */
@Entity(
    tableName = "parent_shared_tags",
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["parentId"]),
        Index(value = ["tagId"]),
        Index(value = ["parentId", "tagId"])
    ],
    primaryKeys = ["parentId", "tagId", "childBucketId"]
)
data class ParentSharedTagEntity(
    /** 父级相册 ID */
    val parentId: Long,
    /** 共享的 TAG ID（外键 → tag_defs，CASCADE 删除） */
    val tagId: Long,
    /** 子相册 bucketId——记录此共享 TAG 同步到了哪个子级 */
    val childBucketId: String,
    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis()
)

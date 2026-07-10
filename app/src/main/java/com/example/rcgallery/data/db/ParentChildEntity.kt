package com.example.rcgallery.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 层级相册——父子关系表。
 * 一个子相册只能属于一个父级（由 parentBucketId + childBucketId 的联合主键约束）。
 */
@Entity(
    tableName = "parent_children",
    indices = [Index(value = ["parentId"]), Index(value = ["childBucketId"], unique = true)]
)
data class ParentChildEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 父级相册 ID */
    val parentId: Long,
    /** 子相册的 bucketId（MediaStore 真实相册） */
    val childBucketId: String,
    /** 子相册在父级内的排序权重 */
    val orderIndex: Int = 0
)

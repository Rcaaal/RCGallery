package com.example.rcgallery.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * TAG 定义表——存储用户创建的标签名称。
 */
@Entity(tableName = "tag_defs", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** TAG 名称（唯一，大小写敏感） */
    val name: String,
    /** 创建时间（毫秒时间戳） */
    val createdAt: Long = System.currentTimeMillis()
)

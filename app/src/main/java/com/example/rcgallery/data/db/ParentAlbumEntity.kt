package com.example.rcgallery.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 层级相册——父级（虚拟容器）定义。
 */
@Entity(
    tableName = "parent_albums",
    indices = [Index(value = ["name"], unique = true)]
)
data class ParentAlbumEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 父级相册名称（唯一） */
    val name: String,
    /** 创建时间戳（用于排序） */
    val createdAt: Long = System.currentTimeMillis()
)

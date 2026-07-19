package com.example.rcgallery.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "watch_later",
    primaryKeys = ["targetKey"],
    indices = [Index(value = ["addedAt"])]
)
data class WatchLaterEntity(
    val targetKey: String,
    val mediaUri: String,
    val mediaType: Int,
    val addedAt: Long = System.currentTimeMillis(),
    val watchedAt: Long? = null
) {
    companion object {
        const val TYPE_IMAGE = 1
        const val TYPE_VIDEO = 2
    }
}

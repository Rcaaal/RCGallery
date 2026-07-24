package com.example.rcgallery.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_import_history",
    indices = [Index(value = ["downloadedAt"]), Index(value = ["platform"])]
)
data class MediaImportHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platform: String,
    val sourceUrl: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val downloadedAt: Long,
    val successCount: Int,
    val failedCount: Int,
    val outputsJson: String,
)

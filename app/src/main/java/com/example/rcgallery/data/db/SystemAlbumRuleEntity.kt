package com.example.rcgallery.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** App-owned folder rule that keeps an album out of MediaStore-backed gallery apps. */
@Entity(tableName = "system_album_rules")
data class SystemAlbumRuleEntity(
    @PrimaryKey
    val directoryPath: String,
    val markerOwnedByApp: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

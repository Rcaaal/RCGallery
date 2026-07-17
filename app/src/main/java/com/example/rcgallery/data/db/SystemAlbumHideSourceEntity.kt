package com.example.rcgallery.data.db

import androidx.room.Entity
import androidx.room.Index

/** Logical owners of a folder's HID state. */
@Entity(
    tableName = "system_album_hide_sources",
    primaryKeys = ["directoryPath", "sourceType", "sourceId"],
    indices = [Index("directoryPath"), Index(value = ["sourceType", "sourceId"])]
)
data class SystemAlbumHideSourceEntity(
    val directoryPath: String,
    val sourceType: String,
    val sourceId: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_DIRECT = "DIRECT"
        const val TYPE_PARENT = "PARENT"
        const val DIRECT_ID = "direct"
    }
}


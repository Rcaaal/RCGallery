package com.example.rcgallery.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Last successful common migration root for a parent album. */
@Entity(tableName = "parent_migration_states")
data class ParentMigrationStateEntity(
    @PrimaryKey val parentId: Long,
    val targetRootPath: String,
    val updatedAt: Long = System.currentTimeMillis()
)


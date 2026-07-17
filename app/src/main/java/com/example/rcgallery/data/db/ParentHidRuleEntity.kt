package com.example.rcgallery.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Parent-level HID switch. Child folders keep their own independent HID sources. */
@Entity(tableName = "parent_hid_rules")
data class ParentHidRuleEntity(
    @PrimaryKey val parentId: Long,
    val createdAt: Long = System.currentTimeMillis()
)


package com.example.rcgallery.model

import com.example.rcgallery.data.db.TagEntity

/** Reserved in-memory tags that are backed by app state instead of tag_defs/tag_targets. */
object SystemTags {
    const val HID_NAME = "HID"
    const val HID_ID = Long.MIN_VALUE

    val hid = TagEntity(
        id = HID_ID,
        name = HID_NAME,
        createdAt = Long.MIN_VALUE
    )

    fun isHid(tag: TagEntity): Boolean = tag.id == HID_ID

    fun isHidName(name: String): Boolean = name.equals(HID_NAME, ignoreCase = true)

    fun prependTo(tags: List<TagEntity>): List<TagEntity> =
        listOf(hid) + tags.filterNot { isHidName(it.name) }
}

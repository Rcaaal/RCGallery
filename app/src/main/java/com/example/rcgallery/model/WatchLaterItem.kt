package com.example.rcgallery.model

data class WatchLaterItem(
    val media: MediaItem,
    val addedAt: Long,
    val watchedAt: Long?
) {
    val isWatched: Boolean get() = watchedAt != null
}

package com.example.rcgallery.ui.navigation

sealed class Route(val route: String) {
    data object AlbumGrid : Route("album_grid")
    data object MediaGrid : Route("media_grid/{albumId}") {
        fun createRoute(albumId: String) = "media_grid/$albumId"
    }
    data object Preview : Route("preview/{initialIndex}") {
        fun createRoute(initialIndex: Int) = "preview/$initialIndex"
    }
    data object VideoPlayer : Route("video_player/{mediaId}") {
        fun createRoute(mediaId: Long) = "video_player/$mediaId"
    }
    data object Search : Route("search")
}

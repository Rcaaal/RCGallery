package com.example.rcgallery

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.rcgallery.ui.navigation.Route
import com.example.rcgallery.ui.screen.AlbumGridScreen
import com.example.rcgallery.ui.screen.SearchScreen
import com.example.rcgallery.ui.theme.RCGalleryTheme
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.player.VideoPlayerScreen

/** PiP 状态与工具方法 */
object PipState {
    var isInPip: Boolean by mutableStateOf(false)
    var exoPlayer: ExoPlayer? = null
    var videoWidth: Int = 16
    var videoHeight: Int = 9

    /** 根据当前视频尺寸构建 PictureInPictureParams */
    fun buildPipParams(): PictureInPictureParams {
        var w = videoWidth; var h = videoHeight
        exoPlayer?.videoSize?.let { vs ->
            if (vs.width > 0 && vs.height > 0) {
                w = vs.width; h = vs.height
                if (vs.unappliedRotationDegrees == 90 || vs.unappliedRotationDegrees == 270) {
                    val tmp = w; w = h; h = tmp
                }
            }
        }
        val ratio = Rational(w.coerceAtLeast(1), h.coerceAtLeast(1))
        AppLogger.d("PiP", "buildPipParams ratio=$w:$h (stored=${videoWidth}x${videoHeight})")
        return PictureInPictureParams.Builder().setAspectRatio(ratio).build()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RCGalleryTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = Route.AlbumGrid.route
                    ) {
                        composable(Route.AlbumGrid.route) {
                            AlbumGridScreen(
                                onSearchClick = {
                                    AppLogger.d("Nav", "navigate → Search")
                                    navController.navigate(Route.Search.route)
                                }
                            )
                        }

                        composable(
                            route = Route.VideoPlayer.route,
                            arguments = listOf(navArgument("mediaId") { type = NavType.LongType })
                        ) { backStackEntry ->
                            val mediaId = backStackEntry.arguments?.getLong("mediaId") ?: 0L
                            AppLogger.d("Nav", "→ VideoPlayer enter mediaId=$mediaId")
                            VideoPlayerScreen(
                                mediaId = mediaId,
                                onBackClick = {
                                    AppLogger.d("Nav", "← popBackStack from VideoPlayer")
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable(Route.Search.route) {
                            SearchScreen(
                                onBackClick = {
                                    AppLogger.d("Nav", "← popBackStack from Search")
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.isInPip = isInPictureInPictureMode
        AppLogger.d("PiP", "modeChanged isInPip=$isInPictureInPictureMode")
    }
}

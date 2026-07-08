package com.example.rcgallery

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.rcgallery.ui.navigation.Route
import com.example.rcgallery.ui.screen.AlbumGridScreen
import com.example.rcgallery.ui.screen.NetworkBrowserScreen
import com.example.rcgallery.ui.screen.SearchScreen
import com.example.rcgallery.ui.screen.TagListScreen
import com.example.rcgallery.ui.theme.RCGalleryTheme
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.player.VideoPlayerScreen
import com.example.rcgallery.data.smb.SmbBrowseState
import com.example.rcgallery.viewmodel.GalleryViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/** PiP 状态与工具方法 */
object PipState {
    var isInPip: Boolean by mutableStateOf(false)
    /** SMB 预览是否激活（打开图片/视频全屏查看时隐藏底部导航栏） */
    var isSmbPreviewActive: Boolean by mutableStateOf(false)
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
                    val viewModel: GalleryViewModel = viewModel()
                    val currentTab by viewModel.currentTab.collectAsState()
                    val smbBrowseState by viewModel.smbBrowseState.collectAsState()
                    var isAlbumActive by remember { mutableStateOf(false) }
                    val showBottomBar = when {
                        PipState.isInPip -> false
                        currentTab == 1 -> !isAlbumActive
                        currentTab == 2 -> !PipState.isSmbPreviewActive && smbBrowseState is SmbBrowseState.DeviceList
                        else -> !isAlbumActive
                    }

                    fun safeGoBack(navController: androidx.navigation.NavController) {
                        if (navController.currentBackStackEntry?.destination?.route != Route.AlbumGrid.route) {
                            navController.popBackStack()
                        }
                    }

                    Scaffold(
                        bottomBar = {
                            // 条件渲染，不占位。嵌套 Scaffold 的 insets 已通过 windowInsets=0 修复
                            if (showBottomBar) {
                                NavigationBar(modifier = Modifier.fillMaxWidth().height(80.dp)) {
                                    NavigationBarItem(
                                        icon = { Text("📁") },
                                        label = { Text("本地") },
                                        selected = currentTab == 0,
                                        onClick = { viewModel.switchTab(0) }
                                    )
                                    NavigationBarItem(
                                        icon = { Text("🏷") },
                                        label = { Text("标签") },
                                        selected = currentTab == 1,
                                        onClick = { viewModel.switchTab(1) }
                                    )
                                    NavigationBarItem(
                                        icon = { Text("🌐") },
                                        label = { Text("网络") },
                                        selected = currentTab == 2,
                                        onClick = { viewModel.switchTab(2) }
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding)) {
                            when (currentTab) {
                                0 -> {
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
                                                },
                                                onAlbumActiveChanged = { active ->
                                                    isAlbumActive = active
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
                                1 -> {
                                    TagListScreen(
                                        viewModel = viewModel,
                                        onBackClick = { viewModel.switchTab(0) },
                                        onOverlayChanged = { active -> isAlbumActive = active }
                                    )
                                }
                                2 -> {
                                    NetworkBrowserScreen(viewModel = viewModel)
                                }
                            }
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

package com.example.rcgallery.ui.screen

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.datasource.DefaultHttpDataSource
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.rcgallery.data.baidu.BaiduCloudEntry
import com.example.rcgallery.ui.component.VideoPlayer
import com.example.rcgallery.viewmodel.BaiduNetdiskViewModel
import com.example.rcgallery.viewmodel.PlaybackSettingsViewModel

@Composable
fun BaiduPreviewScreen(
    initialIndex: Int,
    items: List<BaiduCloudEntry>,
    baiduViewModel: BaiduNetdiskViewModel,
    onDismiss: () -> Unit,
    onDownload: (BaiduCloudEntry) -> Unit
) {
    if (items.isEmpty()) return
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val playbackSettings: PlaybackSettingsViewModel = viewModel(activity)
    val volumeState by playbackSettings.volumeState.collectAsState()
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(items.indices),
        pageCount = { items.size }
    )

    DisposableEffect(Unit) {
        playbackSettings.muteSystemOnEnter()
        onDispose { playbackSettings.restoreSystemVolume() }
    }
    BackHandler(onBack = onDismiss)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 0,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            Box(Modifier.fillMaxSize()) {
                val entry = items[page]
                val remoteUrl by produceState<String?>(null, entry.fsId) {
                    value = runCatching { baiduViewModel.mediaUrl(entry) }.getOrNull()
                }
                val url = remoteUrl
                if (url == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else if (entry.isVideo) {
                    VideoPlayer(
                        uri = Uri.parse(url),
                        isActive = page == pagerState.currentPage,
                        volumeLevel = volumeState.level,
                        onToggleMute = playbackSettings::toggleMute,
                        dataSourceFactory = DefaultHttpDataSource.Factory()
                            .setUserAgent(BAIDU_USER_AGENT),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(url)
                            .addHeader("User-Agent", BAIDU_USER_AGENT)
                            .build(),
                        contentDescription = entry.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        val current = items.getOrNull(pagerState.currentPage)
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 8.dp, start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismiss) { Text("← 返回", color = Color.White) }
            Text(
                text = "${pagerState.currentPage + 1}/${items.size}  ${current?.name.orEmpty()}",
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            current?.let { entry ->
                TextButton(onClick = { onDownload(entry) }) { Text("下载", color = Color.White) }
            }
        }
    }
}

private const val BAIDU_USER_AGENT = "pan.baidu.com"

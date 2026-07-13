package com.example.rcgallery.ui.component

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.rcgallery.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "VideoThumb"
private const val THUMB_MAX_DIM = 360

/**
 * 视频封面缩略图组件 — 轻量级，不创建 ExoPlayer/MediaSession。
 *
 * 用于邻页视频预览场景。使用 [MediaMetadataRetriever] 提取视频第一帧关键帧，
 * 降采样到 [THUMB_MAX_DIM] 像素，IO 线程解码。
 *
 * 解码失败时显示默认视频图标 + 播放按钮覆盖层。
 */
@Composable
fun VideoThumbnailCover(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var hasError by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        thumbnail = null
        hasError = false
        try {
            val bmp = withContext(Dispatchers.IO) {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(context, uri)
                    // 取第一帧关键帧（耗时操作）
                    val frame = mmr.frameAtTime
                    if (frame != null) {
                        // 降采样到 THUMB_MAX_DIM
                        val scale = minOf(
                            THUMB_MAX_DIM.toFloat() / frame.width,
                            THUMB_MAX_DIM.toFloat() / frame.height
                        )
                        if (scale < 1f) {
                            val w = (frame.width * scale).toInt()
                            val h = (frame.height * scale).toInt()
                            Bitmap.createScaledBitmap(frame, w, h, true).also {
                                if (frame !== it) frame.recycle()
                            }
                        } else {
                            frame
                        }
                    } else null
                } finally {
                    mmr.release()
                }
            }
            if (bmp != null) {
                thumbnail = bmp
            } else {
                hasError = true
                AppLogger.d(TAG, "no frame at uri=${uri.lastPathSegment}")
            }
        } catch (e: Exception) {
            hasError = true
            AppLogger.e(TAG, "thumbnail fail uri=${uri.lastPathSegment} err=${e.message}")
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            thumbnail != null -> {
                AndroidView(
                    factory = { ctx ->
                        ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                    },
                    update = { iv -> iv.setImageBitmap(thumbnail) },
                    modifier = Modifier.fillMaxSize()
                )
                // 播放按钮覆盖层
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("▶", color = Color.White, fontSize = 24.sp)
                }
            }
            hasError -> {
                Text("无法加载", color = Color.Gray, fontSize = 14.sp)
            }
            else -> {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

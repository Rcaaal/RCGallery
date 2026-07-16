package com.example.rcgallery.ui.component

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.rcgallery.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "VideoThumb"
private const val THUMB_MAX_DIM = 360
private const val THUMB_CACHE_SIZE_KB = 16 * 1024

private val thumbnailCache = object : LruCache<String, Bitmap>(THUMB_CACHE_SIZE_KB) {
    override fun sizeOf(key: String, value: Bitmap): Int =
        (value.allocationByteCount / 1024).coerceAtLeast(1)
}

private fun cachedThumbnail(key: String): Bitmap? = synchronized(thumbnailCache) {
    thumbnailCache.get(key)?.takeUnless(Bitmap::isRecycled)
}

private fun cacheThumbnail(key: String, bitmap: Bitmap) = synchronized(thumbnailCache) {
    thumbnailCache.put(key, bitmap)
}

private fun extractVideoFrame(context: android.content.Context, uri: Uri): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val frame = retriever.frameAtTime ?: return null
        val scale = minOf(
            THUMB_MAX_DIM.toFloat() / frame.width,
            THUMB_MAX_DIM.toFloat() / frame.height
        )
        if (scale < 1f) {
            val width = (frame.width * scale).toInt().coerceAtLeast(1)
            val height = (frame.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(frame, width, height, true).also { scaled ->
                if (frame !== scaled) frame.recycle()
            }
        } else {
            frame
        }
    } finally {
        retriever.release()
    }
}

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
    val cancellationSignal = remember(uri) { CancellationSignal() }

    DisposableEffect(uri) {
        onDispose { cancellationSignal.cancel() }
    }

    LaunchedEffect(uri) {
        val cacheKey = uri.toString()
        thumbnail = cachedThumbnail(cacheKey)
        hasError = false
        if (thumbnail != null) return@LaunchedEffect
        try {
            val bmp = withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val systemThumbnail = runCatching {
                        context.contentResolver.loadThumbnail(
                            uri,
                            Size(THUMB_MAX_DIM, THUMB_MAX_DIM),
                            cancellationSignal
                        )
                    }.getOrNull()
                    systemThumbnail ?: if (!cancellationSignal.isCanceled) {
                        extractVideoFrame(context, uri)
                    } else null
                } else {
                    extractVideoFrame(context, uri)
                }
            }
            if (bmp != null) {
                cacheThumbnail(cacheKey, bmp)
                thumbnail = bmp
            } else {
                hasError = true
                AppLogger.d(TAG, "no frame at uri=${uri.lastPathSegment}")
            }
        } catch (e: CancellationException) {
            throw e
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

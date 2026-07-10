package com.example.rcgallery.ui.component

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * 缩略图组件 — 基于 Coil AsyncImage。
 *
 * Coil 管线：
 * 1. 先用 BitmapFactory.inSampleSize 解码到目标尺寸（不是 decode 全图）
 * 2. 结果写入 Coil 磁盘缓存（100MB）+ 内存缓存（25% heap）
 * 3. 下次加载直接命中缓存，瞬间返回
 *
 * @param targetSize Coil 用这个值做 subsample，越小越快
 */
@Composable
fun GalleryThumbnail(
    uri: Uri,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholderColor: Color = Color.LightGray.copy(alpha = 0.3f),
    targetSize: Int = 120
) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(uri)
            .size(targetSize)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .crossfade(false)
            .build(),
        contentDescription = contentDescription,
        modifier = modifier.fillMaxSize().background(placeholderColor),
        contentScale = ContentScale.Crop
    )
}

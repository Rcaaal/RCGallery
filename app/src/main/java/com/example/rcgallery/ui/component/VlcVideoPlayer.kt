package com.example.rcgallery.ui.component

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import com.example.rcgallery.R
import com.example.rcgallery.util.AppLogger
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** VLC-backed player used only for ASF/WMV containers. */
@Composable
internal fun VlcVideoPlayer(
    uri: Uri,
    isActive: Boolean,
    volumeLevel: Float,
    onToggleMute: () -> Unit,
    savedPositions: MutableMap<Uri, Long>,
    onRegisterSeekHandler: ((Long) -> Unit) -> Unit,
    onRegisterPositionProvider: (() -> Long) -> Unit,
    onRegisterDurationProvider: (() -> Long) -> Unit,
    modifier: Modifier,
    hideUiOverlays: Boolean,
    keepControllerVisible: Boolean,
    onShowInfoClick: () -> Unit,
    onMoveToTrash: () -> Unit,
    onRegisterSpeedSettingsTrigger: (() -> Unit) -> Unit,
    onControllerVisibilityChanged: (Boolean) -> Unit,
    onFirstFrameRendered: () -> Unit,
    repeatMode: Int,
    onPlaybackEnded: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("rcgallery_prefs", 0) }
    val libVlc = remember { LibVLC(context, arrayListOf("--no-drop-late-frames", "--no-skip-frames")) }
    val player = remember { MediaPlayer(libVlc) }
    var videoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }
    var controllerVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(1L) }
    var hasError by remember { mutableStateOf(false) }
    var showSpeedSettings by remember { mutableStateOf(false) }
    var playbackRate by remember { mutableFloatStateOf(1f) }
    val currentEnded = rememberUpdatedState(onPlaybackEnded)
    val currentFirstFrame = rememberUpdatedState(onFirstFrameRendered)
    val currentRepeatMode = rememberUpdatedState(repeatMode)

    SideEffect {
        onRegisterSeekHandler { target -> player.time = target.coerceIn(0L, player.length.coerceAtLeast(1L)) }
        onRegisterPositionProvider { player.time.coerceAtLeast(0L) }
        onRegisterDurationProvider { player.length.coerceAtLeast(1L) }
        onRegisterSpeedSettingsTrigger { showSpeedSettings = true }
    }

    DisposableEffect(player) {
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    isPlaying = true
                    hasError = false
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> isPlaying = false
                MediaPlayer.Event.Vout -> currentFirstFrame.value()
                MediaPlayer.Event.EndReached -> {
                    if (currentRepeatMode.value == Player.REPEAT_MODE_ONE) {
                        player.time = 0L
                        player.play()
                    } else {
                        isPlaying = false
                        currentEnded.value()
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    hasError = true
                    isPlaying = false
                    AppLogger.e("VlcVideoPlayer", "playback error uri=${uri.lastPathSegment}")
                }
            }
        }
        onDispose {
            if (player.time > 1000L) savedPositions[uri] = player.time
            runCatching { player.stop() }
            runCatching { player.detachViews() }
            player.release()
            libVlc.release()
        }
    }

    LaunchedEffect(uri, videoLayout) {
        val layout = videoLayout ?: return@LaunchedEffect
        runCatching { player.detachViews() }
        player.attachViews(layout, null, false, false)
        val media = Media(libVlc, uri).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=1500")
        }
        player.media = media
        media.release()
        val resumePosition = savedPositions.remove(uri)
        if (isActive) player.play()
        if (resumePosition != null && resumePosition > 0L) {
            var attempts = 0
            while (player.length <= 0L && attempts < 20) {
                delay(50L)
                attempts++
            }
            player.time = resumePosition.coerceAtMost(player.length.coerceAtLeast(resumePosition))
        }
    }

    LaunchedEffect(isActive) {
        if (isActive) {
            if (!player.isPlaying) player.play()
        } else {
            if (player.time > 1000L) savedPositions[uri] = player.time
            player.pause()
        }
    }

    LaunchedEffect(isActive, player) {
        while (isActive) {
            position = player.time.coerceAtLeast(0L)
            duration = player.length.coerceAtLeast(1L)
            isPlaying = player.isPlaying
            delay(250L)
        }
    }

    LaunchedEffect(controllerVisible, keepControllerVisible) {
        if (keepControllerVisible) controllerVisible = true
        onControllerVisibilityChanged(controllerVisible)
        if (controllerVisible && !keepControllerVisible) {
            delay(3000L)
            controllerVisible = false
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { VLCVideoLayout(it).also { layout -> layout.keepScreenOn = true; videoLayout = layout } },
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier.fillMaxSize().clickable {
                controllerVisible = !controllerVisible
            }
        )

        if (hasError) {
            Text("无法播放此 WMV/ASF 视频", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }

        if (!hideUiOverlays && controllerVisible) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(12.dp)
            ) {
                Slider(
                    value = position.coerceAtMost(duration).toFloat(),
                    onValueChange = { value ->
                        position = value.toLong()
                        player.time = position
                    },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f)
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(painterResource(R.drawable.ic_rewind_10), "后退", tint = Color.White,
                        modifier = Modifier.size(34.dp).clickable { player.time = (player.time - 5000L).coerceAtLeast(0L) })
                    Text(if (isPlaying) "II" else ">", color = Color.White, fontSize = 28.sp,
                        modifier = Modifier.clickable { if (player.isPlaying) player.pause() else player.play() })
                    Icon(painterResource(R.drawable.ic_forward_10), "前进", tint = Color.White,
                        modifier = Modifier.size(34.dp).clickable { player.time = (player.time + 15000L).coerceAtMost(duration) })
                }
            }

            Column(
                Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Icon(painterResource(R.drawable.ic_info), "文件信息", tint = Color.White,
                    modifier = Modifier.size(24.dp).clickable(onClick = onShowInfoClick))
                Icon(painterResource(R.drawable.ic_trash), "删除", tint = Color.Red,
                    modifier = Modifier.size(24.dp).clickable(onClick = onMoveToTrash))
            }
        }

        if (!hideUiOverlays) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = InertiaSettings.muteButtonBottomDp.dp)
                    .size(40.dp).background(Color.White.copy(alpha = 0.3f), CircleShape).clickable(onClick = onToggleMute),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(if (volumeLevel > 0f) R.drawable.ic_volume_up else R.drawable.ic_volume_off),
                    if (volumeLevel > 0f) "有声音" else "静音",
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }

    if (showSpeedSettings) {
        AlertDialog(
            onDismissRequest = { showSpeedSettings = false },
            title = { Text("播放速度") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1f, 2f, 3f, 4f).forEach { speed ->
                        FilterChip(
                            selected = playbackRate == speed,
                            onClick = {
                                playbackRate = speed
                                player.rate = speed
                                prefs.edit().putFloat("long_press_speed", speed).apply()
                            },
                            label = { Text("${speed.toInt()}x") }
                        )
                    }
                }
            },
            confirmButton = { Button(onClick = { showSpeedSettings = false }) { Text("完成") } }
        )
    }
}

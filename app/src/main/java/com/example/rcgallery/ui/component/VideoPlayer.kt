package com.example.rcgallery.ui.component

import android.content.Context
import android.util.Xml
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.example.rcgallery.PipState
import com.example.rcgallery.util.AppLogger
import kotlinx.coroutines.delay

private const val SPEED_TEXT_HIDE_DELAY_MS = 800L
private const val PROGRESS_POLL_MS = 100L
private const val DOUBLE_TAP_TIMEOUT_MS = 300L
private val speedOptions = listOf(2f, 3f, 4f, 5f, 10f)

@Composable
fun VideoPlayer(
    uri: Uri,
    isActive: Boolean,
    volumeEnabled: Boolean,
    onVolumeToggle: () -> Unit,
    savedPositions: MutableMap<Uri, Long> = remember { mutableMapOf() },
    onRegisterSeekHandler: ((Long) -> Unit) -> Unit = {},
    onRegisterPositionProvider: (() -> Long) -> Unit = {},
    onRegisterDurationProvider: (() -> Long) -> Unit = {},
    modifier: Modifier = Modifier,
    onRequestPip: (() -> Unit)? = null,
    hideUiOverlays: Boolean = false,
    keepControllerVisible: Boolean = false,
    onShowInfoClick: () -> Unit = {},
    onMoveToTrash: () -> Unit = {},
    dataSourceFactory: DataSource.Factory? = null,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("rcgallery_prefs", Context.MODE_PRIVATE) }
    var hasError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        InertiaSettings.longPressSpeed = prefs.getFloat("long_press_speed", 2f)
        InertiaSettings.longPressTimeoutSec = prefs.getFloat("long_press_timeout_sec", 1.0f)
    }

    val speedBoosted = remember { booleanArrayOf(false) }
    var speedText by remember { mutableStateOf("") }
    var showSpeedSettings by remember { mutableStateOf(false) }
    var controllerVisible by remember { mutableStateOf(false) }
    val pvRef = remember { mutableStateOf<PlayerView?>(null) }
    var pipControllerDisabled by remember { mutableStateOf(false) }
    var playerPosition by remember { mutableFloatStateOf(0f) }
    var playerDuration by remember { mutableFloatStateOf(1f) }
    val lastTapTime = remember { longArrayOf(0L) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setRenderersFactory(
                DefaultRenderersFactory(context).apply {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                }
            )
            .build().apply {
            val factory = dataSourceFactory ?: DefaultDataSource.Factory(context)
            val mediaSource = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(uri))
            setMediaSource(mediaSource)
            // ❌ 不在 remember 中 prepare() — 等 TextureView surface 就绪后再 prepare
            //    防止 codec 在无 surface 时初始化导致死机
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_ONE
            volume = if (isActive && volumeEnabled) 1f else 0f
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    AppLogger.e("VideoPlayer", "playback error uri=${uri.lastPathSegment} error=$error")
                    hasError = true
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        val rot = videoSize.unappliedRotationDegrees
                        val w = videoSize.width; val h = videoSize.height
                        // 如果视频有旋转标记（如竖屏视频编码为1920x1080+rotate90°），交换宽高
                        if (rot == 90 || rot == 270) {
                            PipState.videoWidth = h; PipState.videoHeight = w
                            AppLogger.d("PiP", "onVideoSizeChanged: ${w}x${h} rot=${rot}° → applied ${h}x${w}")
                        } else {
                            PipState.videoWidth = w; PipState.videoHeight = h
                            AppLogger.d("PiP", "onVideoSizeChanged: ${w}x${h} rot=${rot}°")
                        }
                    } else {
                        AppLogger.d("PiP", "onVideoSizeChanged: invalid ${videoSize.width}x${videoSize.height}")
                    }
                }
            })
        }
    }

    // 每次重组注册 seek 回调，供 PreviewScreen 全屏层调用
    // 使用 poll 状态（playerPosition/playerDuration），不直读 ExoPlayer（防异步 prepare 后 duration=0）
    val callbackUri = uri
    SideEffect {
        onRegisterSeekHandler { pos -> exoPlayer.seekTo(pos) }
        onRegisterPositionProvider { playerPosition.toLong() }
        onRegisterDurationProvider { playerDuration.toLong() }
    }

    // MediaSession → PiP 窗口自动显示暂停/播放按钮
    // 用 URI hash 作为唯一 session ID，翻页后多个 VideoPlayer 共存时不会冲突
    // setCallback 通知系统 PiP 控制能力（部分国产 ROM 需要明确回调才能显示控制按钮）
    val mediaSession = remember { mutableStateOf<MediaSession?>(null) }
    DisposableEffect(uri) {
        val ms = try {
            MediaSession.Builder(context, exoPlayer)
                .setId("rcg_${uri.hashCode()}")
                .setCallback(object : MediaSession.Callback {
                    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setAvailablePlayerCommands(exoPlayer.availableCommands)
                            .build()
                    }
                })
                .build()
                .also { AppLogger.d("VideoPlayer", "MediaSession OK id=rcg_${uri.hashCode()}") }
        } catch (e: Throwable) {
            AppLogger.e("VideoPlayer", "MediaSession FAILED: ${e.message}")
            e.printStackTrace()
            null
        }
        mediaSession.value = ms
        onDispose { ms?.release() }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.stop()
            if (PipState.exoPlayer == exoPlayer) PipState.exoPlayer = null
            exoPlayer.release()
        }
    }

    // 保持 PipState.exoPlayer 指向当前 VideoPlayer 的 ExoPlayer，供 PiP 尺寸同步使用
    LaunchedEffect(Unit) { PipState.exoPlayer = exoPlayer }

    // App 切后台时暂停（非 PiP 模式），PiP 模式不暂停
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentHideOverlays by rememberUpdatedState(hideUiOverlays)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && !currentHideOverlays) {
                exoPlayer.playWhenReady = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isActive) {
        if (isActive) {
            hasError = false; exoPlayer.prepare(); exoPlayer.playWhenReady = true
            savedPositions.remove(uri)?.let { exoPlayer.seekTo(it) }
            pvRef.value?.let { pv -> pv.controllerShowTimeoutMs = 3000 }
        } else {
            val pos = exoPlayer.currentPosition
            if (pos > 1000) savedPositions[uri] = pos
            exoPlayer.playWhenReady = false; exoPlayer.stop(); exoPlayer.setPlaybackSpeed(1f)
            speedBoosted[0] = false; speedText = ""
        }
    }

    LaunchedEffect(volumeEnabled) { exoPlayer.volume = if (volumeEnabled) 1f else 0f }

    // PiP 进入前强制隐藏 PlayerView 控制器（防止控制栏闪烁 + SurfaceView 布局计算干扰 PiP 画面）
    LaunchedEffect(hideUiOverlays) {
        if (hideUiOverlays) {
            pipControllerDisabled = true
            pvRef.value?.let { pv ->
                pv.useController = false
                pv.hideController()
            }
            controllerVisible = false
        } else {
            pipControllerDisabled = false
            pvRef.value?.useController = true
        }
    }

    // 信息栏打开时控制栏常驻显示（不自动隐藏）
    LaunchedEffect(keepControllerVisible) {
        pvRef.value?.let { pv ->
            if (keepControllerVisible) {
                pv.controllerShowTimeoutMs = Int.MAX_VALUE
                pv.showController()
            } else {
                pv.controllerShowTimeoutMs = 3000
            }
        }
    }

    LaunchedEffect(isActive) {
        while (isActive) {
            playerPosition = exoPlayer.currentPosition.coerceAtLeast(0).toFloat()
            playerDuration = exoPlayer.duration.coerceAtLeast(1).toFloat()
            delay(PROGRESS_POLL_MS)
        }
    }

    LaunchedEffect(speedText) {
        if (speedText == "1x") { delay(SPEED_TEXT_HIDE_DELAY_MS); speedText = "" }
    }

    if (hasError) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("无法播放此视频", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    // 从 XML 加载 surface_type="texture_view" — Media3 1.6.1 不支持程序化设置
                    val pvAttrs = ctx.resources.getXml(com.example.rcgallery.R.xml.player_view_texture).let { parser ->
                        parser.next(); parser.nextTag(); Xml.asAttributeSet(parser)
                    }
                    val pv = PlayerView(ctx, pvAttrs).apply {
                        player = exoPlayer; useController = true; keepScreenOn = true; controllerShowTimeoutMs = 3000
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controllerVisible = v == android.view.View.VISIBLE })
                    }
                    pvRef.value = pv

                    // 隐藏 PlayerView 原生齿轮按钮（已移至左上角 Compose 层）
                    pv.post {
                        val sid = ctx.resources.getIdentifier("exo_settings", "id", ctx.packageName)
                        if (sid != 0) pv.findViewById<View>(sid)?.visibility = android.view.View.GONE
                    }

                    // ── 长按倍速 Runnable ──
                    val lpr = Runnable {
                        if (exoPlayer.playWhenReady) { val s = InertiaSettings.longPressSpeed; exoPlayer.setPlaybackSpeed(s); speedBoosted[0] = true; speedText = "${s.toInt()}x"; pv.hideController() }
                    }

                    // ── 覆盖层（处理双击暂停 / 长按倍速，不再处理 seek）──
                    val controlBarHeightPx = (160 * ctx.resources.displayMetrics.density).toInt()
                    val overlay = object : View(ctx) {
                        override fun onDraw(canvas: android.graphics.Canvas) {
                            super.onDraw(canvas)
                            // seek 调试线已随 seek 手势迁移至 PreviewScreen
                        }
                    }.apply {
                        isClickable = true; setWillNotDraw(false)
                    }
                    overlay.setOnTouchListener { v, ev ->
                        val inControlBarZone = ev.y.toInt() >= (pv.height - controlBarHeightPx)

                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                v.removeCallbacks(lpr)
                                if (!inControlBarZone) {
                                    // 视频区：长按倍速
                                    if (exoPlayer.playWhenReady) { v.postDelayed(lpr, (InertiaSettings.longPressTimeoutSec * 1000).toLong()) }
                                } else {
                                    lastTapTime[0] = 0L  // 控制栏按钮区，清双击计时器
                                }
                                pv.dispatchTouchEvent(MotionEvent.obtain(ev))
                                true
                            }

                            MotionEvent.ACTION_MOVE -> {
                                pv.dispatchTouchEvent(MotionEvent.obtain(ev))
                                true
                            }

                            MotionEvent.ACTION_UP -> {
                                v.removeCallbacks(lpr)
                                val now = SystemClock.uptimeMillis()

                                if (now - lastTapTime[0] < DOUBLE_TAP_TIMEOUT_MS) {
                                    // 双击暂停/播放
                                    lastTapTime[0] = 0L; v.removeCallbacks(lpr)
                                    exoPlayer.playWhenReady = !exoPlayer.playWhenReady; pv.showController()
                                    if (speedBoosted[0]) { exoPlayer.setPlaybackSpeed(1f); speedBoosted[0] = false; speedText = "" }
                                } else if (speedBoosted[0]) {
                                    val cancel = MotionEvent.obtain(ev.downTime, ev.eventTime, MotionEvent.ACTION_CANCEL, ev.x, ev.y, ev.metaState)
                                    pv.dispatchTouchEvent(cancel); cancel.recycle()
                                    exoPlayer.setPlaybackSpeed(1f); speedBoosted[0] = false; speedText = "1x"; pv.hideController()
                                } else if (controllerVisible) {
                                    lastTapTime[0] = now
                                    pv.dispatchTouchEvent(MotionEvent.obtain(ev))
                                } else {
                                    lastTapTime[0] = now; pv.dispatchTouchEvent(MotionEvent.obtain(ev))
                                    v.postDelayed({ if (lastTapTime[0] != 0L) { lastTapTime[0] = 0L; pv.showController() } }, DOUBLE_TAP_TIMEOUT_MS)
                                }
                                true
                            }

                            MotionEvent.ACTION_CANCEL -> {
                                v.removeCallbacks(lpr)
                                if (speedBoosted[0]) { exoPlayer.setPlaybackSpeed(1f); speedBoosted[0] = false; speedText = "1x" }
                                pv.dispatchTouchEvent(MotionEvent.obtain(ev)); true
                            }

                            else -> { pv.dispatchTouchEvent(MotionEvent.obtain(ev)); true }
                        }
                    }

                    FrameLayout(ctx).apply {
                        addView(pv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                },
                modifier = Modifier.fillMaxSize()
            )


            AnimatedVisibility(visible = speedText.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = speedText, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            // ── 左侧按钮组（信息 + PiP，与控制面板同显隐，纯图标无背景，相同大小）──
            if (!hideUiOverlays && controllerVisible) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 信息按钮（纯图标）
                    Icon(
                        painter = painterResource(com.example.rcgallery.R.drawable.ic_info),
                        contentDescription = "文件信息",
                        tint = Color.White,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { onShowInfoClick() }
                    )
                    // 小窗按钮（纯图标，90% 大小）
                    if (onRequestPip != null) {
                        Icon(
                            painter = painterResource(com.example.rcgallery.R.drawable.ic_pip),
                            contentDescription = "小窗播放",
                            tint = Color.White,
                            modifier = Modifier
                                .size(22.dp)
                                .clickable {
                                    AppLogger.d("VideoPlayer", "PiP button clicked")
                                    onRequestPip()
                                }
                        )
                    }
                }
            }

            // ── 音量按钮（PiP 时隐藏）──
            if (!hideUiOverlays) {
                Box(modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 128.dp).size(40.dp)
                    .background(color = Color.White.copy(alpha = 0.3f), shape = CircleShape).clickable { onVolumeToggle() }, contentAlignment = Alignment.Center) {
                    Icon(painter = painterResource(if (volumeEnabled) com.example.rcgallery.R.drawable.ic_volume_up else com.example.rcgallery.R.drawable.ic_volume_off),
                        contentDescription = if (volumeEnabled) "有声音" else "静音", modifier = Modifier.size(22.dp))
                }
            }

            // ── 速度/长按设置弹窗（齿轮按钮触发）──
            if (showSpeedSettings) {
                // 用本地 mutableState 包裹滑条值，确保 Compose 重组
                var localSpeed by remember { mutableFloatStateOf(InertiaSettings.longPressSpeed) }
                var localTimeout by remember { mutableFloatStateOf(InertiaSettings.longPressTimeoutSec) }

                AlertDialog(
                    onDismissRequest = { showSpeedSettings = false },
                    title = { Text("播放设置") },
                    text = {
                        Column {
                            Text("播放速度", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                speedOptions.forEach { spd ->
                                    FilterChip(
                                        selected = localSpeed == spd,
                                        onClick = {
                                            localSpeed = spd
                                            InertiaSettings.longPressSpeed = spd
                                            prefs.edit().putFloat("long_press_speed", spd).apply()
                                        },
                                        label = { Text("${spd.toInt()}x", fontSize = 12.sp) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("长按触发时间：${localTimeout.toInt()} 秒",
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Slider(
                                value = localTimeout,
                                onValueChange = { v ->
                                    localTimeout = v
                                    InertiaSettings.longPressTimeoutSec = v
                                    prefs.edit().putFloat("long_press_timeout_sec", v).apply()
                                },
                                valueRange = 0.3f..3.0f,
                                steps = 26  // 0.3~3.0, step 0.1 → 27 values, 26 intervals
                            )
                            Text("当前倍速：${localSpeed.toInt()}x，长按 ${"%.1f".format(localTimeout)} 秒",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    confirmButton = { Button(onClick = { showSpeedSettings = false }) { Text("完成") } }
                )
            }
        }
    }
}

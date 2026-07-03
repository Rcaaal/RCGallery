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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.media3.exoplayer.ExoPlayer
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
    onControlZoneActive: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    onRequestPip: (() -> Unit)? = null,
    hideUiOverlays: Boolean = false,
    keepControllerVisible: Boolean = false,
    onShowInfoClick: () -> Unit = {},
    onMoveToTrash: () -> Unit = {},
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
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri.toString()))
            prepare()
            playWhenReady = isActive
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
                    val density = ctx.resources.displayMetrics.density
                    // 从 XML 加载 surface_type="texture_view" — Media3 1.6.1 不支持程序化设置
                    val pvAttrs = ctx.resources.getXml(com.example.rcgallery.R.xml.player_view_texture).let { parser ->
                        parser.next(); parser.nextTag(); Xml.asAttributeSet(parser)
                    }
                    val pv = PlayerView(ctx, pvAttrs).apply {
                        player = exoPlayer; useController = true; keepScreenOn = true; controllerShowTimeoutMs = 3000
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controllerVisible = v == android.view.View.VISIBLE })
                    }
                    pvRef.value = pv

                    // 拦截齿轮按钮→弹出自定义速度设置（而不是 ExoPlayer 原生设置）
                    pv.post {
                        val sid = ctx.resources.getIdentifier("exo_settings", "id", ctx.packageName)
                        if (sid != 0) pv.findViewById<View>(sid)?.setOnClickListener {
                            showSpeedSettings = true
                        }
                    }

                    // ── seek 区边界（控制栏固定，锁定后永不重新计算）──
                    val tmLeft = intArrayOf(0); val tmRight = intArrayOf(0)
                    val tmTop = intArrayOf(0); val tmBot = intArrayOf(0)
                    var zonesReady = false

                    fun findTimeBar(v: View): View? {
                        if (v.javaClass.name == "androidx.media3.ui.DefaultTimeBar") return v
                        if (v is ViewGroup) for (i in 0 until v.childCount) { findTimeBar(v.getChildAt(i))?.let { return it } }
                        return null
                    }

                    // 尝试锁定 seek 区坐标，成功后不再重入（控制栏固定）
                    fun tryLockZones() {
                        if (zonesReady) return
                        findTimeBar(pv)?.let { tb ->
                            try {
                                tb.visibility = android.view.View.INVISIBLE
                                tb.setPadding(0, 0, 0, 0)
                                tb.invalidate()
                            } catch (_: Exception) {}
                        }
                        val sw = ctx.resources.displayMetrics.widthPixels
                        val sh = ctx.resources.displayMetrics.heightPixels
                        val barZoneH = (120 * density).toInt()
                        tmTop[0] = sh - barZoneH; tmBot[0] = sh

                        val left = 0
                        // 右边界 = 屏幕右边缘 - 齿轮宽度(约48dp)，不依赖动态布局坐标
                        val gearWidthPx = (56 * density).toInt()
                        val right = sw - gearWidthPx
                        if (right > 0) {
                            tmLeft[0] = left; tmRight[0] = right; zonesReady = true
                            AppLogger.d("VideoPlayer", "zone LOCKED [${tmLeft[0]},${tmRight[0]}]")
                        }
                    }

                    pv.post { tryLockZones() }
                    pv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) { pv.post { tryLockZones() } }
                        override fun onViewDetachedFromWindow(v: View) {}
                    })
                    pv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> pv.post { tryLockZones() } }

                    // ── 长按倍速 Runnable ──
                    val lpr = Runnable {
                        if (exoPlayer.playWhenReady) { val s = InertiaSettings.longPressSpeed; exoPlayer.setPlaybackSpeed(s); speedBoosted[0] = true; speedText = "${s.toInt()}x"; pv.hideController() }
                    }

                    // 时间区滑动状态
                    val seeking = booleanArrayOf(false)
                    val seekStartX = floatArrayOf(0f)
                    var seekLogged = false

                    // ── 覆盖层（带调试虚线框绘制）──
                    val overlay = object : View(ctx) {
                        private val dPaint = android.graphics.Paint().apply {
                            isAntiAlias = true; style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f
                        }
                        override fun onDraw(canvas: android.graphics.Canvas) {
                            super.onDraw(canvas)
                            if (!InertiaSettings.showControlZoneDebug) return
                            val off = IntArray(2); getLocationOnScreen(off)
                            val sh = ctx.resources.displayMetrics.heightPixels
                            val barZoneH = (120 * ctx.resources.displayMetrics.density).toInt()
                            if (zonesReady) {
                                // 黄色虚线 = seek 区
                                dPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f, 5f), 0f)
                                dPaint.color = android.graphics.Color.YELLOW
                                canvas.drawRect(android.graphics.Rect(tmLeft[0] - off[0], tmTop[0] - off[1], tmRight[0] - off[0], tmBot[0] - off[1]), dPaint)
                            }
                            // 绿色虚线 = 控制栏按钮区（百分比，控制栏按钮双击不触发区）
                            dPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 6f), 0f)
                            dPaint.color = android.graphics.Color.GREEN
                            val cbTopG = sh - (sh * InertiaSettings.controlBarZonePercent / 100).toInt()
                            canvas.drawRect(android.graphics.Rect(0, cbTopG - off[1], width, sh - off[1]), dPaint)
                            dPaint.pathEffect = null
                        }
                    }.apply {
                        val self = this
                        isClickable = true; setWillNotDraw(false)
                        // 调试框启用时持续重新绘制，确保百分比滑块实时更新绿框
                        val invalidator = object : java.lang.Runnable {
                            override fun run() {
                                if (InertiaSettings.showControlZoneDebug) { self.invalidate(); self.postDelayed(this, 200) }
                            }
                        }
                        pv.post { invalidate(); post { invalidate() } }
                        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) { postDelayed(invalidator, 200) }
                            override fun onViewDetachedFromWindow(v: View) { removeCallbacks(invalidator) }
                        })
                        pv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> invalidate() }
                    }
                    overlay.setOnTouchListener { v, ev ->
                        val ry = ev.rawY.toInt(); val rx = ev.rawX.toInt()
                        val inSeekZone = zonesReady && ry in tmTop[0]..tmBot[0] && rx in tmLeft[0]..tmRight[0]
                        val sh = ctx.resources.displayMetrics.heightPixels
                        val cbTop = sh - (sh * InertiaSettings.controlBarZonePercent / 100).toInt()
                        val inControlBarZone = cbTop > 0 && ry >= cbTop && !inSeekZone

                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                v.removeCallbacks(lpr); seeking[0] = false; seekLogged = false
                                // 控制栏按钮区 → 立即清空双击计时器
                                if (inControlBarZone) lastTapTime[0] = 0L
                                AppLogger.d("VideoPlayer", "[touch] DOWN seek=${if (inSeekZone)"Y" else "N"} cb=${if (inControlBarZone)"Y" else "N"} controller=${controllerVisible} pos=${exoPlayer.currentPosition}")
                                if (inSeekZone) {
                                    onControlZoneActive?.invoke(true)
                                    lastTapTime[0] = 0L
                                    seeking[0] = true; seekStartX[0] = ev.rawX
                                } else if (!inControlBarZone) {
                                    // 上方视频区才启动长按倍速计时（控制栏按钮区不触发）
                                    if (exoPlayer.playWhenReady) { v.postDelayed(lpr, (InertiaSettings.longPressTimeoutSec * 1000).toLong()) }
                                    pv.dispatchTouchEvent(MotionEvent.obtain(ev))
                                } else {
                                    pv.dispatchTouchEvent(MotionEvent.obtain(ev))
                                }
                                true
                            }

                            MotionEvent.ACTION_MOVE -> {
                                if (seeking[0]) {
                                    if (!seekLogged) { seekLogged = true; AppLogger.d("VideoPlayer", "[touch] seeking start") }
                                    val dx = ev.rawX - seekStartX[0]
                                    val dur = exoPlayer.duration.coerceAtLeast(1)
                                    val sw = ctx.resources.displayMetrics.widthPixels
                                    val dSeek = (dx / sw * dur).toLong()
                                    val np = (exoPlayer.currentPosition + dSeek).coerceIn(0, dur)
                                    exoPlayer.seekTo(np)
                                    seekStartX[0] = ev.rawX
                                    pv.showController()
                                } else {
                                    pv.dispatchTouchEvent(MotionEvent.obtain(ev))
                                }
                                true
                            }

                            MotionEvent.ACTION_UP -> {
                                v.removeCallbacks(lpr)
                                onControlZoneActive?.invoke(false)
                                val now = SystemClock.uptimeMillis()

                                if (seeking[0]) {
                                    AppLogger.d("VideoPlayer", "[touch] seeking done @ ${exoPlayer.currentPosition}/${exoPlayer.duration}")
                                    seeking[0] = false; lastTapTime[0] = 0L
                                    pv.showController()
                                } else if (inControlBarZone) {
                                    // 控制栏按钮区 → 不触发双击，保护快进/快退按钮
                                    lastTapTime[0] = 0L
                                    pv.dispatchTouchEvent(MotionEvent.obtain(ev))
                                } else if (now - lastTapTime[0] < DOUBLE_TAP_TIMEOUT_MS) {
                                    lastTapTime[0] = 0L; v.removeCallbacks(lpr)
                                    exoPlayer.playWhenReady = !exoPlayer.playWhenReady; pv.showController()
                                    if (speedBoosted[0]) { exoPlayer.setPlaybackSpeed(1f); speedBoosted[0] = false; speedText = "" }
                                } else if (speedBoosted[0]) {
                                    val cancel = MotionEvent.obtain(ev.downTime, ev.eventTime, MotionEvent.ACTION_CANCEL, ev.x, ev.y, ev.metaState)
                                    pv.dispatchTouchEvent(cancel); cancel.recycle()
                                    exoPlayer.setPlaybackSpeed(1f); speedBoosted[0] = false; speedText = "1x"; pv.hideController()
                                } else if (controllerVisible) {
                                    // 控制栏可见且不在按钮区 → 支持双击暂停，单击转发给 PlayerView（隐藏）
                                    if (now - lastTapTime[0] < DOUBLE_TAP_TIMEOUT_MS) {
                                        lastTapTime[0] = 0L
                                        exoPlayer.playWhenReady = !exoPlayer.playWhenReady
                                        pv.showController()
                                    } else {
                                        lastTapTime[0] = now
                                        pv.dispatchTouchEvent(MotionEvent.obtain(ev))
                                    }
                                } else {
                                    lastTapTime[0] = now; pv.dispatchTouchEvent(MotionEvent.obtain(ev))
                                    v.postDelayed({ if (lastTapTime[0] != 0L) { lastTapTime[0] = 0L; pv.showController() } }, DOUBLE_TAP_TIMEOUT_MS)
                                }
                                true
                            }

                            MotionEvent.ACTION_CANCEL -> {
                                seeking[0] = false; v.removeCallbacks(lpr)
                                onControlZoneActive?.invoke(false)
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

            // ── 统一进度条（PiP 时隐藏）──
            if (!hideUiOverlays && playerDuration > 0f) {
                val barOffsetY by remember { derivedStateOf { if (controllerVisible) (-54).dp else 0.dp } }
                LinearProgressIndicator(
                    progress = { (playerPosition / playerDuration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter).offset(y = barOffsetY),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
            }

            AnimatedVisibility(visible = speedText.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Text(text = speedText, modifier = Modifier.padding(top = 80.dp), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            // ── 左侧按钮组（信息 + PiP，与控制面板同显隐，纯图标无背景，相同大小）──
            if (!hideUiOverlays && controllerVisible) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
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
                    // 小窗按钮（纯图标）
                    if (onRequestPip != null) {
                        Icon(
                            painter = painterResource(com.example.rcgallery.R.drawable.ic_pip),
                            contentDescription = "小窗播放",
                            tint = Color.White,
                            modifier = Modifier
                                .size(24.dp)
                                .clickable {
                                    AppLogger.d("VideoPlayer", "PiP button clicked")
                                    onRequestPip()
                                }
                        )
                    }
                }
            }

            // ── 删除按钮（常驻右上角，不随控制面板显隐，纯图标无背景）──
            if (!hideUiOverlays) {
                Icon(
                    painter = painterResource(com.example.rcgallery.R.drawable.ic_trash),
                    contentDescription = "移至回收站",
                    tint = Color(0xFFFF5252),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp)
                        .size(24.dp)
                        .clickable { onMoveToTrash() }
                )
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

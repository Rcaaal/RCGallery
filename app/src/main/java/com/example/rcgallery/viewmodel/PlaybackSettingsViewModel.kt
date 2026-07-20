package com.example.rcgallery.viewmodel

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 系统媒体音量控制器（STREAM_MUSIC）。
 *
 * ## 架构设计
 *
 * ### 深度计数（[muteCount]）
 * 进入预览 / 翻到视频页 → 增加 muteCount；退出预览 / 翻到图片页 → 减少 muteCount。
 * 只有 muteCount 从 0→1 时才保存系统音量快照，从 1→0 时才恢复。
 * 这消除了 LaunchedEffect 和 Lifecycle 回调之间的竞态条件——双方独立调用，深度计数保证配对正确。
 *
 * ### lastNonZero
 * 用户"上次手动设置的非零音量"，供 [toggleMute] 取消静音时恢复。
 * 退出预览时保留当前值不变——不受原始系统音量影响，
 * 确保下次进入预览时静音按钮能恢复到用户习惯的音量。
 *
 * - [VolumeState.level] 只做 UI 显示/滑条位置，不驱动 ExoPlayer
 * - [systemVolumeSnapshot] 精确保存进入预览前的系统音量档位
 */
data class VolumeState(
    val level: Float,       // 0f = 静音，用于 UI 显示 & 滑条位置
    val lastNonZero: Float  // 上次非零音量（最低 0.05f），给 toggleMute 取消静音用
)

class PlaybackSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val audioManager =
        application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _volumeState = MutableStateFlow(loadInitialVolumeState())
    val volumeState: StateFlow<VolumeState> = _volumeState.asStateFlow()

    // ── 深度计数 + 系统音量快照（支持多次 mute/restore 配对）──
    private var muteCount = 0
    private var systemVolumeSnapshot = -1  // -1 = 未保存

    // ══════════════════════════════════════════
    //  系统音量读写
    // ══════════════════════════════════════════

    /** 读取当前系统媒体音量（归一化 0~1）。 */
    private fun readSystemVolume(): Float {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return (if (max > 0) current.toFloat() / max.toFloat() else 1f)
            .coerceIn(0f, 1f)
    }

    /** 将归一化 level 映射为系统音量索引并写入（flags=0，不显示系统音量面板）。 */
    private fun writeSystemVolume(level: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = (level.coerceIn(0f, 1f) * max).toInt().coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
    }

    // ══════════════════════════════════════════
    //  持久化
    // ══════════════════════════════════════════

    /** 启动时从系统音量读取当前值，[lastNonZero] 从 SharedPreferences 恢复。 */
    private fun loadInitialVolumeState(): VolumeState {
        val sysLevel = readSystemVolume()
        val lastNonZero = prefs.getFloat(KEY_LAST_NON_ZERO, sysLevel.coerceAtLeast(MIN_NON_ZERO))
        return VolumeState(level = sysLevel, lastNonZero = lastNonZero)
    }

    private fun saveLastNonZero(value: Float) {
        prefs.edit().putFloat(KEY_LAST_NON_ZERO, value).apply()
    }

    // ══════════════════════════════════════════
    //  公开方法
    // ══════════════════════════════════════════

    /** 设置音量。直接控制系统 STREAM_MUSIC，同时更新 UI state。 */
    fun setVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        writeSystemVolume(clamped)
        val current = _volumeState.value
        val newState = if (clamped > 0f) {
            VolumeState(level = clamped, lastNonZero = clamped)
        } else {
            VolumeState(level = 0f, lastNonZero = current.lastNonZero)
        }
        _volumeState.value = newState
        if (clamped > 0f) saveLastNonZero(clamped)
    }

    /** 静音 ↔ 取消静音。控制系统音量，不覆盖预览入口保存的系统音量档位。 */
    fun toggleMute() {
        val current = _volumeState.value
        if (current.level > 0f) {
            // 静音：记住当前系统音量到 lastNonZero
            val sysLevel = readSystemVolume()
            _volumeState.value = VolumeState(level = 0f, lastNonZero = sysLevel)
            writeSystemVolume(0f)
        } else {
            // 取消静音：恢复到 lastNonZero
            val restore = current.lastNonZero.coerceIn(MIN_NON_ZERO, 1f)
            _volumeState.value = VolumeState(level = restore, lastNonZero = restore)
            writeSystemVolume(restore)
            saveLastNonZero(restore)
        }
    }

    /**
     * 进入预览/翻到视频页时调用：保存音量快照并静音。
     *
     * 深度计数 [muteCount] 始终只在 0↔1 之间切换：
     * - 第一次调用（0→1）：保存系统音量快照，设置静音。
     * - 后续调用（已为 1）：只确保系统音量 = 0，不重复计数。
     * 这消除了 DisposableEffect ON_START 和 LaunchedEffect(page) 同时调用
     * 导致 muteCount 虚高的竞态（修复频繁快速操作 UI/音量不匹配 Bug）。
     */
    fun muteSystemOnEnter() {
        val firstEntry = muteCount == 0
        if (firstEntry) {
            systemVolumeSnapshot =
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val beforeLevel = if (max > 0) {
                systemVolumeSnapshot.toFloat() / max.toFloat()
            } else 0f
            val lastNonZero = if (beforeLevel > 0f) beforeLevel else _volumeState.value.lastNonZero
            _volumeState.value = VolumeState(level = 0f, lastNonZero = lastNonZero)
            muteCount = 1
        }
        writeSystemVolume(0f)
    }

    /**
     * 退出预览/翻到图片页时调用：减少深度计数，归零时恢复系统音量。
     *
     * 幂等：[muteCount <= 0] 时直接返回，无副作用。
     * 恢复后保留当前的 [lastNonZero]（用户习惯音量），不因原始音量为 0 而覆盖，
     * 确保下次进入时 toggleMute 能恢复到用户上次设定的非零音量。
     */
    fun restoreSystemVolume() {
        if (muteCount <= 0) return
        muteCount = 0
        if (systemVolumeSnapshot >= 0) {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val restoreIndex = systemVolumeSnapshot.coerceIn(0, max)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreIndex, 0)
            systemVolumeSnapshot = -1
            val restoreLevel = if (max > 0) restoreIndex.toFloat() / max.toFloat() else 0f
            val currentLastNonZero = _volumeState.value.lastNonZero
            _volumeState.value = VolumeState(
                level = restoreLevel,
                lastNonZero = if (restoreLevel > 0f) restoreLevel else currentLastNonZero
            )
            if (restoreLevel > 0f) saveLastNonZero(restoreLevel)
        }
    }

    companion object {
        private const val PREFS_NAME = "playback_settings"
        private const val KEY_LAST_NON_ZERO = "volume_last_non_zero"
        private const val MIN_NON_ZERO = 0.05f
    }
}

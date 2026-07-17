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
 * - `setVolume` / `toggleMute` 直接操作系统音量，同时更新 UI state
 * - `muteSystemOnEnter` / `restoreSystemVolume` 用于预览页面的进入静音 & 退出恢复
 * - [VolumeState.level] 只做 UI 显示/滑条位置，不驱动 ExoPlayer
 * - [VolumeState.lastNonZero] 用于 toggleMute 取消静音时的恢复值
 * - `beforePreviewSystemVolumeIndex` 精确保存进入预览前的系统音量档位
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

    // ── 页面生命周期恢复（进入预览时记住，退出时恢复）──
    private var enterMuteApplied = false
    private var beforePreviewSystemVolumeIndex = 0

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
     * 进入预览页时调用：记住当前系统音量并静音。
     * 只生效一次（[enterMuteApplied] 保护），退出时需调 [restoreSystemVolume] 重置。
     */
    fun muteSystemOnEnter() {
        if (enterMuteApplied) return
        enterMuteApplied = true
        beforePreviewSystemVolumeIndex =
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val beforeLevel = if (max > 0) {
            beforePreviewSystemVolumeIndex.toFloat() / max.toFloat()
        } else 0f
        val lastNonZero = if (beforeLevel > 0f) beforeLevel else _volumeState.value.lastNonZero
        _volumeState.value = VolumeState(level = 0f, lastNonZero = lastNonZero)
        writeSystemVolume(0f)
    }

    /** 退出预览页时调用：恢复进入前的系统音量。幂等，多次调用安全。 */
    fun restoreSystemVolume() {
        if (!enterMuteApplied) return
        enterMuteApplied = false
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val restoreIndex = beforePreviewSystemVolumeIndex.coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreIndex, 0)
        val restoreLevel = if (max > 0) restoreIndex.toFloat() / max.toFloat() else 0f
        val lastNonZero = if (restoreLevel > 0f) restoreLevel else _volumeState.value.lastNonZero
        _volumeState.value = VolumeState(level = restoreLevel, lastNonZero = lastNonZero)
        if (restoreLevel > 0f) saveLastNonZero(restoreLevel)
    }

    companion object {
        private const val PREFS_NAME = "playback_settings"
        private const val KEY_LAST_NON_ZERO = "volume_last_non_zero"
        private const val MIN_NON_ZERO = 0.05f
    }
}

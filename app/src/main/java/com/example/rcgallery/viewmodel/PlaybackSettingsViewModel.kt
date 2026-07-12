package com.example.rcgallery.viewmodel

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 音量状态：单点共享，由 MainActivity 作用域持有。
 *
 * level == 0f 表示静音，lastNonZero 记录上一次非零音量。
 * 静音恢复时回到 lastNonZero，而非硬编码 1f。
 *
 * 数据持久化到 SharedPreferences，退出重进保留。
 */
data class VolumeState(
    val level: Float,       // 0f = 静音
    val lastNonZero: Float  // 上次非零音量（最低 0.05f）
)

class PlaybackSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _volumeState = MutableStateFlow(loadVolumeState())
    val volumeState: StateFlow<VolumeState> = _volumeState.asStateFlow()

    // ── 持久化 ──

    private fun loadVolumeState(): VolumeState {
        // 版本迁移：PREF_VERSION 不存在说明是首次加载（含升级），忽略旧值，使用默认值
        if (!prefs.contains(KEY_PREF_VERSION)) {
            prefs.edit().putInt(KEY_PREF_VERSION, CURRENT_VERSION).apply()
            val initialLastNonZero = readSystemVolume()
            return VolumeState(level = DEFAULT_LEVEL, lastNonZero = initialLastNonZero)
        }
        val level = prefs.getFloat(KEY_LEVEL, DEFAULT_LEVEL)
        val lastNonZero = prefs.getFloat(KEY_LAST_NON_ZERO, MIN_NON_ZERO)
        return VolumeState(level = level, lastNonZero = lastNonZero)
    }

    /** 读取 Android 当前系统媒体音量（归一化 0~1）。 */
    private fun readSystemVolume(): Float {
        val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 1f
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return (if (max > 0) current.toFloat() / max.toFloat() else 1f).coerceIn(MIN_NON_ZERO, 1f)
    }

    private fun saveVolumeState(state: VolumeState) {
        prefs.edit()
            .putFloat(KEY_LEVEL, state.level)
            .putFloat(KEY_LAST_NON_ZERO, state.lastNonZero)
            .apply()
    }

    // ── 公开方法 ──

    /** 设置音量。传入 >0f 时会同步更新 [lastNonZero]。 */
    fun setVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        val current = _volumeState.value
        val newState = if (clamped > 0f) {
            VolumeState(level = clamped, lastNonZero = clamped)
        } else {
            // 滑到 0：静音，但保留 lastNonZero
            VolumeState(level = 0f, lastNonZero = current.lastNonZero)
        }
        _volumeState.value = newState
        saveVolumeState(newState)
    }

    /** 静音 ↔ 取消静音（恢复到上一次非零音量）。 */
    fun toggleMute() {
        val current = _volumeState.value
        val newState = if (current.level > 0f) {
            // 静音：记住当前值
            VolumeState(level = 0f, lastNonZero = current.level)
        } else {
            // 取消静音：恢复到 lastNonZero
            val restore = current.lastNonZero.coerceIn(MIN_NON_ZERO, 1f)
            VolumeState(level = restore, lastNonZero = restore)
        }
        _volumeState.value = newState
        saveVolumeState(newState)
    }

    companion object {
        private const val PREFS_NAME = "playback_settings"
        private const val KEY_LEVEL = "volume_level"
        private const val KEY_LAST_NON_ZERO = "volume_last_non_zero"
        private const val KEY_PREF_VERSION = "pref_version"
        private const val CURRENT_VERSION = 1
        private const val DEFAULT_LEVEL = 0f
        private const val MIN_NON_ZERO = 0.05f
    }
}

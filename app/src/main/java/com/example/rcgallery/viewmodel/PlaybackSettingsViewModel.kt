package com.example.rcgallery.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-local preview volume. It never modifies the device STREAM_MUSIC level. */
data class VolumeState(
    val level: Float,       // 0f = 静音，用于 UI 显示 & 滑条位置
    val lastNonZero: Float  // 上次非零音量（最低 0.05f），给 toggleMute 取消静音用
)

class PlaybackSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _volumeState = MutableStateFlow(
        VolumeState(
            level = 0f,
            lastNonZero = prefs.getFloat(KEY_LAST_NON_ZERO, DEFAULT_NON_ZERO)
                .coerceIn(MIN_NON_ZERO, 1f)
        )
    )
    val volumeState: StateFlow<VolumeState> = _volumeState.asStateFlow()

    // ══════════════════════════════════════════
    //  持久化
    // ══════════════════════════════════════════

    private fun saveLastNonZero(value: Float) {
        prefs.edit().putFloat(KEY_LAST_NON_ZERO, value).apply()
    }

    /** Each newly opened local or SMB preview starts muted without touching system audio. */
    fun startMutedPreview() {
        _volumeState.value = _volumeState.value.copy(level = 0f)
    }

    // ══════════════════════════════════════════
    //  公开方法
    // ══════════════════════════════════════════

    /** Updates only the active app player volume. */
    fun setVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        val current = _volumeState.value
        val newState = if (clamped > 0f) {
            VolumeState(level = clamped, lastNonZero = clamped)
        } else {
            VolumeState(level = 0f, lastNonZero = current.lastNonZero)
        }
        _volumeState.value = newState
        if (clamped > 0f) saveLastNonZero(clamped)
    }

    /** Toggles the active app player without altering device volume. */
    fun toggleMute() {
        val current = _volumeState.value
        if (current.level > 0f) {
            _volumeState.value = current.copy(level = 0f)
        } else {
            val restore = current.lastNonZero.coerceIn(MIN_NON_ZERO, 1f)
            _volumeState.value = VolumeState(level = restore, lastNonZero = restore)
            saveLastNonZero(restore)
        }
    }

    companion object {
        private const val PREFS_NAME = "playback_settings"
        private const val KEY_LAST_NON_ZERO = "volume_last_non_zero"
        private const val MIN_NON_ZERO = 0.05f
        private const val DEFAULT_NON_ZERO = 1f
    }
}

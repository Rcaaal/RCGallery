package com.example.rcgallery.data

import android.content.Context

/**
 * 虚拟相册名存储 — SharedPreferences 封装。
 * 用于在重命名相册后立即生效（物理移动前或物理失败时保底）。
 */
class AlbumNameStore(context: Context) {

    private val prefs = context.getSharedPreferences("album_names", Context.MODE_PRIVATE)

    /**
     * 获取自定义名称，没有则返回 null。
     */
    fun getCustomName(bucketId: String): String? {
        return prefs.getString("bucket_$bucketId", null)
    }

    /**
     * 设置自定义名称（虚拟重命名，立即生效）。
     */
    fun setCustomName(bucketId: String, name: String) {
        prefs.edit().putString("bucket_$bucketId", name).apply()
    }

    /**
     * 清除自定义名称（物理移动成功后调用）。
     */
    fun removeCustomName(bucketId: String) {
        prefs.edit().remove("bucket_$bucketId").apply()
    }

    /**
     * 获取所有自定义名称映射，key=bucketId, value=customName。
     */
    fun getAllCustomNames(): Map<String, String> {
        return prefs.all
            .filterKeys { it.startsWith("bucket_") }
            .mapKeys { it.key.removePrefix("bucket_") }
            .mapValues { it.value as String }
    }
}

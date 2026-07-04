package com.example.rcgallery.data.smb

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.example.rcgallery.util.AppLogger
import java.io.File

/**
 * SMB 媒体缓存单例 — 管理 ExoPlayer 的 [SimpleCache]。
 *
 * 视频通过 [SmbDataSource] 从服务器读取时，[CacheDataSource] 将数据块写入此缓存，
 * 实现 seek/回放时走本地磁盘读取，避免重复网络传输。
 *
 * ### 缓存策略
 * - [LeastRecentlyUsedCacheEvictor] 限制上限 500MB，自动淘汰最近最少使用的文件
 * - 缓存目录 `context.cacheDir/smb_media_cache`，Android 低存储时可自动清理
 * - 跨 App 重启保留（进程被杀后不丢失已有缓存内容）
 *
 * ### 线程安全
 * 双重检查锁定 + @Volatile 保证线程安全初始化。
 * 每次 [getCache] 返回同一个 [SimpleCache] 实例。
 */
@UnstableApi
object SmbCacheManager {

    private const val MAX_CACHE_BYTES = 500L * 1024 * 1024  // 500 MB
    private const val CACHE_DIR = "smb_media_cache"
    private const val TAG = "SMB-CACHE"

    @Volatile
    private var _cache: SimpleCache? = null

    /**
     * 获取或创建 [SimpleCache] 实例。
     *
     * @param context ApplicationContext（会被转换为 applicationContext 防止 Activity 泄漏）
     */
    fun getCache(context: Context): SimpleCache {
        _cache?.let { return it }
        return synchronized(this) {
            _cache ?: SimpleCache(
                File(context.applicationContext.cacheDir, CACHE_DIR),
                LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
               StandaloneDatabaseProvider(context.applicationContext)
            ).also {
                _cache = it
                AppLogger.d(TAG, "SimpleCache created, maxBytes=$MAX_CACHE_BYTES")
            }
        }
    }

    /**
     * 释放缓存（通常在不需要缓存时调用；可选的，GC 会自动清理）。
     * 供 [DisposableEffect] 等生命周期挂钩调用。
     */
    fun release() {
        synchronized(this) {
            _cache?.release()
            _cache = null
            AppLogger.d(TAG, "SimpleCache released")
        }
    }
}

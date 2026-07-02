package com.example.rcgallery.util

import android.util.Log

/**
 * 统一日志系统：写 LogCat + 内存环形缓冲区，便于一键导出。
 * 用 [getLogs] 获取全部日志文本，配合 DevOverlay 复制。
 */
object AppLogger {
    private const val CAT = "RCGallery"
    private const val RING_SIZE = 500
    private val ring = arrayOfNulls<String>(RING_SIZE)
    private var cursor = 0
    private val lock = Any()

    private fun push(entry: String) {
        synchronized(lock) {
            ring[cursor] = entry
            cursor = (cursor + 1) % RING_SIZE
        }
    }

    fun d(tag: String, msg: String) {
        val line = "[${tag}] $msg"
        Log.d(CAT, line)
        push(line)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val line = "[${tag}] $msg | ${tr?.message ?: ""}"
        Log.e(CAT, line, tr)
        push("E: $line")
    }

    /** 获取全部缓存的日志文本（最旧→最新），用于复制。
     *  @param tagFilter 不为 null 时只返回 tag 匹配的条目（子串匹配，如 "VideoPlayer"） */
    fun getLogs(tagFilter: String? = null): String {
        synchronized(lock) {
            val sb = StringBuilder(RING_SIZE * 60)
            val isFull = ring[cursor] != null
            if (!isFull) {
                for (i in 0 until cursor) {
                    val line = ring[i] ?: continue
                    if (tagFilter == null || line.contains("[$tagFilter]")) sb.append(line).append('\n')
                }
            } else {
                var i = cursor
                var count = 0
                while (count < RING_SIZE) {
                    val line = ring[i] ?: continue
                    if (tagFilter == null || line.contains("[$tagFilter]")) sb.append(line).append('\n')
                    i = (i + 1) % RING_SIZE
                    count++
                }
            }
            return sb.toString()
        }
    }

    fun clear() {
        synchronized(lock) {
            ring.fill(null)
            cursor = 0
        }
    }
}

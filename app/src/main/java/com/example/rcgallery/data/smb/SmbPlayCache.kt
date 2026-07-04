package com.example.rcgallery.data.smb

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * SMB 播放缓存管理 — 管理视频从 SMB 复制到本地的临时文件。
 *
 * 每次播放视频时，先完整复制到本地缓存，再从本地文件播放。
 * 关闭预览时删除缓存文件。
 *
 * 目录：context.cacheDir/smb_play_cache/
 * 文件名基于 SMB URL 的 MD5 哈希，重复点击同一 URL 直接复用缓存。
 */
object SmbPlayCache {

    private const val CACHE_DIR = "smb_play_cache"

    /**
     * 获取缓存目录（不存在时创建）。
     */
    private fun getDir(context: Context): File {
        val dir = File(context.applicationContext.cacheDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 根据 SMB URL 解析出本第缓存文件路径。
     * 文件可能不存在，需要调用方写入。
     * 相同的 URL 返回相同的路径（MD5 哈希）。
     */
    fun resolveFile(context: Context, url: String): File {
        val hash = MessageDigest.getInstance("MD5")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(getDir(context), "smb_video_$hash.tmp")
    }

    /**
     * 删除指定的缓存文件。
     */
    fun deleteFile(file: File) {
        if (file.exists()) {
            file.delete()
        }
    }

    /**
     * 删除全部缓存文件。建议在 App 启动时调用。
     */
    fun cleanAll(context: Context) {
        val dir = getDir(context)
        dir.listFiles()?.forEach { it.delete() }
    }
}

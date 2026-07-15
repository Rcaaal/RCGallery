package com.example.rcgallery.data.smb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.rcgallery.R
import com.example.rcgallery.util.AppLogger
import jcifs.smb.SmbRandomAccessFile
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * CX 文件管理器式 SMB HTTP 代理服务 — Android Foreground Service。
 *
 * ### 架构（与 CX File Explorer 一致）
 *
 * ```text
 * ExoPlayer → DefaultHttpDataSource → http://127.0.0.1:{端口}/smb/{tag}
 *                                          ↓
 *                                   SmbProxyService (ServerSocket)
 *                                          ↓
 *                                   持久 SmbRandomAccessFile 句柄
 *                                          ↓
 *                                   SMB 服务器（通过 jcifs-ng）
 * ```
 *
 * ### 工作原理
 * - 启动时分配**动态端口**（ServerSocket(0)），与 CX 一致
 * - ExoPlayer 发送 HTTP `Range: bytes=X-` 请求
 * - 代理收到后 `raf.seek(position)`（<5ms）→ `raf.read()` → 返回 `206 Partial Content`
 * - 每次 seek 只需环回 HTTP 请求 + 一次 SMB seek，<10ms
 * - 持有 WifiLock，防止播放期间 Wi-Fi 休眠
 *
 * ### 与 CX 的区别
 * - ✅ Android Foreground Service（同 CX's HttpServerService）
 * - ✅ 动态端口分配（ServerSocket(0)，同 CX）
 * - ✅ WifiLock 防止休眠（同 CX）
 * - ✅ 持久 RAF 连接，不重建（同 CX）
 * - ✅ URL 格式 http://127.0.0.1:{端口}/smb/{tag}（同 CX）
 *
 * ### 使用方式
 * ```kotlin
 * // Compose 层调用
 * val intent = Intent(context, SmbProxyService::class.java)
 * context.startService(intent)
 * // 通过 SmbProxyService.register(url) 注册，返回 HTTP URL
 * // ExoPlayer 用 DefaultHttpDataSource 播放该 URL
 * // 关闭时 call SmbProxyService.unregister(tag) + context.stopService(intent)
 * ```
 */
class SmbProxyService : Service() {

    companion object {
        private const val TAG = "SMB-PROXY-SVC"
        private const val CHANNEL_ID = "smb_proxy_channel"
        private const val NOTIFICATION_ID = 1001
        private const val BACKLOG = 2
        /** Socket 读超时（毫秒） */
        private const val SO_TIMEOUT_MS = 30_000

        // ── 服务状态（供 UI 层读取）──
        @Volatile
        var serverPort: Int = 0
            private set
        @Volatile
        var isRunning: Boolean = false
            private set

        private val tagCounter = AtomicInteger(0)
        private val entries = ConcurrentHashMap<String, SmbEntry>()
        private val threadPool = Executors.newCachedThreadPool { r ->
            Thread(r, "smb-proxy").apply { isDaemon = true }
        }

        /**
         * 在服务中注册一个 SMB 文件，返回 HTTP 访问 URL。
         *
         * @param url 原始的 smb:// 路径
         * @return HTTP 访问 URL（http://127.0.0.1:{port}/smb/{tag}），失败返回 null
         */
        fun register(url: String): String? {
            val repo = SmbRepository.getInstance()
            val file = repo.getRandomAccessFile(url).getOrNull() ?: return null
            val length = file.length()
            val tag = tagCounter.incrementAndGet().toString()
            entries[tag] = SmbEntry(url, file, length)
            val httpUrl = "http://127.0.0.1:$serverPort/smb/$tag"
            AppLogger.d(TAG, "register: $httpUrl len=$length")
            return httpUrl
        }

        /**
         * 取消注册，关闭 SMB 文件句柄。
         */
        fun unregister(tag: String) {
            val entry = entries.remove(tag)
            if (entry != null) {
                threadPool.execute {
                    try { entry.smbFile.close() } catch (_: Exception) { }
                    AppLogger.d(TAG, "unregister: $tag (SMB closed)")
                }
            }
        }

        /**
         * 清除所有注册，停止服务时调用。
         */
        private fun clearAll() {
            entries.keys.toList().forEach { unregister(it) }
        }

        private data class SmbEntry(
            val url: String,
            val smbFile: SmbRandomAccessFile,
            val length: Long
        )
    }

    // ── Binder（供 Activity 绑定用，但我们用 static 方法更简单）──
    inner class LocalBinder : Binder() {
        fun getService(): SmbProxyService = this@SmbProxyService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ── 服务变量 ──
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY

        // ── 前台服务通知 ──
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // ── 启动动态端口 HTTP 服务器 ──
        try {
            val ss = ServerSocket(0, BACKLOG, java.net.InetAddress.getByName("127.0.0.1"))
            serverPort = ss.localPort
            serverSocket = ss
            isRunning = true
            val t = Thread({ acceptLoop(ss) }, "smb-http-accept")
            t.isDaemon = true
            t.start()
            acceptThread = t
            AppLogger.d(TAG, "server started on 127.0.0.1:$serverPort")
        } catch (e: Exception) {
            AppLogger.e(TAG, "start server failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // ── 获取 WifiLock（防止播放时 Wi-Fi 休眠）──
        try {
            val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SMB-PROXY")
            wifiLock?.acquire()
            AppLogger.d(TAG, "WifiLock acquired")
        } catch (e: Exception) {
            AppLogger.e(TAG, "WifiLock failed", e)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        AppLogger.d(TAG, "service destroying")
        isRunning = false
        // 停止 HTTP 服务器
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
        // 释放所有 SMB 连接
        clearAll()
        // 释放 WifiLock
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
        serverPort = 0
        AppLogger.d(TAG, "service destroyed")
        super.onDestroy()
    }

    // ══════════════════════════════════════
    //  HTTP 服务器
    // ══════════════════════════════════════

    private fun acceptLoop(ss: ServerSocket) {
        while (isRunning && !ss.isClosed) {
            try {
                val client = ss.accept()
                client.soTimeout = SO_TIMEOUT_MS
                threadPool.execute { handleClient(client) }
            } catch (e: Exception) {
                if (isRunning) AppLogger.e(TAG, "accept error", e)
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // ── 解析 HTTP 请求头 ──
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]
            if (method != "GET" && method != "HEAD") {
                sendResponse(output, 405, "Method Not Allowed")
                return
            }

            // 解析 /smb/{tag} 中的 tag
            val cleanPath = path.split("?").first()
            val tag = cleanPath.removePrefix("/smb/")
            val entry = entries[tag] ?: run {
                AppLogger.d(TAG, "404: tag=$tag")
                sendResponse(output, 404, "Not Found")
                return
            }

            // 解析 Range 头
            var rangeStr: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("range:")) {
                    rangeStr = line.substring(6).trim()
                }
            }

            val position: Long = if (rangeStr != null) {
                val m = Regex("bytes=(\\d+)-").find(rangeStr)
                m?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            } else 0L

            val fileLen = entry.length
            val remaining = fileLen - position
            if (remaining <= 0) {
                sendResponse(output, 416, "Range Not Satisfiable")
                return
            }

            // ── seek 到目标位置（RAF 原生 <5ms）──
            val file = entry.smbFile
            synchronized(file) {
            try { file.seek(position) } catch (e: Exception) {
                AppLogger.e(TAG, "seek failed pos=$position", e)
                sendResponse(output, 500, "Seek Error")
                return
            }

            // ── 返回 206 Partial Content（不限制每次响应大小，ExoPlayer 自行控制）──
            val contentLen = remaining
            val contentType = when {
                entry.url.endsWith(".wmv", ignoreCase = true) -> "video/x-ms-wmv"
                entry.url.endsWith(".asf", ignoreCase = true) -> "video/x-ms-asf"
                else -> "application/octet-stream"
            }
            val respBytes = ("HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: $contentLen\r\n" +
                    "Content-Range: bytes $position-${position + contentLen - 1}/$fileLen\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n").toByteArray()

            try {
                output.write(respBytes)
                output.flush()
                if (method == "HEAD") return
                val buf = ByteArray(256 * 1024)
                var remainingRead = contentLen
                while (remainingRead > 0) {
                    val toRead = minOf(remainingRead, buf.size.toLong()).toInt()
                    val n = file.read(buf, 0, toRead)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    remainingRead -= n
                }
                output.flush()
            } catch (e: Exception) {
                // 客户端提前关闭（seek 后播放器拿到数据会关闭连接）
                AppLogger.d(TAG, "client closed: ${e.message}")
            }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "handle error", e)
        } finally {
            try { client.close() } catch (_: Exception) { }
        }
    }

    // ══════════════════════════════════════
    //  工具
    // ══════════════════════════════════════

    private fun readLine(input: InputStream): String? {
        val bos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bos.size() == 0) null else bos.toString("UTF-8")
            if (b == 13) continue
            if (b == 10) break
            bos.write(b)
        }
        return bos.toString("UTF-8")
    }

    private fun sendResponse(output: OutputStream, code: Int, msg: String) {
        val text = "HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(text.toByteArray())
        output.flush()
    }

    // ══════════════════════════════════════
    //  通知
    // ══════════════════════════════════════

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "SMB 代理服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "SMB 视频流式播放代理" }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMB 流式播放")
            .setContentText("正在通过 SMB 协议流式播放视频")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

package com.example.rcgallery.data.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.OPEN_READONLY
import android.webkit.CookieManager
import java.io.File

/**
 * Extracts YouTube cookies from WebView's embedded SQLite cookie store
 * and writes them in Netscape format for yt-dlp's --cookies option.
 *
 * Pattern from ytdlnis: CookieViewModel.getCookiesFromDB() + updateCookiesFile().
 */
object YouTubeCookieExtractor {

    private const val HEADER = "# Netscape HTTP Cookie File\n" +
        "# Extracted by RCGallery via WebView\n" +
        "# This is a generated file! Do not edit.\n"

    private val projection = arrayOf(
        COL_HOST, COL_EXPIRY, COL_PATH, COL_NAME, COL_VALUE, COL_SECURE
    )

    fun extractAndSave(context: Context, targetFile: File): Boolean {
        val entries = readWebViewCookies(context) ?: return false
        if (entries.isEmpty()) return false

        targetFile.parentFile?.mkdirs()
        targetFile.writeText(buildNetscapeFile(entries))
        return true
    }

    @SuppressLint("SdCardPath")
    private fun readWebViewCookies(context: Context): List<CookieEntry>? {
        runCatching { CookieManager.getInstance().flush() }

        // WebView changes this location between Android/WebView versions.
        // Follow ytdlnis and locate the active Chromium cookie database.
        val dbPath = File(context.applicationInfo.dataDir)
            .walkTopDown()
            .firstOrNull { it.isFile && it.name == "Cookies" }
            ?: return null

        val entries = mutableListOf<CookieEntry>()
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(dbPath.absolutePath, null, OPEN_READONLY)
            db.query("cookies", projection, null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val hostKey = cursor.getString(cursor.getColumnIndexOrThrow(COL_HOST))
                    entries.add(CookieEntry(
                        domain = if (hostKey[0] != '.') ".$hostKey" else hostKey,
                        expiry = cursor.getLong(cursor.getColumnIndexOrThrow(COL_EXPIRY)),
                        path = cursor.getString(cursor.getColumnIndexOrThrow(COL_PATH)),
                        name = cursor.getString(cursor.getColumnIndexOrThrow(COL_NAME)),
                        value = cursor.getString(cursor.getColumnIndexOrThrow(COL_VALUE)),
                        secure = cursor.getLong(cursor.getColumnIndexOrThrow(COL_SECURE)) == 1L,
                    ))
                }
            }
        } catch (_: Exception) { return null }
        finally { db?.close() }

        return entries
    }

    /** Fallback when SQLite DB is inaccessible. */
    fun saveFallbackCookies(targetFile: File): Boolean {
        val raw = runCatching {
            CookieManager.getInstance().getCookie("https://www.youtube.com")
        }.getOrNull() ?: return false
        if (raw.isBlank()) return false

        val sb = StringBuilder(HEADER)
        for (pair in raw.split(";").map { it.trim() }.filter { it.isNotBlank() }) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq)
            val value = pair.substring(eq + 1)
            sb.append(".youtube.com\tTRUE\t/\tTRUE\t0\t$name\t$value\n")
        }
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(sb.toString())
        return true
    }

    private fun buildNetscapeFile(entries: List<CookieEntry>): String {
        val sb = StringBuilder(HEADER)
        for (e in entries) {
            sb.append(e.domain).append("\tTRUE\t${e.path}")
                .append(if (e.secure) "\tTRUE" else "\tFALSE")
                .append("\t${e.expiry}\t${e.name}\t${e.value}\n")
        }
        return sb.toString()
    }

    private data class CookieEntry(
        val domain: String, val expiry: Long, val path: String,
        val name: String, val value: String, val secure: Boolean,
    )

    private const val COL_HOST = "host_key"
    private const val COL_EXPIRY = "expires_utc"
    private const val COL_PATH = "path"
    private const val COL_NAME = "name"
    private const val COL_VALUE = "value"
    private const val COL_SECURE = "is_secure"
}

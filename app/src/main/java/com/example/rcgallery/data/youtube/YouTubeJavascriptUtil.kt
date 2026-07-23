package com.example.rcgallery.data.youtube

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Small, dependency-free port of ytdlnis' PO token JSON/byte helpers. */
internal object YouTubeJavascriptUtil {
    fun parseChallengeData(raw: String): String {
        val scrambled = JSONArray(raw)
        val challenge = if (scrambled.opt(1) is String) {
            JSONArray(String(descramble(scrambled.getString(1)), Charsets.UTF_8))
        } else {
            scrambled.getJSONArray(1)
        }
        val safe = challenge.optJSONArray(1)?.firstString() ?: JSONObject.NULL
        val trusted = challenge.optJSONArray(2)?.firstString() ?: JSONObject.NULL
        return JSONObject().apply {
            put("messageId", challenge.getString(0))
            put("interpreterJavascript", JSONObject().apply {
                put("privateDoNotAccessOrElseSafeScriptWrappedValue", safe)
                put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", trusted)
            })
            put("interpreterHash", challenge.getString(3))
            put("program", challenge.getString(4))
            put("globalName", challenge.getString(5))
            put("clientExperimentsStateBlob", challenge.getString(7))
        }.toString()
    }

    fun parseIntegrityTokenData(raw: String): Pair<String, Long> {
        val data = JSONArray(raw)
        return base64ToU8(data.getString(0)) to data.getLong(1)
    }

    fun stringToU8(value: String): String = newUint8Array(value.toByteArray())

    fun u8ToBase64(value: String): String {
        val bytes = value.split(',').filter { it.isNotBlank() }
            .map { it.trim().toInt().coerceIn(0, 255).toByte() }.toByteArray()
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun descramble(value: String): ByteArray {
        return decodeBase64(value).map { (it.toInt() + 97).toByte() }.toByteArray()
    }

    private fun base64ToU8(value: String): String = newUint8Array(decodeBase64(value))

    private fun newUint8Array(bytes: ByteArray): String =
        "new Uint8Array([${bytes.joinToString(",") { (it.toInt() and 0xff).toString() }}])"

    private fun decodeBase64(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/').replace('.', '=')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        return Base64.decode(normalized, Base64.DEFAULT)
    }

    private fun JSONArray.firstString(): String? =
        (0 until length()).asSequence().map { opt(it) }.filterIsInstance<String>().firstOrNull()
}

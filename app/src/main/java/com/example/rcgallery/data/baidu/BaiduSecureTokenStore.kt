package com.example.rcgallery.data.baidu

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class BaiduSecureTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): BaiduToken? = runCatching {
        val encoded = prefs.getString(KEY_TOKEN, null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        if (packed.size <= IV_SIZE) return null
        val iv = packed.copyOfRange(0, IV_SIZE)
        val encrypted = packed.copyOfRange(IV_SIZE, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        }
        val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        BaiduToken(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            expiresAtMillis = json.getLong("expires_at")
        )
    }.getOrNull()

    fun write(token: BaiduToken) {
        val plain = JSONObject()
            .put("access_token", token.accessToken)
            .put("refresh_token", token.refreshToken)
            .put("expires_at", token.expiresAtMillis)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val packed = cipher.iv + cipher.doFinal(plain)
        prefs.edit().putString(KEY_TOKEN, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFS_NAME = "rcgallery_baidu_secure"
        const val KEY_TOKEN = "oauth_token"
        const val KEY_ALIAS = "rcgallery_baidu_token_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

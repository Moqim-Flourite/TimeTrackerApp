package com.operit.timetracker.diary

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * 用 EncryptedSharedPreferences 安全存储 API key。
 * 加密不可用时 fallback 到普通 SharedPreferences（降级但不崩溃）。
 */
class ApiKeyManager(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "diary_secure_prefs",
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        context.getSharedPreferences("diary_prefs", Context.MODE_PRIVATE)
    }

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""

    fun setApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    fun hasApiKey(): Boolean = getApiKey().isNotBlank()

    companion object {
        private const val KEY_API_KEY = "openai_api_key"
    }
}

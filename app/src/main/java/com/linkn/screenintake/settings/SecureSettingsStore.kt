package com.linkn.screenintake.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 所有设置项集中存这里。API Key 敏感，整个文件都用 EncryptedSharedPreferences（AES-256）。
 */
class SecureSettingsStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** 通义千问 API Key（bailian.console.aliyun.com 申请，支付宝/微信支付充值） */
    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    /** 用 SAF 选定的保存文件夹（tree URI 的字符串形式），账本/待办/灵感都写在这里面 */
    var folderUri: String
        get() = prefs.getString(KEY_FOLDER_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FOLDER_URI, value).apply()

    /** 用户自己写的分类偏好，每次分类都会带上一起发给模型 */
    var classifyRules: String
        get() = prefs.getString(KEY_RULES, DEFAULT_RULES) ?: DEFAULT_RULES
        set(value) = prefs.edit().putString(KEY_RULES, value).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    fun isReady(): Boolean = apiKey.isNotBlank() && folderUri.isNotBlank()

    companion object {
        private const val PREFS_NAME = "screen_intake_secure"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_RULES = "classify_rules"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"

        const val DEFAULT_RULES =
            "美团/饿了么买菜算日用，不算餐饮。\n" +
                "打车、加油、停车算交通。\n" +
                "跟客户/同事一起吃饭算人情，不算餐饮。\n" +
                "拿不准分类就填「其他」，但不要因为分类拿不准就放弃识别这是一笔支出。"
    }
}

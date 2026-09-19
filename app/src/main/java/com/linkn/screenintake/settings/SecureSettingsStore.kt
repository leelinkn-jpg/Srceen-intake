package com.linkn.screenintake.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.linkn.screenintake.classify.ExpensePurpose
import com.linkn.screenintake.classify.Categories
import org.json.JSONArray

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

    /** 用 SAF 选定的秒记同步根目录；App 会在其中按财务、健康、工作、成长分目录保存。 */
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

    var englishWeeklyMinutes: Int
        get() = prefs.getInt(KEY_ENGLISH_WEEKLY_MINUTES, 180)
        set(value) = prefs.edit().putInt(KEY_ENGLISH_WEEKLY_MINUTES, value.coerceIn(10, 1_680)).apply()

    var investingWeeklyMinutes: Int
        get() = prefs.getInt(KEY_INVESTING_WEEKLY_MINUTES, 120)
        set(value) = prefs.edit().putInt(KEY_INVESTING_WEEKLY_MINUTES, value.coerceIn(10, 1_680)).apply()

    fun isReady(): Boolean = apiKey.isNotBlank() && folderUri.isNotBlank()

    var expensePurposes: List<String>
        get() {
            val raw = prefs.getString(KEY_EXPENSE_PURPOSES, null) ?: return ExpensePurpose.defaults
            return runCatching {
                val array = JSONArray(raw)
                ExpensePurpose.normalize((0 until array.length()).map { array.getString(it) })
            }.getOrDefault(ExpensePurpose.defaults)
        }
        set(value) {
            val normalized = ExpensePurpose.normalize(value)
            require(normalized.all(ExpensePurpose::valid)) { "用途为 1–20 个字，不能包含换行或【】" }
            prefs.edit().putString(KEY_EXPENSE_PURPOSES, JSONArray(normalized).toString()).apply()
        }

    /** 支出类别和用途分开保存：类别回答花在什么上，用途回答为什么/为谁花。 */
    var expenseCategories: List<String>
        get() {
            val raw = prefs.getString(KEY_EXPENSE_CATEGORIES, null) ?: return Categories.EXPENSE
            return runCatching {
                val array = JSONArray(raw)
                ExpensePurpose.normalize((0 until array.length()).map { array.getString(it) })
            }.getOrDefault(Categories.EXPENSE)
        }
        set(value) {
            val normalized = ExpensePurpose.normalize(value)
            require(normalized.isNotEmpty() && normalized.all(ExpensePurpose::valid)) {
                "类别为 1–20 个字，不能包含换行或【】"
            }
            prefs.edit().putString(KEY_EXPENSE_CATEGORIES, JSONArray(normalized).toString()).apply()
        }

    companion object {
        private const val PREFS_NAME = "screen_intake_secure"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_RULES = "classify_rules"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_EXPENSE_PURPOSES = "expense_purposes"
        private const val KEY_EXPENSE_CATEGORIES = "expense_categories"
        private const val KEY_ENGLISH_WEEKLY_MINUTES = "english_weekly_minutes"
        private const val KEY_INVESTING_WEEKLY_MINUTES = "investing_weekly_minutes"

        const val DEFAULT_RULES =
            "美团/饿了么买菜算日用，不算餐饮。\n" +
                "打车、加油、停车算交通。\n" +
                "跟客户/同事一起吃饭算人情，不算餐饮。\n" +
                "拿不准分类就填「其他」，但不要因为分类拿不准就放弃识别这是一笔支出。"
    }
}

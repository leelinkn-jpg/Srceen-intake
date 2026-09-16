package com.linkn.screenintake.store

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * 持仓页面「刷新最新价格」取到的价格，缓存在这里而不是 FinanceScreen 里的 remember 状态——
 * 之前放在 remember 里的话，一切走（切到别的 Tab 再切回来）持仓这个 Composable 就会被
 * 销毁重建，取到的价格全部清零，市值/总资产瞬间跳回按成本估算的数字，跟他实际点了
 * 「刷新」这个动作完全对不上，看着像是"自己会变"。挪到这个跟 [DataChangeSignal] 同级的
 * App 内单例之后，只要 App 进程没被杀掉，价格就会一直保留着上次刷新的结果，直到下一次
 * 手动点刷新才会变——不刷新就不会变，符合"在我手动触发更新之前，就一直显示在这里"的预期。
 *
 * 不写进跟 Mac 同步的那个 SAF 文件夹：这类会变的数字本来就不该跟着账本.csv 那些文件
 * 一起同步（见 QuoteFetcher 头部注释，同步方向是仅发送，写进去也传不回来，纯属浪费）。
 * 但"不写进同步文件夹"和"App 进程重启后要不要保留"是两件不同的事——一开始图省事两个
 * 都用"只存内存"解决，结果退出 App 再打开，刚刷新的价格又没了、变回"暂无价格"，看着
 * 像坏了。这里额外存一份到本机的 SharedPreferences（纯本地文件，不在那个 SAF 目录里，
 * Syncthing 碰不到），App 冷启动时 [load] 一次垫底，后面每次手动刷新都会覆盖过去，
 * 两头的诉求都照顾到——冷启动时看到的是"上次刷新的结果"，不是空的，但也不会假装这是
 * 实时数据（还是要手动刷新才会真的变）。
 */
object PriceCache {
    var prices: Map<String, Double?> by mutableStateOf(mapOf())
        private set

    private var prefs: SharedPreferences? = null

    /** ScreenIntakeApp.onCreate 里调一次，读上一次刷新的结果垫底，避免冷启动时先短暂
     * 显示一次"暂无价格"再跳到真实数据、或者要手动点一次刷新才看得到东西。 */
    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        val raw = p.getString(KEY_PRICES, null) ?: return
        val loaded = try {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, Double?>()
            json.keys().forEach { key ->
                map[key] = if (json.isNull(key)) null else json.optDouble(key)
            }
            map
        } catch (e: Exception) {
            emptyMap<String, Double?>()
        }
        if (loaded.isNotEmpty()) {
            prices = loaded
        }
    }

    /** market:code 当 key，跟 FinanceScreen/HoldingsView 里 keyOf(h) 的拼法保持一致。 */
    fun update(newPrices: Map<String, Double?>) {
        prices = prices + newPrices
        persist()
    }

    private fun persist() {
        val p = prefs ?: return
        val json = JSONObject()
        prices.forEach { (key, value) ->
            if (value != null) json.put(key, value) else json.put(key, JSONObject.NULL)
        }
        p.edit().putString(KEY_PRICES, json.toString()).apply()
    }

    private const val PREFS_NAME = "price_cache"
    private const val KEY_PRICES = "prices"
}

package com.linkn.screenintake.store

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 持仓页面用：给一只股票现取一个最新价格。三个市场分别用不同的免费公开行情接口——
 * 都不是官方文档化的 API，没有 key，也没有任何 SLA 保证，纯粹是社区里常年在用的公开
 * 接口，理论上哪天说变就变、说停就停。失败了直接返回 null，调用方（FinanceScreen）
 * 会显示"获取失败"而不是崩溃或者显示一个假数字。
 *
 * 之所以是 App 自己发请求现算，而不是让 Mac 那边算好同步过来：手机这边的同步文件夹
 * 现在是"仅发送"模式（保护记账数据不被外部改动），价格这种会变的数字没法指望从
 * 外面同步进来，只能自己现取。
 */
object QuoteFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** @return 最新价格，拿不到（网络失败、代码不对、接口格式变了）就返回 null。 */
    fun fetchPrice(market: String, code: String): Double? = try {
        when (market) {
            "US" -> fetchUs(code)
            "HK" -> fetchHk(code)
            else -> fetchAShare(code)
        }
    } catch (e: Exception) {
        null
    }

    /** A股：腾讯财经的公开行情接口，不需要 key。代码要按 sh/sz 前缀拼——6 开头是沪市，
     * 0/3 开头是深市，猜不出来的按沪市试一次。返回格式类似
     * "v_sh600519="1~贵州茅台~600519~1500.00~...";"，第 3 个字段（下标从 0 数，也就是
     * 第 4 个 ~ 分隔的值）是最新价。 */
    private fun fetchAShare(rawCode: String): Double? {
        val digits = rawCode.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val prefix = when {
            rawCode.startsWith("sh", true) || rawCode.startsWith("sz", true) ->
                rawCode.take(2).lowercase()
            digits.startsWith("6") -> "sh"
            else -> "sz"
        }
        val symbol = "$prefix$digits"
        val request = Request.Builder().url("https://qt.gtimg.cn/q=$symbol").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            // 腾讯这个接口按 GBK 编码返回，但价格字段是纯数字，用哪种字符集解码都不影响数字本身。
            val text = resp.body?.string() ?: return null
            val content = text.substringAfter("\"", "").substringBefore("\"")
            val fields = content.split("~")
            return fields.getOrNull(3)?.toDoubleOrNull()
        }
    }

    /** 美股：用 Stooq 的免费行情接口，CSV 格式，不需要 key。代码直接转小写拼 ".us"。 */
    private fun fetchUs(rawCode: String): Double? {
        val symbol = rawCode.trim().lowercase() + ".us"
        return fetchStooq(symbol)
    }

    /** 港股：优先用腾讯财经的港股实时行情接口（sqt.gtimg.cn）——跟 A股用的是同一家，
     * 大陆这边做港股通要覆盖全部港股代码，不会像 Stooq 那样漏掉天立教育（01773）这种
     * 中小市值、交易不算活跃的股票（Stooq 对美股/大盘港股覆盖不错，但小盘港股这块
     * 明显有缺口——之前查天立教育一直查不到任何 Stooq 页面收录记录，反而腾讯港股通
     * 接口是公开可查、社区广泛在用的标准格式）。代码补零到 5 位再拼 "r_hk" 前缀
     * （700 → r_hk00700，1773 → r_hk01773），返回格式类似
     * v_r_hk01773="100~天立教育~01773~1.230~1.210~1.220~...~"，第 3 个字段
     * （下标从 0 数）是最新价，跟 A股接口的字段位置一样。
     * 腾讯这边万一也取不到（比如接口临时抽风），再退回 Stooq 试一次——不带前导 0
     * 的自然写法拼 ".hk"（"700.hk"，不是补零到 5 位的 "00700.hk"，那是另一个代码
     * 段位，之前踩过这个坑）。两边都失败才真正返回 null。 */
    private fun fetchHk(rawCode: String): Double? {
        val digits = rawCode.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        fetchTencentHk(digits)?.let { return it }
        val stooqSymbol = (digits.toIntOrNull()?.toString() ?: digits) + ".hk"
        return fetchStooq(stooqSymbol)
    }

    private fun fetchTencentHk(digits: String): Double? {
        val symbol = "r_hk" + digits.padStart(5, '0')
        val request = Request.Builder().url("https://sqt.gtimg.cn/utf8/q=$symbol").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val text = resp.body?.string() ?: return null
            val content = text.substringAfter("\"", "").substringBefore("\"")
            if (content.isEmpty()) return null
            val fields = content.split("~")
            val price = fields.getOrNull(3)?.toDoubleOrNull()
            return if (price != null && price > 0) price else null
        }
    }

    /** Stooq 的免费行情接口，美股/港股共用这一个取数逻辑，CSV 格式，不需要 key。 */
    private fun fetchStooq(symbol: String): Double? {
        val url = "https://stooq.com/q/l/?s=$symbol&f=sd2t2ohlcv&h&e=csv"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val text = resp.body?.string() ?: return null
            val lines = text.trim().lines()
            if (lines.size < 2) return null
            val cols = lines[1].split(",")
            // 表头是 Symbol,Date,Time,Open,High,Low,Close,Volume —— Close 是倒数第二列。
            val close = cols.getOrNull(cols.size - 2)?.toDoubleOrNull()
            return if (close != null && close > 0) close else null
        }
    }
}

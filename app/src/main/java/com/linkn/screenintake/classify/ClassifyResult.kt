package com.linkn.screenintake.classify

import org.json.JSONObject

/**
 * 模型返回的分类结果，统一成这一个结构，具体字段按 type 取用。
 */
data class ClassifyResult(
    val type: String,            // expense / income / todo / note / trade / holding / transfer / weight / ignore
    // type="weight"（体重截图）也复用这个字段存体重公斤数，不单独开一个字段——
    // 跟 holding 复用 price 存成本价是同一个思路，省一次 schema 改动。
    val amount: Double? = null,
    val merchant: String? = null,
    val category: String? = null,
    val summary: String? = null,
    val detail: String? = null,
    val who: String? = null,
    val whenText: String? = null,
    val source: String? = null,
    val reason: String? = null,
    // 待办/灵感专用：这条内容归到"财务/健康/工作/习惯/其他"里的哪一块——四个底部 Tab
    // 各自的"待办与灵感"摘要区域靠这个字段筛选。支出/收入/交易/持仓/转账这些类型天然
    // 就是财务范畴，不需要模型判断，落盘时直接按财务处理（见 RecordStore.route）；
    // 只有 todo/note 这两类需要模型真的判断一下。模型判不出来、给了无效值，或者压根
    // 没给，落盘时统一按"其他"兜底（见 [normalizedDomain]），不强求模型必须精确分类。
    val domain: String? = null,
    // 待办专用：模型换算出来的具体时间点，ISO 格式 "yyyy-MM-ddTHH:mm:ss"，给 App 本地提醒用。
    val dueAt: String? = null,
    // 记账/收入专用：截图或文字里能看出的实际交易时间，ISO 格式 "yyyy-MM-ddTHH:mm:ss"，
    // 用来给账本.csv的「日期」列带上具体时间，让排序按真实发生时间来，而不是记录时间
    // （比如今天补记昨天忘记录的一笔）。模型看不出具体时间就留空，落盘时会退回到记账当下的时间。
    val transactionAt: String? = null,
    // 记账/收入专用：这笔钱走的是哪张卡/账户，原样保留截图里看到的文字（比如「招商银行
    // 储蓄卡(6789)」），看不出来就留空。用来给「卡片余额」自动加减，见 RecordStore。
    val card: String? = null,
    // 以下是 type = "transfer"（自己名下账户之间互转，比如还信用卡、把工资卡的钱转到
    // 另一张卡）专用字段：转出/转入分别是哪个账户，原样保留识别到的文字，看不出来就留空
    // （落盘时会显示成"未知账户"，不阻塞流水记录本身，但不会去改任何卡片余额）。
    val fromCard: String? = null,
    val toCard: String? = null,
    // 以下都是 type = "trade"（股票买卖）专用字段。
    val tradeSide: String? = null,   // "buy" / "sell"
    val market: String? = null,      // "A" / "US" / "HK"
    val stockCode: String? = null,   // 证券代码，原样保留
    val stockName: String? = null,   // 证券名称
    val shares: Double? = null,      // 股数
    val price: Double? = null,       // 每股成交价
    val purpose: String? = null      // 支出用途，由用户选择，与消费分类独立
) {
    val isExpense get() = type == "expense"
    val isIncome get() = type == "income"
    val isTodo get() = type == "todo"
    val isNote get() = type == "note"
    val isTrade get() = type == "trade"
    val isHolding get() = type == "holding"
    val isTransfer get() = type == "transfer"
    val isWeight get() = type == "weight"
    val isDigitalHealth get() = type == "digital_health"
    val isMealNote get() = type == "meal_note"
    val isIgnore get() = type == "ignore"

    /** 落盘/展示待办、灵感的领域标签时统一走这个，不要直接用 [domain] 原始值——
     * 校验 + 兜底成"其他"的逻辑只写这一份。 */
    fun normalizedDomain(): String = domain?.trim()?.takeIf { it in DOMAINS } ?: "其他"

    /** 记账和收入都是「一笔钱」，很多地方（编辑逻辑、展示格式）两者共用同一套处理。 */
    private val isMoney get() = isExpense || isIncome

    /** 中文类型名，给通知/待确认草稿这些人要看的地方用。 */
    fun typeLabel(): String = when (type) {
        "expense" -> "支出"
        "income" -> "收入"
        "todo" -> "待办"
        "note" -> "灵感"
        "trade" -> "股票交易"
        "holding" -> "持仓设置"
        "transfer" -> "互转"
        "weight" -> "体重"
        "digital_health" -> "数字健康"
        "meal_note" -> "饮食记录"
        "ignore" -> "忽略"
        else -> type
    }

    /** 一行摘要，给通知/待确认草稿展示当前识别出来的内容用。 */
    fun displaySummary(): String = when {
        isMoney -> buildString {
            append("¥")
            append("%.2f".format(amount ?: 0.0))
            if (!merchant.isNullOrBlank()) append(" $merchant")
            if (!category.isNullOrBlank()) append(" · $category")
            if (isExpense && !purpose.isNullOrBlank()) append(" · $purpose")
        }
        isTodo -> buildString {
            append("[${normalizedDomain()}] ")
            append(summary ?: "（未提取到内容）")
            if (!whenText.isNullOrBlank()) append(" · $whenText") else append(" · 缺具体时间")
        }
        isNote -> buildString {
            append("[${normalizedDomain()}] ")
            append(summary ?: "")
        }
        isMealNote -> summary ?: "（未提取到饮食内容）"
        isTrade -> buildString {
            append(if (tradeSide == "sell") "卖出 " else "买入 ")
            append(stockName ?: stockCode ?: "未知标的")
            if (!stockCode.isNullOrBlank() && stockName != null) append("($stockCode)")
            if (shares != null) append(" ${fmtNum(shares)}股")
            if (price != null) append(" @¥%.2f".format(price))
            if (!summary.isNullOrBlank()) append(" · $summary")
        }
        isHolding -> buildString {
            append("持仓 ")
            append(stockName ?: stockCode ?: "未知标的")
            if (!stockCode.isNullOrBlank() && stockName != null) append("($stockCode)")
            if (shares != null) append(" 共${fmtNum(shares)}股")
            if (price != null) append(" 成本¥%.2f".format(price))
            if (!summary.isNullOrBlank()) append(" · $summary")
        }
        isTransfer -> buildString {
            append("¥")
            append("%.2f".format(amount ?: 0.0))
            append("  ")
            append(fromCard?.takeIf { it.isNotBlank() } ?: "某账户")
            append(" → ")
            append(toCard?.takeIf { it.isNotBlank() } ?: "某账户")
            if (!summary.isNullOrBlank()) append(" · $summary")
        }
        isWeight -> buildString {
            append("体重 %.1fkg".format(amount ?: 0.0))
            if (!summary.isNullOrBlank()) append(" · $summary")
        }
        isDigitalHealth -> "屏幕使用 ${amount?.toInt() ?: 0} 分钟" + if (!summary.isNullOrBlank()) " · $summary" else ""
        else -> summary ?: reason ?: ""
    }

    /**
     * 去重用的「内容指纹」——类型 + 关键字段都一样才算同一条内容，金额四舍五入到分，
     * 避免浮点误差把本来一样的两条误判成不一样。「忽略」不参与去重（本来就不落盘，
     * 重复了也不占地方，没必要拦）。给 [RecentCaptureDedup] 用，判断是不是刚刚
     * 已经处理过同一条内容——比如手滑连按两次组合键，或者对着同一屏幕又触发了一次。
     */
    fun signature(): String? = when {
        isIgnore -> null
        isMoney -> listOf(
            type, category.orEmpty(), merchant.orEmpty(), "%.2f".format(amount ?: 0.0)
        ).joinToString("|")
        isTodo -> listOf(type, summary.orEmpty(), whenText.orEmpty()).joinToString("|")
        isNote -> listOf(type, summary.orEmpty(), detail.orEmpty()).joinToString("|")
        isTrade -> listOf(
            type, market.orEmpty(), stockCode.orEmpty(), tradeSide.orEmpty(),
            "%.4f".format(shares ?: 0.0), "%.4f".format(price ?: 0.0)
        ).joinToString("|")
        isHolding -> listOf(
            type, market.orEmpty(), stockCode.orEmpty(),
            "%.4f".format(shares ?: 0.0), "%.4f".format(price ?: 0.0)
        ).joinToString("|")
        isTransfer -> listOf(
            type, fromCard.orEmpty(), toCard.orEmpty(), "%.2f".format(amount ?: 0.0)
        ).joinToString("|")
        isWeight -> listOf(type, "%.1f".format(amount ?: 0.0)).joinToString("|")
        isDigitalHealth -> listOf(type, transactionAt.orEmpty(), "%.0f".format(amount ?: 0.0), detail.orEmpty()).joinToString("|")
        else -> null
    }

    /** 点「编辑」时改的到底是哪个字段——记账/收入类改的是「分类」（打车/代驾这种判断），
     * 待办/灵感改的是「内容」本身；「忽略」比较特殊，编辑框里写的不是替换某个字段，
     * 而是当成一次全新的手动记录重新走一遍分类（见 [CaptureConfirmReceiver]）。 */
    fun editHint(): String = when {
        isMoney -> "选择消费分类；支出可另外标记用途"
        isIgnore -> "改成需要记录的内容"
        isTodo && dueAt.isNullOrBlank() -> "改内容，记得写清楚具体时间（比如「明天8点15」），不写时间的待办不算记完"
        isTrade -> "这里只能加一句备注，股数/价格/代码这些数字不对的话，建议直接删除这条草稿重新截图或重新打字"
        isHolding -> "这里只能加一句备注，股数/成本/代码这些数字不对的话，建议直接删除这条草稿重新截图或重新打字"
        isTransfer -> "这里只能加一句备注，转出/转入的账户和金额不对的话，建议直接删除这条草稿重新截图或重新打字"
        isWeight -> "这里只能加一句备注，体重数字不对的话，建议直接删除这条草稿重新截图"
        else -> "改内容"
    }

    /**
     * 编辑框提交的文字，套回对应字段——记账/收入类替换 category，其余替换 summary。
     * 金额、商户这些字段编辑框管不到，需要更大改动时还是建议打开 App 手动改。
     *
     * @param dueAt 待办专用：App 内编辑弹窗的时间选择器选出来的具体时间（ISO
     * "yyyy-MM-ddTHH:mm:ss"），非空时连带把 [whenText] 也换成人看得懂的格式，两个字段
     * 保持一致——待办.md 显示 whenText，本地通知使用 dueAt，编辑时必须一起改。
     * 编辑（通知里的行内回复，没有时间选择器）不会传这个参数，走的是另一条"当成全新内容重新
     * 分类一遍"的路径，见 [com.linkn.screenintake.capture.CaptureConfirmActions]。
     */
    fun withEdit(editedText: String, dueAt: String? = null): ClassifyResult {
        val base = if (isMoney) copy(category = editedText) else copy(summary = editedText)
        if (!isTodo || dueAt == null) return base
        return base.copy(dueAt = dueAt, whenText = formatDueAtForDisplay(dueAt))
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("type", type)
        o.put("amount", amount ?: JSONObject.NULL)
        o.put("merchant", merchant ?: JSONObject.NULL)
        o.put("category", category ?: JSONObject.NULL)
        o.put("summary", summary ?: JSONObject.NULL)
        o.put("detail", detail ?: JSONObject.NULL)
        o.put("who", who ?: JSONObject.NULL)
        o.put("whenText", whenText ?: JSONObject.NULL)
        o.put("source", source ?: JSONObject.NULL)
        o.put("reason", reason ?: JSONObject.NULL)
        o.put("domain", domain ?: JSONObject.NULL)
        o.put("dueAt", dueAt ?: JSONObject.NULL)
        o.put("transactionAt", transactionAt ?: JSONObject.NULL)
        o.put("card", card ?: JSONObject.NULL)
        o.put("fromCard", fromCard ?: JSONObject.NULL)
        o.put("toCard", toCard ?: JSONObject.NULL)
        o.put("tradeSide", tradeSide ?: JSONObject.NULL)
        o.put("market", market ?: JSONObject.NULL)
        o.put("stockCode", stockCode ?: JSONObject.NULL)
        o.put("stockName", stockName ?: JSONObject.NULL)
        o.put("shares", shares ?: JSONObject.NULL)
        o.put("price", price ?: JSONObject.NULL)
        o.put("purpose", purpose ?: JSONObject.NULL)
        return o.toString()
    }

    companion object {
        /** 待办/灵感归到四个领域 Tab 之一的合法取值，"其他"是兜底桶——模型判不出来、
         * 判断值不在这个集合里，或者字段压根是空的，统一按"其他"处理，不强行要求
         * 模型必须精确归类，也不会因为一个奇怪的值让整条内容找不到该放在哪个 Tab。 */
        val DOMAINS = setOf("财务", "健康", "工作", "习惯", "其他")

        /** 股数展示用：整数股不带小数点（"100股"而不是"100.0股"），有小数的照实显示
         * （美股/港股可能有碎股）。 */
        fun fmtNum(n: Double): String =
            if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()

        /** dueAt 的 ISO 时间转成人看的样子，给 whenText/待办.md 展示用；万一格式不对解析
         * 失败，原样返回而不是让整个编辑提交崩掉——顶多显示不好看，不会丢数据。 */
        private fun formatDueAtForDisplay(dueAt: String): String = try {
            java.time.LocalDateTime.parse(dueAt)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        } catch (e: Exception) {
            dueAt
        }

        fun fromJson(json: String): ClassifyResult {
            val o = JSONObject(json)
            fun str(key: String): String? = if (o.has(key) && !o.isNull(key)) o.getString(key) else null
            return ClassifyResult(
                type = o.optString("type", "ignore"),
                amount = if (o.has("amount") && !o.isNull("amount")) o.optDouble("amount") else null,
                merchant = str("merchant"),
                category = str("category"),
                purpose = str("purpose"),
                summary = str("summary"),
                detail = str("detail"),
                who = str("who"),
                whenText = str("whenText"),
                source = str("source"),
                reason = str("reason"),
                domain = str("domain"),
                dueAt = str("dueAt"),
                transactionAt = str("transactionAt"),
                card = str("card"),
                fromCard = str("fromCard"),
                toCard = str("toCard"),
                tradeSide = str("tradeSide"),
                market = str("market"),
                stockCode = str("stockCode"),
                stockName = str("stockName"),
                shares = if (o.has("shares") && !o.isNull("shares")) o.optDouble("shares") else null,
                price = if (o.has("price") && !o.isNull("price")) o.optDouble("price") else null
            )
        }
    }
}

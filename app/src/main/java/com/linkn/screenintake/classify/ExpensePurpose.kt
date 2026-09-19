package com.linkn.screenintake.classify

/** 分类回答买了什么，用途回答为谁/因什么花钱；用途可留空，不从商户擅自推断。 */
object ExpensePurpose {
    val defaults = listOf("因公", "给老婆", "给爸爸", "给妈妈")
    const val NONE = "不标记用途"
    private val noteTag = Regex("^【用途：([^】\\r\\n]+)】(?: )?")
    private val cardTag = Regex("^【账户：([^】\\r\\n]+)】(?: )?")

    fun normalize(values: List<String>): List<String> = values.map { it.trim() }
        .filter { it.isNotEmpty() }.distinct()

    fun valid(value: String): Boolean = value.isNotBlank() && value.length <= 20 &&
        value.none { it in "\r\n【】" } && value != NONE

    // 保持账本原有五列，只有新记录的备注带可读标记，不迁移/改写历史数据。
    fun withNote(note: String, purpose: String?, card: String? = null): String = buildString {
        if (!card.isNullOrBlank()) append("【账户：$card】 ")
        if (!purpose.isNullOrBlank()) append("【用途：$purpose】 ")
        append(note)
    }.trimEnd()

    fun fromNote(note: String): String? = noteTag.find(note)?.groupValues?.get(1)
    fun cardFromNote(note: String): String? = cardTag.find(note)?.groupValues?.get(1)
    fun withoutTag(note: String): String = note.replaceFirst(cardTag, "").replaceFirst(noteTag, "")

    /** 通知快速修改只改明确输入的字段，金额、账户、商户与时间原样保留。 */
    fun edit(result: ClassifyResult, text: String, purposes: List<String>): ClassifyResult {
        val value = text.trim()
        require(value.isNotEmpty()) { "请填写分类或消费用途" }
        if (!result.isExpense) {
            if (result.isIncome) require(value in Categories.INCOME) { "请选择已有收入分类" }
            return result.withEdit(value)
        }
        if (value == NONE) return result.copy(purpose = null)
        if (value.startsWith("用途：")) {
            val purpose = value.removePrefix("用途：").trim()
            require(purpose in purposes) { "用途不在设置中，请打开 App 选择或在设置里新增" }
            return result.copy(purpose = purpose)
        }
        if (value in Categories.EXPENSE) return result.copy(category = value)
        if (value in purposes) return result.copy(purpose = value)
        throw IllegalArgumentException("请填已有分类（如餐饮）或设置中的用途；点通知可打开待确认")
    }
}

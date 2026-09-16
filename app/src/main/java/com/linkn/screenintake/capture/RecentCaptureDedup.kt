package com.linkn.screenintake.capture

/**
 * 短时间内重复识别到同一条内容的去重兜底——只挡"几乎肯定是同一次触发被重复处理"的
 * 情况：时间窗口给得很短（20 秒），所以像"今天和明天都买同一瓶水""隔几天交一次一样
 * 金额的停车费"这种真实的、时间上隔开的重复消费，完全不会被当成重复吃掉；只有手指
 * 抖了连按两次组合键、或者对着同一屏幕/同一张截图又触发了一次这种几乎同一瞬间发生
 * 的重复，才会被拦下不再记一遍。
 *
 * 存在内存里，不落盘——重启 App 之后这份记录清空，不需要长期保留，也没必要为了这么
 * 短的窗口再多写一个文件。
 */
object RecentCaptureDedup {
    private const val WINDOW_MS = 20_000L
    private val recent = ArrayDeque<Pair<String, Long>>()

    /**
     * 检查这条内容的「签名」在窗口内是不是刚出现过；不是的话顺便把它记进去，调用方
     * 不用自己再补一次记录动作。返回 true 表示这是重复内容，调用方应该跳过、不再记。
     */
    @Synchronized
    fun checkAndRemember(signature: String): Boolean {
        val now = System.currentTimeMillis()
        while (recent.isNotEmpty() && now - recent.first().second > WINDOW_MS) {
            recent.removeFirst()
        }
        val isDuplicate = recent.any { it.first == signature }
        if (!isDuplicate) {
            recent.addLast(signature to now)
        }
        return isDuplicate
    }
}

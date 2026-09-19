package com.linkn.screenintake.classify

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/**
 * 大模型擅长抽取“下周一早上八点”里的语义和时间，却偶尔会把日期算偏一天。
 * 对明确的“下周几”在手机本地重新计算日期，同时保留模型抽出的时分秒。
 */
object RelativeDateNormalizer {
    private val nextWeekday = Regex("下周([一二三四五六日天])")
    private val weekdayMap = mapOf(
        '一' to DayOfWeek.MONDAY, '二' to DayOfWeek.TUESDAY, '三' to DayOfWeek.WEDNESDAY,
        '四' to DayOfWeek.THURSDAY, '五' to DayOfWeek.FRIDAY, '六' to DayOfWeek.SATURDAY,
        '日' to DayOfWeek.SUNDAY, '天' to DayOfWeek.SUNDAY
    )

    fun normalize(result: ClassifyResult, originalText: String, today: LocalDate = LocalDate.now()): ClassifyResult {
        if (!result.isTodo || result.dueAt.isNullOrBlank()) return result
        val source = listOf(originalText, result.summary, result.whenText).joinToString(" ")
        val match = nextWeekday.find(source) ?: return result
        val targetDay = weekdayMap[match.groupValues[1].first()] ?: return result
        val parsed = runCatching { LocalDateTime.parse(result.dueAt) }.getOrNull() ?: return result
        val nextMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1)
        val correctedDate = nextMonday.plusDays((targetDay.value - DayOfWeek.MONDAY.value).toLong())
        val corrected = LocalDateTime.of(correctedDate, parsed.toLocalTime())
        return result.copy(
            dueAt = corrected.toString(),
            whenText = result.whenText ?: corrected.toString().replace('T', ' ')
        )
    }
}

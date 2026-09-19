package com.linkn.screenintake.classify

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class RelativeDateNormalizerTest {
    @Test
    fun `下周一按本地日期校正并保留时间`() {
        val input = ClassifyResult(
            type = "todo",
            summary = "周会说季度营销目标",
            whenText = "下周一早晨八点一刻",
            dueAt = "2026-09-22T08:15:00"
        )

        val result = RelativeDateNormalizer.normalize(input, "下周一提醒我", LocalDate.of(2026, 9, 18))

        assertEquals("2026-09-21T08:15", result.dueAt)
    }
}

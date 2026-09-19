package com.linkn.screenintake.meeting

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class MeetingNameTest {
    private fun time(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test fun `周一八点四十五默认周会`() {
        assertEquals("周会", MeetingRecord.defaultName(time(2026, 9, 21, 8, 45)))
    }

    @Test fun `工作日八点二十默认部门晨会`() {
        assertEquals("部门晨会", MeetingRecord.defaultName(time(2026, 9, 22, 8, 20)))
    }

    @Test fun `其他时间默认会议记录`() {
        assertEquals("会议记录", MeetingRecord.defaultName(time(2026, 9, 22, 14, 0)))
    }
}

package com.linkn.screenintake.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportActionStateTest {
    @Test fun onlyFinalUserDecisionsLeavePendingList() {
        assertTrue(isReportActionPending(null))
        assertTrue(isReportActionPending(""))
        assertFalse(isReportActionPending("accepted"))
        assertFalse(isReportActionPending("rejected"))
    }
}

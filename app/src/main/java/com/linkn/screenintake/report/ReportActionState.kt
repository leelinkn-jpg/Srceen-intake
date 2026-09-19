package com.linkn.screenintake.report

/** Only actions without a final user decision belong in the pending-confirmation UI. */
fun isReportActionPending(status: String?): Boolean = status != "accepted" && status != "rejected"

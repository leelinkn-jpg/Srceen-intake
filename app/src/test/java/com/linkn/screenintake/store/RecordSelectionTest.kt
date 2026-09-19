package com.linkn.screenintake.store

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordSelectionTest {
    @Test fun reorderedRecordStillMatches() {
        assertEquals("original", selectUnchangedRecord(listOf("new", "original")) { it == "original" })
    }
    @Test(expected = IllegalStateException::class) fun deletedRecordNeverFallsBackToIndex() {
        selectUnchangedRecord(listOf("replacement")) { it == "original" }
    }
    @Test(expected = IllegalStateException::class) fun duplicatesRequireResolution() {
        selectUnchangedRecord(listOf("same", "same")) { it == "same" }
    }
}

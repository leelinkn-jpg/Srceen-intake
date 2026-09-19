package com.linkn.screenintake.classify

import org.junit.Assert.*
import org.junit.Test

class ExpensePurposeTest {
    private val coffee = ClassifyResult(type = "expense", amount = 18.0, merchant = "瑞幸",
        category = "餐饮", summary = "咖啡", card = "招商银行", transactionAt = "2026-09-16T12:30:00")

    @Test fun purposeLeavesFinancialFieldsAndCategoryUnchanged() {
        assertEquals(coffee.copy(purpose = "因公"), ExpensePurpose.edit(coffee, "用途：因公", ExpensePurpose.defaults))
        assertEquals(coffee.copy(purpose = "给老婆"), ExpensePurpose.edit(coffee, "给老婆", ExpensePurpose.defaults))
    }

    @Test fun changingCategoryPreservesPurpose() {
        val tagged = coffee.copy(purpose = "给妈妈")
        assertEquals(tagged.copy(category = "日用消费"), ExpensePurpose.edit(tagged, "日用消费", ExpensePurpose.defaults))
        assertEquals(coffee, ExpensePurpose.edit(coffee.copy(purpose = "因公"), ExpensePurpose.NONE, emptyList()))
    }

    @Test fun explicitPrefixDisambiguatesCategoryNamedPurpose() {
        assertEquals("餐饮", ExpensePurpose.edit(coffee, "用途：餐饮", listOf("餐饮")).purpose)
        assertNull(ExpensePurpose.edit(coffee, "餐饮", listOf("餐饮")).purpose)
    }

    @Test fun deletedChoiceDoesNotBecomeCategory() {
        assertThrows(IllegalArgumentException::class.java) { ExpensePurpose.edit(coffee, "用途：已删除", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ExpensePurpose.edit(coffee, "金额改成999", ExpensePurpose.defaults) }
        assertThrows(IllegalArgumentException::class.java) { ExpensePurpose.edit(coffee, "", ExpensePurpose.defaults) }
    }

    @Test fun oldDraftsAndNewDraftsRoundTrip() {
        assertNull(ClassifyResult.fromJson("{\"type\":\"expense\",\"amount\":18}").purpose)
        val tagged = coffee.copy(purpose = "给爸爸")
        assertEquals(tagged, ClassifyResult.fromJson(tagged.toJson()))
        assertTrue(tagged.displaySummary().contains("给爸爸"))
    }

    @Test fun fiveColumnLedgerCanCarryPurposeInNoteWithoutChangingLegacyNotes() {
        val note = "瑞幸 咖啡, 加冰\"少糖\""
        assertEquals(note, ExpensePurpose.withNote(note, null))
        assertEquals(note, ExpensePurpose.withoutTag(note))
        assertNull(ExpensePurpose.fromNote(note))
        val tagged = ExpensePurpose.withNote(note, "给老婆")
        assertEquals("给老婆", ExpensePurpose.fromNote(tagged))
        assertEquals(note, ExpensePurpose.withoutTag(tagged))
        assertEquals("【用途：因公】", ExpensePurpose.withNote("", "因公"))
    }

    @Test fun settingsValidateAndDeduplicateWithoutRestoringDeletedDefaults() {
        assertEquals(emptyList<String>(), ExpensePurpose.normalize(emptyList()))
        assertEquals(listOf("给老婆", "因公"), ExpensePurpose.normalize(listOf(" 给老婆 ", "因公", "", "给老婆")))
        assertFalse(ExpensePurpose.valid("【用途】"))
        assertFalse(ExpensePurpose.valid("给\n妈妈"))
        assertFalse(ExpensePurpose.valid(ExpensePurpose.NONE))
        assertTrue(ExpensePurpose.valid("给家里人"))
    }
}

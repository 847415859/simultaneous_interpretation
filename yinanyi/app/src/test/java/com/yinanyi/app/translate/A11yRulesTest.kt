package com.yinanyi.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yRulesTest {
    @Test
    fun `offers the button only for chinese text in ordinary fields`() {
        assertTrue(A11yRules.shouldOffer("主播今天打得太厉害了", "com.example.chat", false, ""))
        assertFalse(A11yRules.shouldOffer("hello there", "com.example.chat", false, ""))
        assertFalse(A11yRules.shouldOffer("", "com.example.chat", false, ""))
        assertFalse(A11yRules.shouldOffer(null, "com.example.chat", false, ""))
    }

    @Test
    fun `password fields and unknown packages never get the button`() {
        assertFalse(A11yRules.shouldOffer("密码123", "com.example.chat", true, ""))
        assertFalse(A11yRules.shouldOffer("你好", null, false, ""))
    }

    @Test
    fun `banks and our own app are off by default`() {
        assertFalse(A11yRules.shouldOffer("转账", "com.eg.android.AlipayGphone", false, ""))
        assertFalse(A11yRules.shouldOffer("你好", "com.yinanyi.app", false, ""))
    }

    @Test
    fun `user can switch apps off and on again`() {
        var csv = ""
        csv = A11yRules.addOff(csv, "com.example.chat")
        csv = A11yRules.addOff(csv, "com.example.chat")
        assertEquals("com.example.chat", csv)
        assertFalse(A11yRules.shouldOffer("你好", "com.example.chat", false, csv))
        csv = A11yRules.removeOff(csv, "com.example.chat")
        assertTrue(A11yRules.shouldOffer("你好", "com.example.chat", false, csv))
    }

    @Test
    fun `very long text is skipped and the label shows the language`() {
        assertFalse(A11yRules.shouldOffer("字".repeat(5000), "com.example.chat", false, ""))
        assertEquals("译 JA", A11yRules.pillLabel("ja"))
    }
}

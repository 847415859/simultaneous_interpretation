package com.yinanyi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiKeyCheckTest {
    private val key = "sk-0123456789abcdef0123456789abcdef"

    @Test
    fun `extracts a bare key`() = assertEquals(key, ApiKeyCheck.extract(key))

    @Test
    fun `extracts a key wrapped in whitespace and text`() {
        assertEquals(key, ApiKeyCheck.extract("  API-KEY：$key \n复制"))
    }

    @Test
    fun `ignores ordinary text and too-short keys`() {
        assertNull(ApiKeyCheck.extract("今天吃什么"))
        assertNull(ApiKeyCheck.extract("sk-abc"))
        assertNull(ApiKeyCheck.extract(null))
    }
}

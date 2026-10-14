package com.yinanyi.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GlossaryTextTest {
    @Test
    fun `parses common separators`() {
        val terms = GlossaryText.parse("Kuro=黑\nMio → 澪\nfoo->福\nbar\t吧\n")
        assertEquals(listOf(Term("Kuro", "黑"), Term("Mio", "澪"), Term("foo", "福"), Term("bar", "吧")), terms)
    }

    @Test
    fun `skips blanks comments and broken lines`() {
        assertEquals(listOf(Term("a", "b")), GlossaryText.parse("# 注释\n\n没有分隔符\n=缺原文\n缺译文=\na=b"))
    }

    @Test
    fun `strong separator wins over colon and comma in names`() {
        assertEquals(listOf(Term("Re:Zero", "从零开始"), Term("A, B", "甲乙")), GlossaryText.parse("Re:Zero=从零开始\nA, B=甲乙"))
    }

    @Test
    fun `falls back to comma or colon`() {
        assertEquals(listOf(Term("x", "y"), Term("p", "q")), GlossaryText.parse("x，y\np:q"))
    }

    @Test
    fun `format round trips`() {
        val terms = listOf(Term("a", "b"), Term("c d", "e"))
        assertEquals(terms, GlossaryText.parse(GlossaryText.format(terms)))
    }
}

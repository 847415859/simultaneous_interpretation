package com.yinanyi.app.translate

import com.yinanyi.app.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRulesTest {
    @Test
    fun `chinese is detected, foreign text is not`() {
        assertTrue(TextRules.isChinese("主播今天打得太厉害了"))
        assertTrue(TextRules.isChinese("我今天用了 iPhone 拍照"))
        assertFalse(TextRules.isChinese("chat is going wild tonight"))
        assertFalse(TextRules.isChinese("今日の配信、すごかった"))
        assertFalse(TextRules.isChinese("오늘 스트리머 대박"))
    }

    @Test
    fun `tokens are masked and restored`() {
        val text = "@Mika 主播太强了 [doge] 看这个 https://example.com/a?b=1 哈哈"
        val (masked, tokens) = TextRules.mask(text)
        assertEquals(listOf("@Mika", "[doge]", "https://example.com/a?b=1"), tokens)
        assertEquals("{1} 主播太强了 {2} 看这个 {3} 哈哈", masked)
        assertEquals(text, TextRules.unmask(masked, tokens))
    }

    @Test
    fun `lost placeholders are appended instead of dropped`() {
        val out = TextRules.unmask("The streamer is great {1}", listOf("@Mika", "[doge]"))
        assertTrue(out.contains("@Mika"))
        assertTrue(out.contains("[doge]"))
    }

    @Test
    fun `mention stops at punctuation`() {
        val (_, tokens) = TextRules.mask("@小明，你好")
        assertEquals(listOf("@小明"), tokens)
    }

    @Test
    fun `long lines are split at sentence ends`() {
        val line = "第一句话。".repeat(100)
        val chunks = TextRules.chunk(line, 120)
        assertTrue(chunks.all { it.length <= 120 })
        assertEquals(line, chunks.joinToString(""))
    }

    @Test
    fun `blank lines are kept as empty groups`() {
        val groups = TextRules.linesAndChunks("你好\n\n再见")
        assertEquals(3, groups.size)
        assertTrue(groups[1].isEmpty())
    }

    @Test
    fun `head keeps the cut at a sentence end`() {
        val text = "好。".repeat(1500)
        val (head, rest) = TextRules.head(text, 2000)
        assertTrue(head.length <= 2000)
        assertEquals(text, head + rest)
        assertTrue(head.endsWith("。"))
    }

    @Test
    fun `paragraphs respect the size limit`() {
        val text = (1..10).joinToString("\n") { "段落$it " + "字".repeat(300) }
        val parts = TextRules.paragraphs(text, 1000)
        assertTrue(parts.all { it.length <= 1000 })
        assertEquals(text, parts.joinToString("\n"))
    }

    @Test
    fun `prompt carries tone terms and token rule`() {
        val terms = listOf(Term("Kuro", "黑"))
        val p = TextRules.systemPrompt(Langs.byCode("en"), Tones.builtin.first(), terms, "黑今天很强", keepTokens = true)
        assertTrue(p.contains("English"))
        assertTrue(p.contains(Tones.builtin.first().hint))
        assertTrue(p.contains("黑 -> Kuro"))
        assertTrue(p.contains("@mentions"))
    }

    @Test
    fun `custom tone is passed as the user's own requirement`() {
        val tone = Tones.custom("c1", "撒娇", "用软萌的口吻", "all")
        val p = TextRules.systemPrompt(Langs.byCode("ja"), tone, emptyList(), "你好", keepTokens = false)
        assertTrue(p.contains("用软萌的口吻"))
        assertFalse(p.contains("@mentions"))
    }

    @Test
    fun `alternatives drop numbering and duplicates`() {
        val raw = "1. Hello there\n2) Hi there\n- Hello there\nHey you\n"
        assertEquals(listOf("Hi there", "Hey you"), TextRules.parseAlternatives(raw, "Hello there"))
    }
}

class ToneConfigTest {
    @Test
    fun `round trips through json`() {
        val cfg = ToneConfig(
            custom = listOf(Tones.custom("c1", "撒娇", "软萌", "en")),
            hidden = setOf("tactful"),
            order = listOf("c1", "casual"),
        )
        val back = ToneConfig.fromJson(cfg.toJson())
        assertEquals(cfg.custom.map { it.id to it.hint to it.scope }, back.custom.map { it.id to it.hint to it.scope })
        assertEquals(cfg.hidden, back.hidden)
        assertEquals(cfg.order, back.order)
    }

    @Test
    fun `bad json gives defaults`() {
        assertEquals(0, ToneConfig.fromJson("not json").custom.size)
        assertEquals(0, ToneConfig.fromJson(null).custom.size)
    }

    @Test
    fun `order, hiding and scope decide what the panel shows`() {
        val cfg = ToneConfig(
            custom = listOf(Tones.custom("c1", "撒娇", "软萌", "ja")),
            hidden = setOf("formal"),
            order = listOf("c1", "polite"),
        )
        val en = cfg.visible("en").map { it.id }
        assertFalse("formal" in en)
        assertFalse("c1" in en)
        assertEquals("polite", en.first())
        assertEquals("c1", cfg.visible("ja").first().id)
    }

    @Test
    fun `name rules`() {
        val existing = listOf(Tones.custom("c1", "撒娇", "x", "all"))
        assertEquals("请输入名称", Tones.nameError("  ", existing, null))
        assertTrue(Tones.nameError("口语", existing, null)!!.contains("同名"))
        assertTrue(Tones.nameError("撒娇", existing, null)!!.contains("同名"))
        assertNull(Tones.nameError("撒娇", existing, "c1"))
        assertNull(Tones.nameError("主播互动", existing, null))
    }
}

package com.yinanyi.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneExampleTest {
    private val casual = Tones.builtin.first { it.id == "casual" }

    @Test
    fun `examples follow the target language`() {
        assertEquals("Thanks for the help!", Tones.exampleFor(casual, "en"))
        assertEquals("助かった、ありがとう！", Tones.exampleFor(casual, "ja"))
        assertEquals("도와줘서 고마워!", Tones.exampleFor(casual, "ko"))
        assertEquals("谢谢你帮忙！", Tones.exampleFor(casual, "zh"))
    }

    @Test
    fun `languages without examples fall back to English`() {
        assertEquals(Tones.exampleFor(casual, "en"), Tones.exampleFor(casual, "th"))
    }

    @Test
    fun `every built-in tone has an example in every covered language`() {
        for (lang in listOf("en", "ja", "ko", "fr", "de", "es", "zh")) {
            val seen = Tones.builtin.map { Tones.exampleFor(it, lang) }
            assertTrue(lang, seen.all { it.isNotBlank() })
            assertEquals(lang, seen.size, seen.toSet().size)
        }
    }

    @Test
    fun `custom tones have no example`() {
        assertEquals("", Tones.exampleFor(Tones.custom("c1", "撒娇", "软萌", "all"), "ja"))
        assertNotEquals("", Tones.exampleFor(casual, "ja"))
    }
}

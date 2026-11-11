package com.museum.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSegmenterTest {
    @Test
    fun englishSentenceBoundariesSurviveSavingAndReloading() {
        val first = TextSegmenter.segment("Value is 2.5 units. Done. Next sentence.")
        val reloaded = TextSegmenter.segment(first.normalizedText)
        assertEquals(first.sentences.map { it.text }, reloaded.sentences.map { it.text })
        assertEquals("Value is 2.5 units. Done. Next sentence.", first.normalizedText)
    }
    @Test
    fun `splits Chinese punctuation and keeps closing quotes`() {
        val result = TextSegmenter.segment("第一句。她说：“第二句！”\n\n第三段没有句号")

        assertEquals(2, result.paragraphs.size)
        assertEquals(listOf("第一句。", "她说：“第二句！”", "第三段没有句号"), result.sentences.map { it.text })
        assertEquals(listOf(0, 0, 1), result.sentences.map { it.paragraphIndex })
    }

    @Test
    fun `does not split decimal dots`() {
        val result = TextSegmenter.segment("这件展品高1.25米。Value is 2.5 units. Done.")
        assertEquals(3, result.sentences.size)
        assertEquals("这件展品高1.25米。", result.sentences[0].text)
    }
}

package com.museum.reader.domain

import org.junit.Assert.*
import org.junit.Test

class TextPositionIndexTest {
    @Test fun rawOffsetsIncludeWhitespaceRepeatedSentencesAndCrLf() {
        val text = "  相同。  相同。\r\n\r\n  英文 one. English two! \n结尾。"
        val index = TextPositionIndex.raw(text)
        val starts = listOf(text.indexOf("相同。"), text.indexOf("相同。", 5),
            text.indexOf("英文"), text.indexOf("English"), text.indexOf("结尾"))
        assertEquals(starts, index.sentenceStarts.toList())
        starts.forEachIndexed { sentence, offset -> assertEquals(sentence, index.sentenceAt(offset)) }
        assertEquals(1, index.paragraphAt(text.indexOf("English")))
        assertEquals(2, index.paragraphAt(text.lastIndex))
    }
    @Test fun searchUsesLiteralTextCaseInsensitiveAndCrossParagraphOffsets() {
        val text = "One. one。\n\n下一段 One。"
        val hits = TextSearch.find(text, "ONE")
        assertEquals(listOf(0, 5, text.lastIndexOf("One")), hits.starts.toList())
        assertEquals(3, hits.length)
        val cross = TextSearch.find(text, "。\n\n下")
        assertEquals(listOf(text.indexOf("。\n\n下")), cross.starts.toList())
        assertEquals(0, TextSearch.find(text, "[a]").count)
        assertEquals(0, TextSearch.find(text, "").count)
    }
    @Test fun storedOffsetsDoNotGuessSentenceSeparatorWidth() {
        val content = TextSegmenter.segment("第一句。第二句。\nThird one. Another sentence!")
        val index = TextPositionIndex.stored(content)
        content.sentences.forEachIndexed { i, sentence ->
            val offset = index.sentenceStarts[i]
            assertTrue(content.normalizedText.substring(offset).startsWith(sentence.text))
        }
        assertEquals(2, index.sentenceAt(content.normalizedText.indexOf("Third")))
    }
    @Test fun millionCharacterSearchIsAccurateAndCanBeCancelled() {
        val text = "甲乙".repeat(500_000)
        assertEquals(500_000, TextSearch.find(text, "乙").count)
        var checks = 0
        assertTrue(runCatching { TextSearch.find(text, "甲") {
            if (++checks >= 2) error("cancelled")
        } }.isFailure)
    }
}

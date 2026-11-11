package com.museum.reader.domain

import com.museum.reader.model.ParagraphSegment
import com.museum.reader.model.SegmentedText
import com.museum.reader.model.SentenceSegment

object TextSegmenter {
    private val sentenceEndings = setOf('。', '！', '？', '!', '?', '；', ';')
    private val closingMarks = setOf('”', '’', '」', '』', '）', ')', '】', ']', '》', '〉')

    fun segment(raw: String): SegmentedText {
        val normalized = raw
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[\\t\\u00A0 ]+"), " ")
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        if (normalized.isBlank()) return SegmentedText("", emptyList())

        val paragraphTexts = normalized
            .split(Regex("\n+"))
            .map(String::trim)
            .filter(String::isNotEmpty)

        var globalSentenceIndex = 0
        val paragraphs = paragraphTexts.mapIndexedNotNull { paragraphIndex, paragraphText ->
            val sentenceTexts = splitSentences(paragraphText)
            if (sentenceTexts.isEmpty()) return@mapIndexedNotNull null
            val start = globalSentenceIndex
            val sentences = sentenceTexts.map { sentenceText ->
                SentenceSegment(
                    index = globalSentenceIndex++,
                    paragraphIndex = paragraphIndex,
                    text = sentenceText,
                )
            }
            ParagraphSegment(paragraphIndex, start, sentences)
        }

        return SegmentedText(
            normalizedText = paragraphTexts.joinToString("\n\n"),
            paragraphs = paragraphs,
        )
    }

    internal fun splitSentences(paragraph: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < paragraph.length) {
            val current = paragraph[index]
            val isDecimalDot = current == '.' &&
                index > 0 && index + 1 < paragraph.length &&
                paragraph[index - 1].isDigit() && paragraph[index + 1].isDigit()
            val isEnglishPeriodEnd = current == '.' && !isDecimalDot &&
                (index == paragraph.lastIndex || paragraph[index + 1].isWhitespace() || paragraph[index + 1] in closingMarks)
            val isEllipsisEnd = current == '…' && index + 1 < paragraph.length && paragraph[index + 1] == '…'
            val endsSentence = current in sentenceEndings || isEnglishPeriodEnd || isEllipsisEnd

            if (endsSentence) {
                if (isEllipsisEnd) index++
                var endExclusive = index + 1
                while (endExclusive < paragraph.length && paragraph[endExclusive] in closingMarks) {
                    endExclusive++
                }
                paragraph.substring(start, endExclusive).trim().takeIf(String::isNotEmpty)?.let(result::add)
                start = endExclusive
                index = endExclusive
            } else {
                index++
            }
        }
        paragraph.substring(start).trim().takeIf(String::isNotEmpty)?.let(result::add)
        return result
    }
}

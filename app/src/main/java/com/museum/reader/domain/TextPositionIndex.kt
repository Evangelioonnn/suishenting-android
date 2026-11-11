package com.museum.reader.domain

import com.museum.reader.model.SegmentedText

/** Offsets point into the actual text, including whitespace and paragraph separators. */
class TextPositionIndex(val sentenceStarts: IntArray, val paragraphStarts: IntArray) {
    fun sentenceAt(offset: Int): Int = preceding(sentenceStarts, offset)
    fun paragraphAt(offset: Int): Int = preceding(paragraphStarts, offset)
    private fun preceding(array: IntArray, value: Int): Int {
        if (array.isEmpty()) return -1
        val found = array.binarySearch(value)
        return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
    }
    companion object {
        fun raw(text: String, check: () -> Unit = {}): TextPositionIndex {
            val sentences = ArrayList<Int>(); val paragraphs = ArrayList<Int>()
            var lineStart = 0
            while (lineStart < text.length) {
                check()
                var lineEnd = lineStart
                while (lineEnd < text.length && text[lineEnd] != '\r' && text[lineEnd] != '\n') lineEnd++
                val line = text.substring(lineStart, lineEnd)
                if (line.isNotBlank()) {
                    paragraphs += lineStart + line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                    var from = 0
                    for (sentence in TextSegmenter.splitSentences(line)) {
                        val at = line.indexOf(sentence, from)
                        if (at >= 0) { sentences += lineStart + at; from = at + sentence.length }
                    }
                }
                lineStart = lineEnd + 1
                if (text.getOrNull(lineEnd) == '\r' && text.getOrNull(lineStart) == '\n') lineStart++
            }
            return TextPositionIndex(sentences.toIntArray(), paragraphs.toIntArray())
        }

        fun stored(content: SegmentedText): TextPositionIndex {
            var from = 0
            val offsets = content.sentences.map { sentence ->
                val at = content.normalizedText.indexOf(sentence.text, from)
                check(at >= 0) { "句子无法定位到实际正文" }
                from = at + sentence.text.length
                at
            }.toIntArray()
            return TextPositionIndex(offsets, content.paragraphs.map { offsets[it.sentenceStart] }.toIntArray())
        }
    }
}

data class TextSearchResult(val starts: IntArray = IntArray(0), val length: Int = 0) {
    val count: Int get() = starts.size
    fun range(index: Int): IntRange? = starts.getOrNull(index)?.let { it until it + length }
}

object TextSearch {
    fun find(text: String, query: String, check: () -> Unit = {}): TextSearchResult {
        if (query.isEmpty()) return TextSearchResult()
        var values = IntArray(64); var size = 0; var from = 0
        while (from <= text.length - query.length) {
            if (size % 1024 == 0) check()
            val at = text.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = at
            from = at + query.length
        }
        return TextSearchResult(values.copyOf(size), query.length)
    }
}

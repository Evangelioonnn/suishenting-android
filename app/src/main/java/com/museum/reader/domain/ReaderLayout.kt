package com.museum.reader.domain

import com.museum.reader.model.SegmentedText

data class ReaderBlock(val paragraph: Int, val sentence: Int = -1, val start: Int = 0, val end: Int = 0)

/** A single long sentence/paragraph must not force a million-character Text layout. */
class ReaderLayout(val blocks: List<ReaderBlock>, val firstItems: IntArray) {
    fun itemFor(sentence: Int, offset: Int = 0): Int {
        val first = firstItems.getOrNull(sentence) ?: return 0
        var low = first
        var high = (firstItems.getOrNull(sentence + 1)?.minus(1) ?: blocks.lastIndex)
        // A paragraph header may precede the following sentence.
        while (high > first && blocks[high].sentence != sentence) high--
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (blocks[middle].start <= offset) low = middle else high = middle - 1
        }
        return low
    }
    companion object {
        const val CHUNK = 800
        fun build(content: SegmentedText): ReaderLayout {
            val blocks = ArrayList<ReaderBlock>()
            val first = IntArray(content.sentences.size)
            for (paragraph in content.paragraphs) {
                blocks += ReaderBlock(paragraph.index)
                for (sentence in paragraph.sentences) {
                    first[sentence.index] = blocks.size
                    var at = 0
                    while (at < sentence.text.length) {
                        var end = (at + CHUNK).coerceAtMost(sentence.text.length)
                        if (end < sentence.text.length && sentence.text[end].isLowSurrogate()) end--
                        blocks += ReaderBlock(paragraph.index, sentence.index, at, end)
                        at = end
                    }
                }
            }
            return ReaderLayout(blocks, first)
        }
    }
}

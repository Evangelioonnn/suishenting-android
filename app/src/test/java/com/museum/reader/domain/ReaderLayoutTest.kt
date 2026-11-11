package com.museum.reader.domain
import org.junit.Assert.*
import org.junit.Test

class ReaderLayoutTest {
    @Test fun longSentenceIsVirtualizedWithoutChangingTextOrSentenceIdentity() {
        val text = "😀长".repeat(3000) + "。下一句。\n\n末段。"
        val content = TextSegmenter.segment(text)
        val layout = ReaderLayout.build(content)
        for (sentence in content.sentences) {
            val blocks = layout.blocks.filter { it.sentence == sentence.index }
            assertTrue(blocks.all { it.end - it.start <= ReaderLayout.CHUNK })
            assertEquals(sentence.text, blocks.joinToString("") { sentence.text.substring(it.start, it.end) })
            for (block in blocks) {
                assertFalse(sentence.text[block.start].isLowSurrogate())
                assertEquals(block, layout.blocks[layout.itemFor(sentence.index, block.start)])
            }
        }
        assertEquals(2, layout.blocks.count { it.sentence < 0 })
    }
}

package com.museum.reader.ui

import org.junit.Assert.*
import org.junit.Test

class ImportPreviewBoundaryTest {
    @Test fun pagesReassembleExactlyWithoutSplittingSupplementaryCharactersOrCrLf() {
        for (seam in listOf("🧭", "\r\n")) {
            val text = "甲".repeat(2399) + seam + "乙".repeat(2000) + "。" + "余".repeat(6500)
            val pages = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                val end = importPageEnd(text, start)
                assertTrue(end - start in 1..2400)
                pages += text.substring(start, end)
                start = end
            }
            assertEquals(2399, pages.first().length)
            assertTrue(pages[1].startsWith(seam))
            assertTrue(pages[1].endsWith("。"))
            assertEquals(text, pages.joinToString(""))
        }
    }
}

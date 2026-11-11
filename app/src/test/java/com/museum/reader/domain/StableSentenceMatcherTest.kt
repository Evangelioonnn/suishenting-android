package com.museum.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StableSentenceMatcherTest {
    @Test fun insertionKeepsRepeatedSentencesInTheirContext() {
        val before = listOf("开场。", "相同句。", "第一展厅。", "相同句。", "结束。")
        val after = listOf("新开场。", "开场。", "相同句。", "第一展厅。", "新增。", "相同句。", "结束。")
        assertEquals(listOf(null, 0, 1, 2, null, 3, 4), StableSentenceMatcher.match(before, after))
    }

    @Test fun movedUniqueSentenceKeepsIdentityAndChangedSentenceDoesNot() {
        val result = StableSentenceMatcher.match(
            listOf("一。", "二。", "三。", "四。"),
            listOf("三。", "一。", "二改。", "四。"),
        )
        assertEquals(listOf(2, 0, null, 3), result)
    }

    @Test fun longDocumentDoesNotRequireQuadraticDiff() {
        val before = (0 until 30_000).map { "第" + it + "句。" }
        val after = before.toMutableList().apply { add(15_000, "插入。"); removeAt(20_001) }
        val result = StableSentenceMatcher.match(before, after)
        assertEquals(14_999, result[14_999])
        assertNull(result[15_000])
        assertEquals(15_000, result[15_001])
        assertEquals(20_001, result[20_001])
        assertEquals(result.filterNotNull().size, result.filterNotNull().toSet().size)
    }
}

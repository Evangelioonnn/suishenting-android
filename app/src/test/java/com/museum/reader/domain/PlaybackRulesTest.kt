package com.museum.reader.domain

import com.museum.reader.model.LoopMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRulesTest {
    @Test
    fun `none stops at end`() {
        assertEquals(2, PlaybackRules.nextIndex(1, 3, LoopMode.NONE, 0, 0))
        assertNull(PlaybackRules.nextIndex(2, 3, LoopMode.NONE, 0, 0))
    }

    @Test
    fun `all wraps to first`() {
        assertEquals(0, PlaybackRules.nextIndex(2, 3, LoopMode.ALL, 0, 0))
    }

    @Test
    fun `range normalizes and wraps`() {
        assertEquals(2..4, PlaybackRules.normalizeRange(4, 2, 8))
        assertEquals(2, PlaybackRules.nextIndex(4, 8, LoopMode.RANGE, 4, 2))
        assertEquals(3, PlaybackRules.nextIndex(2, 8, LoopMode.RANGE, 4, 2))
    }
}

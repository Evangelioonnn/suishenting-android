package com.museum.reader.domain

import org.junit.Assert.*
import org.junit.Test

class SpeechTextChunksTest {
    @Test fun providerAndSystemLimitsNeverBreakValidSupplementaryCharacters() {
        val text = ("甲".repeat(299) + "𠮷🙂" + "a".repeat(3898) + "𠮷。\n").repeat(4)
        for ((units, bytes) in listOf(300 to 900, 3900 to Int.MAX_VALUE, 2 to 4)) {
            val chunks = SpeechTextChunks.split(text, units, bytes).toList()
            assertEquals(text, chunks.joinToString(""))
            assertTrue(chunks.all { it.isNotEmpty() && it.length <= units && it.toByteArray(Charsets.UTF_8).size <= bytes })
            assertTrue(chunks.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
        }
        assertTrue(SpeechTextChunks.split("", 300, 900).none())
    }
}

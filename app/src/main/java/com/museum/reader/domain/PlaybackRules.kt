package com.museum.reader.domain

import com.museum.reader.model.LoopMode

object PlaybackRules {
    fun normalizeRange(start: Int, end: Int, sentenceCount: Int): IntRange {
        if (sentenceCount <= 0) return IntRange.EMPTY
        val safeStart = start.coerceIn(0, sentenceCount - 1)
        val safeEnd = end.coerceIn(0, sentenceCount - 1)
        return minOf(safeStart, safeEnd)..maxOf(safeStart, safeEnd)
    }

    fun nextIndex(
        current: Int,
        sentenceCount: Int,
        loopMode: LoopMode,
        rangeStart: Int,
        rangeEnd: Int,
    ): Int? {
        if (sentenceCount <= 0) return null
        val safeCurrent = current.coerceIn(0, sentenceCount - 1)
        return when (loopMode) {
            LoopMode.NONE -> (safeCurrent + 1).takeIf { it < sentenceCount }
            LoopMode.ALL -> if (safeCurrent + 1 < sentenceCount) safeCurrent + 1 else 0
            LoopMode.RANGE -> {
                val range = normalizeRange(rangeStart, rangeEnd, sentenceCount)
                when {
                    safeCurrent < range.first -> range.first
                    safeCurrent >= range.last -> range.first
                    else -> safeCurrent + 1
                }
            }
        }
    }
}

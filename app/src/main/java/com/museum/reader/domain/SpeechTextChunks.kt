package com.museum.reader.domain

/** Linear, lossless partitioning for both TTS UTF-16 limits and provider UTF-8 byte limits. */
object SpeechTextChunks {
    fun split(text: String, maxUnits: Int, maxBytes: Int = Int.MAX_VALUE): Sequence<String> = sequence {
        require(maxUnits >= 2 && maxBytes >= 4) { "文字分段上限无效" }
        var start = 0
        while (start < text.length) {
            var end = start
            var bytes = 0
            while (end < text.length) {
                val first = text[end]
                val units = if (first.isHighSurrogate() && end + 1 < text.length && text[end + 1].isLowSurrogate()) 2 else 1
                val nextBytes = when {
                    units == 2 -> 4
                    first.code <= 0x7f -> 1
                    first.code <= 0x7ff -> 2
                    first.isSurrogate() -> 1 // Matches the UTF-8 encoder's replacement for invalid input.
                    else -> 3
                }
                if (end - start + units > maxUnits || bytes.toLong() + nextBytes > maxBytes) break
                end += units; bytes += nextBytes
            }
            check(end > start)
            yield(text.substring(start, end))
            start = end
        }
    }
}

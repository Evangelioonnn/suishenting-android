package com.museum.reader.domain

/** Keeps identity across edits using ordered context, without a quadratic long-document diff. */
object StableSentenceMatcher {
    fun match(previous: List<String>, next: List<String>): List<Int?> {
        val result = arrayOfNulls<Int>(next.size)
        fun align(oldFrom: Int, oldUntil: Int, newFrom: Int, newUntil: Int) {
            var a = oldFrom
            var b = newFrom
            var endA = oldUntil
            var endB = newUntil
            while (a < endA && b < endB && previous[a] == next[b]) result[b++] = a++
            while (a < endA && b < endB && previous[endA - 1] == next[endB - 1]) result[--endB] = --endA
            if (a == endA || b == endB) return
            val oldOccurrences = HashMap<String, Int>()
            for (i in a until endA) oldOccurrences[previous[i]] =
                if (oldOccurrences.containsKey(previous[i])) -1 else i
            val newCounts = HashMap<String, Int>()
            for (i in b until endB) newCounts[next[i]] = (newCounts[next[i]] ?: 0) + 1
            val candidates = (b until endB).mapNotNull { j ->
                oldOccurrences[next[j]]?.takeIf { it >= 0 && newCounts[next[j]] == 1 }?.let { it to j }
            }
            if (candidates.isNotEmpty()) {
                val tails = IntArray(candidates.size)
                val parent = IntArray(candidates.size) { -1 }
                var size = 0
                candidates.forEachIndexed { i, candidate ->
                    var low = 0
                    var high = size
                    while (low < high) {
                        val middle = (low + high) / 2
                        if (candidates[tails[middle]].first < candidate.first) low = middle + 1 else high = middle
                    }
                    if (low > 0) parent[i] = tails[low - 1]
                    tails[low] = i
                    if (low == size) size++
                }
                val anchors = ArrayList<Pair<Int, Int>>(size)
                var index = tails[size - 1]
                while (index >= 0) {
                    anchors += candidates[index]
                    index = parent[index]
                }
                anchors.reverse()
                var oldStart = a
                var newStart = b
                for ((oldIndex, newIndex) in anchors) {
                    align(oldStart, oldIndex, newStart, newIndex)
                    result[newIndex] = oldIndex
                    oldStart = oldIndex + 1
                    newStart = newIndex + 1
                }
                align(oldStart, endA, newStart, endB)
            } else if ((endA - a).toLong() * (endB - b) <= 200_000L) {
                val width = endB - b + 1
                val lengths = IntArray((endA - a + 1) * width)
                for (i in endA - a - 1 downTo 0) {
                    for (j in endB - b - 1 downTo 0) {
                        lengths[i * width + j] = if (previous[a + i] == next[b + j]) {
                            lengths[(i + 1) * width + j + 1] + 1
                        } else maxOf(lengths[(i + 1) * width + j], lengths[i * width + j + 1])
                    }
                }
                var i = 0
                var j = 0
                while (a + i < endA && b + j < endB) {
                    if (previous[a + i] == next[b + j]) result[b + j++] = a + i++
                    else if (lengths[(i + 1) * width + j] >= lengths[i * width + j + 1]) i++ else j++
                }
            } else {
                val positions = HashMap<String, ArrayDeque<Int>>()
                for (i in a until endA) positions.getOrPut(previous[i]) { ArrayDeque() }.addLast(i)
                var last = a - 1
                for (j in b until endB) {
                    val queue = positions[next[j]] ?: continue
                    while (queue.isNotEmpty() && queue.first() <= last) queue.removeFirst()
                    if (queue.isNotEmpty()) {
                        last = queue.removeFirst()
                        result[j] = last
                    }
                }
            }
        }
        align(0, previous.size, 0, next.size)
        val used = result.filterNotNull().toHashSet()
        val unused = previous.indices.filterNot { it in used }.groupBy { previous[it] }
        val missing = next.indices.filter { result[it] == null }.groupBy { next[it] }
        for ((text, indices) in missing) {
            val old = unused[text]
            if (indices.size == 1 && old?.size == 1) result[indices[0]] = old[0]
        }
        return result.toList()
    }
}

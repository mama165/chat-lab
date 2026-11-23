package com.example.chatlab

import java.text.Normalizer

/** An excerpt of the text read, and the passages to highlight (the words searched). */
data class Excerpt(val text: String, val marks: List<IntRange>)

object Highlight {
    /** Each character in lowercase without accent, keeping the length of the text. */
    fun foldChars(text: String): String = buildString(text.length) {
        for (char in text) {
            val plain = Normalizer.normalize(char.toString(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            append(if (plain.length == 1) plain.lowercase().single() else char.lowercaseChar())
        }
    }

    /** The passages of the text that contain one of the words searched, without accent or case. */
    fun ranges(text: String, query: String): List<IntRange> {
        val haystack = foldChars(text)
        val words = Library.fold(query).split(" ").filter { it.isNotBlank() }
        val found = ArrayList<IntRange>()
        for (word in words) {
            var from = haystack.indexOf(word)
            while (from >= 0) {
                found += from until from + word.length
                from = haystack.indexOf(word, from + word.length)
            }
        }
        return merge(found)
    }

    /**
     * An excerpt of at most [max] characters around the first word found, with "…" when the
     * text is cut. Without a word found, the start of the text.
     */
    fun excerpt(text: String, query: String, max: Int = 90): Excerpt {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= max) return Excerpt(clean, ranges(clean, query))
        val first = ranges(clean, query).firstOrNull()?.first ?: 0
        var start = (first - max / 3).coerceAtLeast(0)
        val end = (start + max).coerceAtMost(clean.length)
        start = (end - max).coerceAtLeast(0)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < clean.length) "…" else ""
        val body = prefix + clean.substring(start, end).trim() + suffix
        return Excerpt(body, ranges(body, query))
    }

    private fun merge(ranges: List<IntRange>): List<IntRange> {
        val sorted = ranges.sortedBy { it.first }
        val merged = ArrayList<IntRange>()
        for (range in sorted) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        return merged
    }
}

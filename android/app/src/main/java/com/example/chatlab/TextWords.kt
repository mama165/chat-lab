package com.example.chatlab

/** The words read from a line, merged into the words already found in the image (without duplicates). */
object TextWords {
    /**
     * Adds the words of [line] to [words], keyed by their lowercase form. Returns how many were new.
     * A capital I before an apostrophe is a misread l ("I'herbe"); a lone symbol or digit is noise.
     */
    fun add(words: MutableMap<String, String>, line: String): Int = addNew(words, line).size

    /** Same as [add], but returns the words that are new, as they were read. */
    fun addNew(words: MutableMap<String, String>, line: String): List<String> {
        val fresh = ArrayList<String>()
        val fixed = line.replace("I'", "l'").replace("I’", "l’")
        for (word in fixed.split(Regex("\\s+"))) {
            if (word.isNotBlank() && (word.length > 1 || word[0].isLetter())) {
                if (words.putIfAbsent(word.lowercase(), word) == null) fresh.add(word)
            }
        }
        return fresh
    }
}

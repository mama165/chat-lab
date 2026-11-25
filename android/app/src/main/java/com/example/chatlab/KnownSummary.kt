package com.example.chatlab

import org.json.JSONObject
import java.io.Reader

/**
 * The photos already in the library, kept small: only the size, the date and the type of each entry, read
 * one entry at a time. The whole library is never held in memory, so that the folder page cannot run out of
 * memory. The rules are the same as the analysis ([readKnown], [carry], [reusableMime]).
 */
class KnownSummary private constructor(
    private val byAddress: Map<String, Entry>,
    private val byIdentity: Map<KnownFiles.Identity, Entry>,
) {
    data class Entry(val size: Long, val modified: Long, val mime: String)

    /** The type already known for a photo that is unchanged: it is not read again. */
    fun reusableMime(file: FileNode): String? = unchanged(file)?.mime?.takeIf { it.isNotBlank() }

    /** True for a photo that is new or changed: it still has to be analysed. */
    fun toAnalyse(file: FileNode): Boolean = unchanged(file) == null

    private fun unchanged(file: FileNode): Entry? {
        val entry = byAddress[file.id]
            ?: byIdentity[KnownFiles.Identity(file.name, file.length, file.lastModified)]
            ?: return null
        if (file.length < 0) return null
        return entry.takeIf { it.size == file.length && it.modified == file.lastModified }
    }

    companion object {
        val EMPTY = KnownSummary(emptyMap(), emptyMap())

        /** Reads the library text of [reader] (a JSON array of entries), keeping the entries of [version] only. */
        fun parse(reader: Reader, version: Int): KnownSummary {
            val byAddress = HashMap<String, Entry>()
            val byIdentity = HashMap<KnownFiles.Identity, Entry>()
            forEachEntry(reader) { item ->
                if (!item.has("source") || item.optInt("analysis_version") != version) return@forEachEntry
                val entry = Entry(item.optLong("size"), item.optLong("modified"), item.optString("mime"))
                byAddress[item.getString("source")] = entry
                if (item.has("name") && item.has("modified")) {
                    byIdentity[KnownFiles.Identity(item.getString("name"), item.optLong("size", -1L), item.optLong("modified", -1L))] = entry
                }
            }
            return KnownSummary(byAddress, byIdentity)
        }

        /**
         * Calls [action] for each object of the top-level array, one at a time: only the text of the current
         * entry is held in memory. Strings are read with their escapes, so a brace inside a name does not count.
         */
        internal fun forEachEntry(reader: Reader, action: (JSONObject) -> Unit) {
            val buffer = CharArray(64 * 1024)
            val text = StringBuilder()
            var braces = 0
            var inString = false
            var escape = false
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                for (i in 0 until read) {
                    val c = buffer[i]
                    val capturing = braces > 0
                    if (inString) {
                        if (capturing) text.append(c)
                        when {
                            escape -> escape = false
                            c == '\\' -> escape = true
                            c == '"' -> inString = false
                        }
                        continue
                    }
                    when (c) {
                        '"' -> {
                            inString = true
                            if (capturing) text.append(c)
                        }
                        '{' -> {
                            if (!capturing) text.setLength(0)
                            braces++
                            text.append(c)
                        }
                        '}' -> {
                            if (capturing) {
                                text.append(c)
                                braces--
                                if (braces == 0) {
                                    runCatching { JSONObject(text.toString()) }.getOrNull()?.let(action)
                                    text.setLength(0)
                                }
                            }
                        }
                        else -> if (capturing) text.append(c)
                    }
                }
            }
        }
    }
}

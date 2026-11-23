package com.example.chatlab

import org.json.JSONArray
import org.json.JSONObject

/** A folder to analyse: the address given by the Android picker, and its name. */
data class SourceFolder(val uri: String, val name: String)

/** The list of folders to analyse, kept in the preferences. */
object SourceList {
    fun decode(text: String?): List<SourceFolder> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val uri = item.optString("uri")
                if (uri.isBlank()) null else SourceFolder(uri, item.optString("name").ifBlank { "Dossier" })
            }.distinctBy { it.uri }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun encode(list: List<SourceFolder>): String {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        return array.toString()
    }

    /**
     * Adds a folder. A folder already present is not doubled; a name already taken gets a
     * number ("Camera (2)"), so that the library tells apart two folders with the same name.
     */
    fun add(list: List<SourceFolder>, uri: String, name: String): List<SourceFolder> {
        if (list.any { it.uri == uri }) return list
        val clean = name.ifBlank { "Dossier" }
        return list + SourceFolder(uri, uniqueName(list, clean))
    }

    fun remove(list: List<SourceFolder>, uri: String): List<SourceFolder> = list.filter { it.uri != uri }

    /** The old setting, a single folder, becomes a list of one folder. */
    fun fromLegacy(uri: String?, name: String?): List<SourceFolder> =
        if (uri.isNullOrBlank()) emptyList() else listOf(SourceFolder(uri, name?.ifBlank { null } ?: "Dossier"))

    private fun uniqueName(list: List<SourceFolder>, name: String): String {
        val taken = list.map { it.name }.toSet()
        if (name !in taken) return name
        var number = 2
        while ("$name ($number)" in taken) number++
        return "$name ($number)"
    }
}

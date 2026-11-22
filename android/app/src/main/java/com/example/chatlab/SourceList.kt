package com.example.chatlab

import org.json.JSONArray
import org.json.JSONObject

/** Un dossier à analyser : l'adresse donnée par le sélecteur d'Android, et son nom. */
data class SourceFolder(val uri: String, val name: String)

/** La liste des dossiers à analyser, gardée dans les préférences. */
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
     * Ajoute un dossier. Un dossier déjà présent n'est pas doublé ; un nom déjà pris reçoit un
     * numéro (« Camera (2) »), pour que le carnet distingue deux dossiers de même nom.
     */
    fun add(list: List<SourceFolder>, uri: String, name: String): List<SourceFolder> {
        if (list.any { it.uri == uri }) return list
        val clean = name.ifBlank { "Dossier" }
        return list + SourceFolder(uri, uniqueName(list, clean))
    }

    fun remove(list: List<SourceFolder>, uri: String): List<SourceFolder> = list.filter { it.uri != uri }

    /** L'ancien réglage, un seul dossier, devient une liste d'un dossier. */
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

package com.example.chatlab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Une photo retirée d'une recherche, avec la date du retrait. */
data class Removal(val query: String, val source: String, val date: String)

object Removals {
    /** Toutes les photos retirées, toutes recherches confondues. */
    fun all(context: Context): List<Removal> {
        val json = LibraryStore.readRemovals(context)
        return json.keys().asSequence().flatMap { query ->
            val array = json.optJSONArray(query) ?: JSONArray()
            (0 until array.length()).map { index ->
                val item = array.opt(index)
                if (item is JSONObject) Removal(query, item.optString("source"), item.optString("date", ""))
                else Removal(query, item.toString(), "")
            }
        }.toList().sortedWith(compareBy<Removal>({ it.query }, { it.date }))
    }

    /** Ajoute des photos retirées pour une recherche, avec la date du jour. */
    fun add(context: Context, query: String, sources: Collection<String>) {
        val json = LibraryStore.readRemovals(context)
        val array = json.optJSONArray(query) ?: JSONArray().also { json.put(query, it) }
        val already = (0 until array.length()).map { index ->
            val item = array.opt(index)
            if (item is JSONObject) item.optString("source") else item.toString()
        }.toSet()
        val today = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date())
        sources.filter { it !in already }.forEach { source ->
            array.put(JSONObject().put("source", source).put("date", today))
        }
        LibraryStore.writeRemovals(context, json)
    }

    /** Rétablit une photo : elle revient dans la recherche d'origine. */
    fun restore(context: Context, query: String, source: String) {
        val json = LibraryStore.readRemovals(context)
        val array = json.optJSONArray(query) ?: return
        val kept = JSONArray()
        for (index in 0 until array.length()) {
            val item = array.opt(index)
            val itemSource = if (item is JSONObject) item.optString("source") else item.toString()
            if (itemSource != source) kept.put(item)
        }
        json.put(query, kept)
        LibraryStore.writeRemovals(context, json)
    }
}

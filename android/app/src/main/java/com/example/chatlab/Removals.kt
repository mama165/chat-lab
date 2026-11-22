package com.example.chatlab

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Les retraits enregistrés dans retraits.json (dossier chatlab). */
object Removals {
    fun all(context: Context): List<Removal> = RemovalsJson.all(LibraryStore.readRemovals(context))

    fun sourcesFor(context: Context, query: String): Set<String> =
        RemovalsJson.sourcesFor(LibraryStore.readRemovals(context), query)

    fun add(context: Context, query: String, sources: Collection<String>) {
        val today = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date())
        LibraryStore.writeRemovals(context, RemovalsJson.add(LibraryStore.readRemovals(context), query, sources, today))
    }

    fun restore(context: Context, query: String, source: String) {
        LibraryStore.writeRemovals(context, RemovalsJson.restore(LibraryStore.readRemovals(context), query, source))
    }
}

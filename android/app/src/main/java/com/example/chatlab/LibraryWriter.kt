package com.example.chatlab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Écrit le carnet (library.json). N'a besoin d'aucun modèle : on peut l'appeler sans rien analyser. */
object LibraryWriter {
    fun write(context: Context, results: List<ImageResult>, keep: List<JSONObject> = emptyList()): File {
        val entries = JSONArray()
        val added = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        for (r in results) {
            if (r.error != null || r.vector == null) continue
            val entry = JSONObject()
                // Deux dossiers peuvent contenir un même nom de fichier : l'adresse d'origine est unique.
                .put("id", if (r.source.isNotBlank()) r.source else r.file.name)
                .put("path", if (r.source.isNotBlank()) r.source else r.file.path)
                .put("name", r.file.name)
                .put("kind", "image")
                .put("analysis_version", ANALYSIS_VERSION)
                .put("size", if (r.source.isNotBlank()) r.size else r.file.length())
                .put("added_at", added.format(Date(if (r.modified > 0) r.modified else r.file.lastModified())))
                .put("faces", r.faces)
                .put("portrait", r.portrait)
                .put("vectors", JSONArray().put(JSONArray(r.vector.toList())))
            r.indoor?.let { entry.put("indoor", it) }
            if (r.text.isNotBlank()) entry.put("text", r.text)
            if (r.source.isNotBlank()) {
                entry.put("source", r.source)
                entry.put("modified", r.modified)
            }
            if (r.taken.isNotBlank()) entry.put("taken", r.taken)
            if (r.takenMs > 0) entry.put("taken_ms", r.takenMs)
            if (r.folder.isNotBlank()) entry.put("folder", r.folder)
            if (r.mime.isNotBlank()) entry.put("mime", r.mime)
            entries.put(entry)
        }
        // Les photos des dossiers retirés de la liste restent dans le carnet, telles quelles.
        keep.forEach { entries.put(it) }
        // Le carnet va dans le dossier chatlab choisi (il survit à une réinstallation).
        LibraryStore.write(context, entries.toString())
        return File(context.filesDir, "library.json")
    }
}

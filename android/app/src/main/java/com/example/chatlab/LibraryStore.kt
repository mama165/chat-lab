package com.example.chatlab

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Le carnet (library.json) vit dans le dossier chatlab que vous avez choisi : il survit à une
 * désinstallation ou à une réinstallation de l'application. Sans dossier utilisable, il reste
 * dans l'espace privé de l'application.
 */
object LibraryStore {
    private const val NAME = "library.json"
    private const val REMOVALS = "retraits.json"

    private fun folder(context: Context): DocumentFile? {
        val uri = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)
            ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(uri))?.takeIf { it.canWrite() }
    }

    /** Le contenu du carnet, ou null s'il n'existe pas encore. */
    fun read(context: Context): String? {
        val inFolder = folder(context)?.findFile(NAME)
        if (inFolder != null && inFolder.exists()) {
            return try {
                context.contentResolver.openInputStream(inFolder.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            } catch (e: Exception) {
                null
            }
        }
        val local = File(context.filesDir, NAME)
        return if (local.exists()) local.readText(Charsets.UTF_8) else null
    }

    fun write(context: Context, text: String) {
        val dir = folder(context)
        if (dir == null) {
            File(context.filesDir, NAME).writeText(text, Charsets.UTF_8)
            return
        }
        val target = dir.findFile(NAME) ?: dir.createFile("application/json", NAME)
            ?: error("impossible de créer le carnet dans le dossier chatlab")
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("impossible d'écrire le carnet dans le dossier chatlab")
    }

    /** Écrit la trace d'un plantage dans erreur.txt, dans le dossier chatlab, pour pouvoir la lire. */
    fun saveErrorReport(context: Context, text: String) {
        try {
            val dir = folder(context) ?: return
            val target = dir.findFile("erreur.txt") ?: dir.createFile("text/plain", "erreur.txt") ?: return
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            // Rien à faire de plus : on ne veut pas provoquer un second plantage.
        }
    }

    /** Les retraits : pour chaque recherche, les photos écartées. */
    fun readRemovals(context: Context): JSONObject {
        val text = try {
            val inFolder = folder(context)?.findFile(REMOVALS)
            if (inFolder != null && inFolder.exists()) {
                context.contentResolver.openInputStream(inFolder.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            } else {
                File(context.filesDir, REMOVALS).takeIf { it.exists() }?.readText(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
        return try {
            JSONObject(text ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
    }

    /** Écarte une photo pour une recherche donnée ; le retrait est gardé d'un lancement à l'autre. */
    fun addRemoval(context: Context, query: String, source: String) {
        val all = readRemovals(context)
        val list = all.optJSONArray(query) ?: JSONArray().also { all.put(query, it) }
        list.put(source)
        val text = all.toString()
        val dir = folder(context)
        if (dir == null) {
            File(context.filesDir, REMOVALS).writeText(text, Charsets.UTF_8)
            return
        }
        val target = dir.findFile(REMOVALS) ?: dir.createFile("application/json", REMOVALS)
            ?: error("impossible de créer retraits.json dans le dossier chatlab")
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("impossible d'écrire retraits.json")
    }

    /** Écrit l'ensemble des retraits, dans le dossier chatlab si possible. */
    fun writeRemovals(context: Context, json: JSONObject) {
        val text = json.toString()
        val dir = folder(context)
        if (dir == null) {
            File(context.filesDir, REMOVALS).writeText(text, Charsets.UTF_8)
            return
        }
        val target = dir.findFile(REMOVALS) ?: dir.createFile("application/json", REMOVALS)
            ?: error("impossible de créer retraits.json dans le dossier chatlab")
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("impossible d'écrire retraits.json")
    }
}

/** Le carnet enregistré, lu depuis le dossier chatlab (ou l'espace privé à défaut). */
fun Library.load(context: Context): List<LibraryEntry> = parse(LibraryStore.read(context))

/** Taille du carnet enregistré, en octets (0 s'il n'existe pas). */
fun LibraryStore.librarySize(context: Context): Long = try {
    val uri = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)
    val inFolder = uri?.let { DocumentFile.fromTreeUri(context, Uri.parse(it))?.findFile("library.json") }
    inFolder?.length() ?: File(context.filesDir, "library.json").takeIf { it.exists() }?.length() ?: 0L
} catch (e: Exception) {
    0L
}

/**
 * Tout effacer : le carnet et les retraits, dans le dossier chatlab et dans l'espace privé.
 * Les photos, les modèles et les dossiers choisis ne sont pas touchés.
 */
fun LibraryStore.wipeAnalysis(context: Context) {
    val uri = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)
    val folder = uri?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) }
    for (name in listOf("library.json", "retraits.json")) {
        try {
            folder?.findFile(name)?.delete()
        } catch (e: Exception) {
            // Un fichier déjà absent ou en lecture seule ne bloque pas le reste.
        }
        File(context.filesDir, name).delete()
    }
}

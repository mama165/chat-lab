package com.example.chatlab

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Le carnet (library.json) vit dans le dossier chatlab que vous avez choisi : il survit à une
 * désinstallation ou à une réinstallation de l'application. Sans dossier utilisable, il reste
 * dans l'espace privé de l'application.
 */
object LibraryStore {
    private const val NAME = "library.json"

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
}

package com.example.chatlab

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Écrit le fichier de chaque analyse terminée. Sur le téléphone, le dossier ChatLab/analyses du
 * stockage interne, accessible depuis l'appli Fichiers ; sans l'accès à tous les fichiers, le
 * dossier privé de l'appli. Une erreur d'écriture ne doit jamais faire échouer l'analyse.
 */
object RunArchive {
    fun directory(context: Context): File =
        if (StorageAccess.granted()) File(Environment.getExternalStorageDirectory(), "ChatLab/analyses")
        else File(context.filesDir, "analyses")

    fun save(context: Context, record: AnalysisRecord): File? = try {
        val dir = directory(context).apply { mkdirs() }
        File(dir, AnalysisFiles.nameFor(record.startMs)).also { it.writeText(record.toJson().toString(2)) }
    } catch (e: Exception) {
        null
    }

    /** Les fichiers d'analyse, du plus récent au plus ancien. */
    fun list(context: Context): List<File> =
        directory(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            .orEmpty()
}

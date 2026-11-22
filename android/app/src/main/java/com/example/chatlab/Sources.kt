package com.example.chatlab

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream

private const val PATH_PREFIX = "file:"

/** L'adresse d'un dossier choisi par chemin : « file: » suivi du chemin absolu. */
fun pathUri(path: String): String = PATH_PREFIX + path

/** Les dossiers à analyser, gardés dans les préférences. L'ancien dossier unique est repris. */
object Sources {
    private const val KEY = "sources"

    fun read(context: Context): List<SourceFolder> {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val stored = SourceList.decode(preferences.getString(KEY, null))
        if (stored.isNotEmpty() || preferences.contains(KEY)) return stored
        // Première lecture après la mise à jour : le dossier unique d'avant devient une liste d'un dossier.
        val legacy = preferences.getString(KEY_SOURCE, null)
        val migrated = SourceList.fromLegacy(legacy, legacy?.let { nameOf(context, Uri.parse(it)) })
        if (migrated.isNotEmpty()) write(context, migrated)
        return migrated
    }

    /** Ajoute le dossier choisi dans le sélecteur d'Android et garde l'accès après un redémarrage. */
    fun add(context: Context, uri: Uri): List<SourceFolder> {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Un dossier qui n'accepte pas l'accès durable sera signalé à l'analyse.
        }
        val next = SourceList.add(read(context), uri.toString(), nameOf(context, uri))
        write(context, next)
        return next
    }

    /** Ajoute un dossier de l'arbre du stockage interne, par son chemin. */
    fun addPath(context: Context, path: String): List<SourceFolder> {
        val next = SourceList.add(read(context), pathUri(path), File(path).name)
        write(context, next)
        return next
    }

    /** La racine à lire pour un dossier choisi : par chemin (stockage interne) ou par le sélecteur. */
    fun rootOf(context: Context, folder: SourceFolder): DirNode =
        if (folder.uri.startsWith(PATH_PREFIX)) JavaDirNode(File(folder.uri.removePrefix(PATH_PREFIX)))
        else SafDir.root(context, folder)

    /** Vide la liste des dossiers à analyser. Le dossier chatlab (modèles et carnet) reste choisi. */
    fun clear(context: Context) = write(context, emptyList())

    fun remove(context: Context, uri: String): List<SourceFolder> {
        val next = SourceList.remove(read(context), uri)
        write(context, next)
        return next
    }

    private fun write(context: Context, list: List<SourceFolder>) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(KEY, SourceList.encode(list)).apply()
    }

    fun nameOf(context: Context, uri: Uri): String = try {
        DocumentFile.fromTreeUri(context, uri)?.name
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "Dossier"
}

/** Un fichier d'un dossier choisi, lu par le fournisseur de documents d'Android. */
class SafFile(
    private val context: Context,
    tree: Uri,
    documentId: String,
    override val name: String,
    override val length: Long,
    override val lastModified: Long,
) : FileNode {
    private val uri: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    override val id: String get() = uri.toString()
    override fun open(): InputStream? = context.contentResolver.openInputStream(uri)
}

/**
 * Un dossier d'un arbre choisi. Le contenu est lu en une seule requête (nom, type, taille, date)
 * au lieu d'une par fichier, ce qui est beaucoup plus rapide. Une requête refusée ou impossible
 * donne null : le dossier est « refusé ».
 */
class SafDir(
    private val context: Context,
    private val tree: Uri,
    private val documentId: String,
    override val name: String,
) : DirNode {
    override fun children(): List<Any>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = try {
            context.contentResolver.query(childrenUri, columns, null, null, null)
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        } ?: return null
        val found = ArrayList<Any>()
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val label = it.getString(1) ?: id.substringAfterLast('/')
                val mime = it.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    found += SafDir(context, tree, id, label)
                } else {
                    val size = if (it.isNull(3)) -1L else it.getLong(3)
                    val modified = if (it.isNull(4)) 0L else it.getLong(4)
                    found += SafFile(context, tree, id, label, size, modified)
                }
            }
        }
        return found
    }

    companion object {
        /** La racine d'un dossier choisi : son contenu est lu à partir de là. */
        fun root(context: Context, folder: SourceFolder): SafDir {
            val tree = Uri.parse(folder.uri)
            return SafDir(context, tree, DocumentsContract.getTreeDocumentId(tree), folder.name)
        }
    }
}

/** Ce qu'on sait d'un dossier au moment où on le choisit. */
data class FolderStatus(
    val folder: SourceFolder,
    val state: State,
    /** Nombre d'images lisibles trouvées, quand le dossier est lisible. */
    val photos: Int = 0,
    val ignored: Int = 0,
    val refused: List<String> = emptyList(),
) {
    enum class State { READABLE, REFUSED, EMPTY }
}

object FolderCheck {
    /** Lit le dossier (sans l'analyser) pour dire s'il est lisible, refusé ou sans image. */
    fun check(context: Context, folder: SourceFolder): FolderStatus {
        val scan = try {
            FolderScanner.scan(Sources.rootOf(context, folder))
        } catch (e: Exception) {
            return FolderStatus(folder, FolderStatus.State.REFUSED)
        }
        return when {
            scan.rootRefused -> FolderStatus(folder, FolderStatus.State.REFUSED)
            scan.candidates.isEmpty() && scan.refused.isEmpty() -> FolderStatus(folder, FolderStatus.State.EMPTY, 0, scan.ignored.size)
            else -> FolderStatus(folder, FolderStatus.State.READABLE, scan.candidates.size, scan.ignored.size, scan.refused)
        }
    }
}

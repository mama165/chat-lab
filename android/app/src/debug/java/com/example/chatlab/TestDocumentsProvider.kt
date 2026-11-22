package com.example.chatlab

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.net.URLConnection

/**
 * Un fournisseur de documents de test (version debug seulement). Il sert le dossier [base] comme un
 * arbre de documents, exactement comme le ferait la galerie ou un stockage cloud, pour tester la
 * lecture des dossiers choisis par l'utilisateur sans passer par le sélecteur d'Android.
 *
 * Les identifiants de documents sont des chemins relatifs à [base], préfixés par « root ».
 */
class TestDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_COLUMNS)
        cursor.newRow()
            .add(Root.COLUMN_ROOT_ID, "test")
            .add(Root.COLUMN_DOCUMENT_ID, ROOT)
            .add(Root.COLUMN_TITLE, rootName)
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_COLUMNS)
        addRow(cursor, documentId)
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        // Un dossier refusé : le fournisseur lève la même erreur que le système pour un accès interdit.
        if (parentDocumentId in refused) throw SecurityException("accès refusé : $parentDocumentId")
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_COLUMNS)
        val folder = fileOf(parentDocumentId)
        folder.listFiles()?.sortedBy { it.name }?.forEach { addRow(cursor, idOf(it)) }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (documentId in unreadable) throw FileNotFoundException("lecture impossible : $documentId")
        val file = fileOf(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    private fun addRow(cursor: MatrixCursor, documentId: String) {
        val file = fileOf(documentId)
        val isDir = file.isDirectory
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, if (documentId == ROOT) rootName else file.name)
            .add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else mimeOf(file))
            .add(Document.COLUMN_SIZE, if (isDir) null else file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, 0)
    }

    private fun mimeOf(file: File): String = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"

    private fun idOf(file: File): String = ROOT + "/" + file.relativeTo(base).path

    private fun fileOf(documentId: String): File {
        val relative = documentId.removePrefix(ROOT).trimStart('/')
        return if (relative.isEmpty()) base else File(base, relative)
    }

    companion object {
        const val AUTHORITY = "com.example.chatlab.testdocuments"
        const val ROOT = "root"

        /** Le dossier servi, choisi par chaque test. */
        @Volatile
        var base: File = File("/nonexistent")

        /** Le nom affiché pour la racine (« Camera »). */
        @Volatile
        var rootName: String = "Camera"

        /** Identifiants de dossiers dont la lecture est refusée, par exemple « root/Privé ». */
        val refused: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

        /** Identifiants de fichiers qui ne peuvent pas être ouverts. */
        val unreadable: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

        fun reset() {
            refused.clear()
            unreadable.clear()
            rootName = "Camera"
        }

        /** L'adresse de l'arbre, comme celle que renvoie le sélecteur de dossiers. */
        fun treeUri(): android.net.Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT)

        private val DEFAULT_ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)
        private val DEFAULT_DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}

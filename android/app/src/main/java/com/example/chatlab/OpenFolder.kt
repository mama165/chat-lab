package com.example.chatlab

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import java.io.File

/**
 * Folders the app creates on the shared storage, under ChatLab. They are opened from the settings
 * and only exist when the app has access to all files.
 */
object OpenFolder {
    /** True when [relative] (for example "diagnostics") exists under ChatLab on the shared storage. */
    fun exists(relative: String): Boolean =
        StorageAccess.granted() && File(Environment.getExternalStorageDirectory(), "ChatLab/$relative").isDirectory

    /** Opens the folder in the Files app; says so when it is not reachable from there. */
    fun open(context: Context, relative: String) {
        if (!StorageAccess.granted()) {
            Toast.makeText(context, "Le dossier est dans l'espace privé de l'app.", Toast.LENGTH_LONG).show()
            return
        }
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:ChatLab/$relative")
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "Impossible d'ouvrir le dossier.", Toast.LENGTH_LONG).show() }
    }
}

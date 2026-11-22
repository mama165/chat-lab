package com.example.chatlab

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile

/** Les deux choix de dossier, utilisables depuis l'onglet Photos et depuis les réglages. */
class FolderPickers(val chooseChatlab: () -> Unit, val chooseSource: () -> Unit)

@Composable
fun rememberFolderPickers(onChanged: () -> Unit): FolderPickers {
    val context = LocalContext.current
    val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val chatlab = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Lecture et écriture, gardées après un redémarrage : le carnet est enregistré dans ce dossier.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            preferences.edit().putString(KEY_FOLDER, uri.toString()).apply()
            onChanged()
        }
    }
    val source = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            preferences.edit().putString(KEY_SOURCE, uri.toString()).apply()
            onChanged()
        }
    }
    return FolderPickers(chooseChatlab = { chatlab.launch(null) }, chooseSource = { source.launch(null) })
}

/** Le nom d'un dossier choisi (« chatlab », « Camera »), ou null s'il n'est pas choisi. */
fun folderName(context: Context, key: String): String? {
    val uri = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(key, null) ?: return null
    return try {
        DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name ?: "choisi"
    } catch (e: Exception) {
        "choisi"
    }
}

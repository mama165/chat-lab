package com.example.chatlab

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile

/**
 * The folder choices: the chatlab folder (models and library), and adding a folder to
 * analyse. Android only lets you choose one folder at a time: we add as many as we want.
 */
class FolderPickers(val chooseChatlab: () -> Unit, val addSource: () -> Unit)

@Composable
fun rememberFolderPickers(onAddSource: (() -> Unit)? = null, onChanged: () -> Unit): FolderPickers {
    val context = LocalContext.current
    val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val chatlab = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Read and write, kept after a restart: the library is saved in this folder.
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
            Sources.add(context, uri)
            onChanged()
        }
    }
    // The internal storage page replaces the picker when the page asks for it.
    return FolderPickers(
        chooseChatlab = { chatlab.launch(null) },
        addSource = { if (onAddSource != null) onAddSource() else source.launch(null) },
    )
}

/** The name of the chatlab folder ("chatlab"), or null if it is not chosen. */
fun folderName(context: Context, key: String): String? {
    val uri = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(key, null) ?: return null
    return try {
        DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name ?: "choisi"
    } catch (e: Exception) {
        "choisi"
    }
}

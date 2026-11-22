package com.example.chatlab

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * L'accès à tous les fichiers, nécessaire pour lire le stockage interne en entier. Sans lui,
 * Android ne laisse voir que les dossiers choisis un par un.
 */
object StorageAccess {
    fun granted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    /** Ouvre l'écran Android où l'on accorde l'accès à tous les fichiers à l'appli. */
    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        context.startActivity(intent)
    }
}

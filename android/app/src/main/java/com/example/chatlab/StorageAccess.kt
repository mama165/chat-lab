package com.example.chatlab

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * All-files access, needed to read the whole internal storage. Without it,
 * Android only lets us see the folders chosen one by one.
 */
object StorageAccess {
    fun granted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    /** Opens the Android screen where the all-files access is granted to the app. */
    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        context.startActivity(intent)
    }
}

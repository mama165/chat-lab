package com.example.chatlab

/** What the Photos tab shows: no folder yet (view 1.1.3), the folders at rest (view 1.2), or the analysis running. */
enum class PhotosTab { EMPTY, FOLDERS, RUNNING }

/** The rule, without Android: nothing to show until the chatlab folder and one folder of photos are chosen. */
internal fun photosTab(chatlabChosen: Boolean, folders: Int, running: Boolean): PhotosTab = when {
    !chatlabChosen || folders == 0 -> PhotosTab.EMPTY
    running -> PhotosTab.RUNNING
    else -> PhotosTab.FOLDERS
}

package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotosTabStateTest {
    @Test
    fun withoutAFolderOfPhotosTheTabIsEmpty() {
        // view 1.1.3: "Pas encore de photos", even when the chatlab folder is there
        assertEquals(PhotosTab.EMPTY, photosTab(chatlabChosen = true, folders = 0, running = false))
    }

    @Test
    fun withoutTheChatlabFolderTheTabIsEmptyToo() {
        assertEquals(PhotosTab.EMPTY, photosTab(chatlabChosen = false, folders = 2, running = false))
    }

    @Test
    fun aFolderAtRestShowsTheFolders() {
        // view 1.2: the folders, the performance and "Lancer l'analyse"
        assertEquals(PhotosTab.FOLDERS, photosTab(chatlabChosen = true, folders = 1, running = false))
    }

    @Test
    fun aRunningAnalysisShowsTheProgress() {
        assertEquals(PhotosTab.RUNNING, photosTab(chatlabChosen = true, folders = 1, running = true))
    }
}

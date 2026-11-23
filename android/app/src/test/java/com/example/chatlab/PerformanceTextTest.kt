package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceTextTest {
    @Test
    fun the_performances_have_the_names_of_the_screens() {
        assertEquals(listOf("Léger", "Normal", "Rapide", "Maximum"), AnalysisLevel.entries.map { it.label })
    }

    @Test
    fun the_photos_at_once_read_in_french() {
        assertEquals("1 photo à la fois", AnalysisLevel.ECONOMIQUE.photosAtOnce())
        assertEquals("2 photos à la fois", AnalysisLevel.EQUILIBRE.photosAtOnce())
        assertEquals("4 photos à la fois", AnalysisLevel.MAXIMAL.photosAtOnce())
    }

    @Test
    fun a_finished_folder_says_how_many_photos_were_new() {
        val folder = FolderProgress("Camera", done = 4, total = 4)
        assertEquals("Terminé, 4 nouvelles photos · 4 / 4", folderDetail(folder))
    }

    @Test
    fun a_folder_being_read_says_which_photo_it_reads() {
        val folder = FolderProgress("Pictures", done = 0, total = 6, current = "IMG_2041.jpg")
        assertEquals("Nouvelles photos · en cours : IMG_2041.jpg · 0 / 6", folderDetail(folder))
    }

    @Test
    fun a_folder_not_read_yet_is_waiting() {
        assertEquals("En attente, pas encore lu · 0 / 4", folderDetail(FolderProgress("Screenshots", done = 0, total = 4)))
    }

    @Test
    fun a_folder_with_nothing_new_has_nothing_to_do() {
        assertEquals("Déjà dans le carnet · rien à faire", folderDetail(FolderProgress("WhatsApp Images", done = 0, total = 0)))
    }

    @Test
    fun the_level_name_is_part_of_the_progress() {
        assertEquals("", Progress().levelLabel)
        assertEquals("Normal", Progress(running = true, levelLabel = "Normal").levelLabel)
    }
}

class LaterFolderTest {
    @Test
    fun a_folder_added_during_the_analysis_waits_for_the_next_one() {
        val folder = FolderProgress("WhatsApp Images", done = 0, total = 0, later = true)
        assertEquals(FolderMark.WAITING, folder.mark())
        assertEquals("Ajouté pendant l'analyse, lu à la prochaine", folderDetail(folder))
    }

    @Test
    fun a_folder_of_the_analysis_is_not_marked_as_later() {
        assertEquals(FolderMark.FINISHED, FolderProgress("Camera", done = 4, total = 4).mark())
    }
}

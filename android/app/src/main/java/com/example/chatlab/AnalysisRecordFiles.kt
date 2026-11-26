package com.example.chatlab

import java.io.File

/**
 * The analysis record kept in the private space: the photo table and its vectors. "Tout effacer" erases them.
 * The migration marker (photos.migrated) is written by the wipe: without it, the backup of the library would be
 * imported again at the next start.
 */
object AnalysisRecordFiles {
    val RECORD = listOf("photos.db", "photos.db-journal", "photos.db-wal", "photos.db-shm", "vectors.bin")
    const val MIGRATED = "photos.migrated"

    /** Size in bytes of the record files in [dir]. */
    fun size(dir: File): Long = RECORD.sumOf { name -> File(dir, name).takeIf { it.exists() }?.length() ?: 0L }

    /**
     * Deletes the record files in [dir] and returns how many were deleted. Other files are not touched.
     * The marker is written even when the marker was missing: otherwise the start would import the backup
     * of the library again, and the photos would come back.
     */
    fun wipe(dir: File): Int {
        val deleted = RECORD.count { name -> File(dir, name).takeIf { it.exists() }?.delete() == true }
        File(dir, MIGRATED).writeText("wiped")
        return deleted
    }
}

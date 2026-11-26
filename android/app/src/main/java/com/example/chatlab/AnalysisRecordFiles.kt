package com.example.chatlab

import java.io.File

/**
 * The analysis record kept in the private space: the photo table and its vectors. "Tout effacer" erases them.
 * The migration marker (photos.migrated) stays: without it, an old copy of the library would be imported again.
 */
object AnalysisRecordFiles {
    val RECORD = listOf("photos.db", "photos.db-journal", "photos.db-wal", "photos.db-shm", "vectors.bin")

    /** Size in bytes of the record files in [dir]. */
    fun size(dir: File): Long = RECORD.sumOf { name -> File(dir, name).takeIf { it.exists() }?.length() ?: 0L }

    /** Deletes the record files in [dir] and returns how many were deleted. Other files are not touched. */
    fun wipe(dir: File): Int = RECORD.count { name -> File(dir, name).takeIf { it.exists() }?.delete() == true }
}

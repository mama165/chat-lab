package com.example.chatlab

import java.util.Calendar
import java.util.TimeZone

/** Small factories shared by the tests. Everything happens in UTC, at a fixed time. */
object TestData {
    val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    fun utc(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(UTC).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    /** Dimanche 22 novembre 2026, 14:00. */
    val NOW: Long = utc(2026, 11, 22, 14, 0)
    val TIME = When(NOW, UTC)

    fun minutesAgo(minutes: Long): Long = NOW - minutes * 60_000L
    fun hoursAgo(hours: Long): Long = NOW - hours * 3_600_000L
    fun daysAgo(days: Long): Long = NOW - days * 24 * 3_600_000L

    fun entry(
        id: String,
        text: String = "",
        indoor: Boolean? = null,
        faces: Int = 0,
        taken: String = "",
        vector: FloatArray? = null,
        name: String = "$id.jpg",
        source: String = "content://photos/$id",
        folder: String = "",
        mime: String = "",
        takenMs: Long = 0L,
        closeUp: Boolean = false,
    ) = LibraryEntry(
        id = id,
        name = name,
        path = "/files/$id.jpg",
        indoor = indoor,
        faces = faces,
        text = text,
        vector = vector,
        taken = taken,
        source = source,
        folder = folder,
        mime = mime,
        takenMs = takenMs,
        closeUp = closeUp,
    )

    fun match(id: String, score: Float, indoor: Boolean? = null, faces: Int = 0, takenMs: Long = 0L) =
        Match(entry(id, indoor = indoor, faces = faces, takenMs = takenMs), score)
}

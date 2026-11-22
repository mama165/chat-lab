package com.example.chatlab

/** Petites fabriques partagées par les tests. */
object TestData {
    fun entry(
        id: String,
        text: String = "",
        indoor: Boolean? = null,
        faces: Int = 0,
        taken: String = "",
        vector: FloatArray? = null,
        name: String = "$id.jpg",
        source: String = "content://photos/$id",
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
    )

    fun match(id: String, score: Float, indoor: Boolean? = null, faces: Int = 0, taken: String = "") =
        Match(entry(id, indoor = indoor, faces = faces, taken = taken), score)

    val NOV_2026 = SimpleDate(2026, 11, 22)
}

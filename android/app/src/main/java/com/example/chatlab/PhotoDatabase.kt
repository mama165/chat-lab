package com.example.chatlab

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One photo as it is stored: the facts in the table, the vector in vectors.bin. */
class PhotoRecord(
    val address: String,
    val name: String,
    val path: String,
    val folder: String?,
    val mime: String?,
    val size: Long,
    val modified: Long?,
    val taken: String?,
    val takenMs: Long?,
    val analysisVersion: Int,
    val faces: Int,
    val portrait: Boolean,
    val indoor: Boolean?,
    val text: String?,
    val vector: FloatArray,
)

/**
 * The photo table, in SQLite (part of Android, no library to add). The address is the primary key:
 * writing the same photo again replaces its row, it never adds a second one.
 */
class PhotoDatabase(context: Context) : SQLiteOpenHelper(context, "photos.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE photo (
              address          TEXT    PRIMARY KEY NOT NULL,
              name             TEXT    NOT NULL,
              path             TEXT    NOT NULL,
              folder           TEXT,
              mime             TEXT,
              size             INTEGER NOT NULL,
              modified         INTEGER,
              taken            TEXT,
              taken_ms         INTEGER,
              analysis_version INTEGER NOT NULL,
              faces            INTEGER NOT NULL DEFAULT 0,
              portrait         INTEGER NOT NULL DEFAULT 0,
              indoor           INTEGER,
              text             TEXT,
              vector_row       INTEGER NOT NULL UNIQUE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX photo_identity ON photo (name, size, modified)")
        db.execSQL("CREATE INDEX photo_version ON photo (analysis_version)")
        db.execSQL("CREATE INDEX photo_folder ON photo (folder)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** The row of every known photo, by address. */
    fun rowsByAddress(): Map<String, Long> {
        val rows = HashMap<String, Long>()
        readableDatabase.rawQuery("SELECT address, vector_row FROM photo", null).use { cursor ->
            while (cursor.moveToNext()) rows[cursor.getString(0)] = cursor.getLong(1)
        }
        return rows
    }

    /** The first row that no photo uses yet. */
    fun nextFreeRow(): Long {
        readableDatabase.rawQuery("SELECT COALESCE(MAX(vector_row) + 1, 0) FROM photo", null).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    /** Writes the rows in one transaction: all of them, or none. */
    fun upsertAll(records: List<Pair<PhotoRecord, Long>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((record, row) in records) {
                val values = ContentValues().apply {
                    put("address", record.address)
                    put("name", record.name)
                    put("path", record.path)
                    put("folder", record.folder)
                    put("mime", record.mime)
                    put("size", record.size)
                    put("modified", record.modified)
                    put("taken", record.taken)
                    put("taken_ms", record.takenMs)
                    put("analysis_version", record.analysisVersion)
                    put("faces", record.faces)
                    put("portrait", if (record.portrait) 1 else 0)
                    put("indoor", record.indoor?.let { if (it) 1 else 0 })
                    put("text", record.text)
                    put("vector_row", row)
                }
                db.insertWithOnConflict("photo", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

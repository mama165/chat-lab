package com.example.chatlab

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The vectors of the photos, in one binary file: each photo takes [dimension] floats of 4 bytes, in
 * little-endian order, so the block of row `n` starts at byte `n * blockBytes`. No separator, no header.
 */
class VectorFile(private val file: File, val dimension: Int = 768) {
    private val blockBytes = dimension * Float.SIZE_BYTES

    /** Writes the vectors at their rows, then forces them to the disk once. */
    fun writeAll(vectors: List<Pair<Long, FloatArray>>) {
        if (vectors.isEmpty()) return
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            for ((row, vector) in vectors) {
                require(vector.size == dimension) { "vector of ${vector.size} values, expected $dimension" }
                val bytes = ByteBuffer.allocate(blockBytes).order(ByteOrder.LITTLE_ENDIAN)
                vector.forEach { bytes.putFloat(it) }
                raf.seek(row * blockBytes)
                raf.write(bytes.array())
            }
            raf.fd.sync()
        }
    }

    /** Reads [count] consecutive vectors from [firstRow], as one flat array of count * dimension floats. */
    fun readBlock(firstRow: Long, count: Int): FloatArray {
        val bytes = ByteArray(count * blockBytes)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(firstRow * blockBytes)
            raf.readFully(bytes)
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count * dimension) { buffer.getFloat() }
    }

    /**
     * The vector of a row, or null if the file is missing or does not reach that row (after a wipe, the
     * table and the file are not always gone together). Reading it must not stop the whole library.
     */
    fun readOrNull(row: Long): FloatArray? {
        if (!file.exists() || (row + 1) * blockBytes > file.length()) return null
        return read(row)
    }

    /** Reads the vector of a row. Only that block is read, not the whole file. */
    fun read(row: Long): FloatArray {
        val bytes = ByteArray(blockBytes)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(row * blockBytes)
            raf.readFully(bytes)
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimension) { buffer.getFloat() }
    }
}

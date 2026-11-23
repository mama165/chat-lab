package com.example.chatlab

import java.io.File

/**
 * The number of photos under each folder, computed in a single pass over the photos found.
 * A photo counts for each of its parent folders, up to the root of the storage. A folder
 * without photos does not appear in the result: its count is zero.
 */
object FolderCounts {
    fun countByFolder(photoPaths: Collection<String>, root: String): Map<String, Int> {
        val counts = HashMap<String, Int>()
        val top = File(root).absolutePath
        for (path in photoPaths) {
            var parent = File(path).parentFile
            while (parent != null && parent.absolutePath.startsWith(top) && parent.absolutePath != File(top).parent) {
                counts[parent.absolutePath] = (counts[parent.absolutePath] ?: 0) + 1
                if (parent.absolutePath == top) break
                parent = parent.parentFile
            }
        }
        return counts
    }
}

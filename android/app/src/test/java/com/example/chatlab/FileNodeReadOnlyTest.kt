package com.example.chatlab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Photos are never modified: the analysis only sees a file through [FileNode],
 * which can only read it. This test fails if a write method is added to it.
 */
class FileNodeReadOnlyTest {
    private val writing = listOf("write", "delete", "rename", "set", "create", "move", "truncate", "append", "remove", "update")

    @Test
    fun aFileCanOnlyBeRead() {
        val methods = FileNode::class.java.methods.map { it.name }
        assertTrue(methods.contains("open"))
        for (name in methods) {
            assertFalse("méthode d'écriture interdite : $name", writing.any { name.startsWith(it) })
        }
    }

    @Test
    fun aFolderCanOnlyBeListed() {
        val methods = DirNode::class.java.methods.map { it.name }
        for (name in methods) {
            assertFalse("méthode d'écriture interdite : $name", writing.any { name.startsWith(it) })
        }
    }
}

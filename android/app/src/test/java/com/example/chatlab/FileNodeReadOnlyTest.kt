package com.example.chatlab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les photos ne sont jamais modifiées : l'analyse ne voit un fichier qu'à travers [FileNode],
 * qui ne sait que le lire. Ce test échoue si une méthode d'écriture y est ajoutée.
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

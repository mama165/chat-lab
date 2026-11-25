package com.example.chatlab

import java.io.File
import java.io.InputStream

/** A file seen while listing a folder, independently of the file system. */
interface FileNode {
    val name: String
    val length: Long
    val lastModified: Long

    /** Stable identifier (content:// address or path), key of the library. */
    val id: String

    /** Opens the file for reading, or returns null / throws an error if it is inaccessible. */
    fun open(): InputStream?
}

/** A folder: its list is null when the system refuses to read it. */
interface DirNode {
    val name: String
    fun children(): List<Any>?
}

/** A file kept for the analysis, with its detected type. */
data class Candidate(val file: FileNode, val mime: String, val folder: String)

/** A file dropped, with the recognised type (null: unknown). */
data class IgnoredFile(val name: String, val mime: String?, val folder: String)

/** A file we could not read from the listing. */
data class UnreadableFile(val name: String, val reason: FailureReason, val folder: String)

/** The result of listing a source folder. */
data class ScanResult(
    val folder: String,
    val candidates: List<Candidate>,
    val ignored: List<IgnoredFile>,
    val unreadable: List<UnreadableFile>,
    /** Relative paths of the subfolders whose access is refused. */
    val refused: List<String>,
    /** The folder itself is refused: nothing could be listed. */
    val rootRefused: Boolean,
) {
    val isEmpty: Boolean get() = candidates.isEmpty() && ignored.isEmpty() && unreadable.isEmpty() && refused.isEmpty()
}

/**
 * Walks a folder and its subfolders, and decides for each file from its first
 * bytes. Works on any file system: Android (DocumentFile) in production,
 * java.io.File in the tests.
 */
object FolderScanner {
    /**
     * [reusable] gives the type already known for an unchanged file: it is then not read again (the
     * listing stays fast when the library is already full). [examined] receives the number of
     * files seen so far, to show the progress.
     */
    fun scan(
        root: DirNode,
        reusable: (FileNode) -> String? = { null },
        examined: (Int) -> Unit = {},
    ): ScanResult {
        var count = 0
        val candidates = ArrayList<Candidate>()
        val ignored = ArrayList<IgnoredFile>()
        val unreadable = ArrayList<UnreadableFile>()
        val refused = ArrayList<String>()
        val top = root.children()
            ?: return ScanResult(root.name, emptyList(), emptyList(), emptyList(), emptyList(), rootRefused = true)

        fun walk(children: List<Any>, path: String) {
            for (child in children) {
                when (child) {
                    is DirNode -> {
                        val inner = try {
                            child.children()
                        } catch (e: SecurityException) {
                            null
                        }
                        val label = if (path.isEmpty()) child.name else "$path/${child.name}"
                        if (inner == null) refused += label else walk(inner, label)
                    }
                    is FileNode -> {
                        count++
                        examined(count)
                        val known = reusable(child)
                        if (known != null) {
                            candidates += Candidate(child, known, root.name)
                        } else {
                            when (val decision = decide(child)) {
                                is ScanDecision.Analyze -> candidates += Candidate(child, decision.mime, root.name)
                                is ScanDecision.Ignore -> ignored += IgnoredFile(child.name, decision.mime, root.name)
                                is ScanDecision.Unreadable -> unreadable += UnreadableFile(child.name, decision.reason, root.name)
                            }
                        }
                    }
                }
            }
        }
        walk(top, "")
        return ScanResult(root.name, candidates, ignored, unreadable, refused, rootRefused = false)
    }

    /** Reads the first bytes of the file and applies the rules. */
    fun decide(file: FileNode): ScanDecision {
        val head = try {
            file.open()?.use { input ->
                val buffer = ByteArray(FileSniffer.HEAD_SIZE)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                buffer.copyOf(read)
            }
        } catch (e: Exception) {
            null
        }
        return ScanRules.classify(file.length, head)
    }
}

/** A real file (java.io.File) seen as a [FileNode]. Used by the tests and the tools. */
class JavaFileNode(private val file: File) : FileNode {
    override val name: String get() = file.name
    override val length: Long get() = file.length()
    override val lastModified: Long get() = file.lastModified()
    override val id: String get() = file.path
    override fun open(): InputStream? = file.inputStream()
}

/** A real folder (java.io.File) seen as a [DirNode]; list null if the folder is not readable. */
class JavaDirNode(private val dir: File) : DirNode {
    override val name: String get() = dir.name
    override fun children(): List<Any>? {
        val items = dir.listFiles() ?: return null
        return items.sortedBy { it.name }.map { if (it.isDirectory) JavaDirNode(it) else JavaFileNode(it) }
    }
}

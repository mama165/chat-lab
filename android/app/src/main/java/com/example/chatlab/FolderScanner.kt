package com.example.chatlab

import java.io.File
import java.io.InputStream

/** Un fichier vu pendant le listage d'un dossier, indépendamment du système de fichiers. */
interface FileNode {
    val name: String
    val length: Long
    val lastModified: Long

    /** Identifiant stable (adresse content:// ou chemin), clé du carnet. */
    val id: String

    /** Ouvre le fichier en lecture, ou renvoie null / lève une erreur s'il est inaccessible. */
    fun open(): InputStream?
}

/** Un dossier : sa liste est null quand le système refuse de le lire. */
interface DirNode {
    val name: String
    fun children(): List<Any>?
}

/** Un fichier gardé pour l'analyse, avec son type détecté. */
data class Candidate(val file: FileNode, val mime: String, val folder: String)

/** Un fichier écarté, avec le type reconnu (null : inconnu). */
data class IgnoredFile(val name: String, val mime: String?, val folder: String)

/** Un fichier qu'on n'a pas pu lire dès le listage. */
data class UnreadableFile(val name: String, val reason: FailureReason, val folder: String)

/** Le résultat du listage d'un dossier source. */
data class ScanResult(
    val folder: String,
    val candidates: List<Candidate>,
    val ignored: List<IgnoredFile>,
    val unreadable: List<UnreadableFile>,
    /** Chemins relatifs des sous-dossiers dont l'accès est refusé. */
    val refused: List<String>,
    /** Le dossier lui-même est refusé : rien n'a pu être listé. */
    val rootRefused: Boolean,
) {
    val isEmpty: Boolean get() = candidates.isEmpty() && ignored.isEmpty() && unreadable.isEmpty() && refused.isEmpty()
}

/**
 * Parcourt un dossier et ses sous-dossiers, et décide de chaque fichier d'après ses premiers
 * octets. Fonctionne sur n'importe quel système de fichiers : Android (DocumentFile) en
 * production, java.io.File dans les tests.
 */
object FolderScanner {
    /**
     * [reusable] donne le type déjà connu d'un fichier inchangé : il n'est alors pas relu (le
     * listage reste rapide quand le carnet est déjà plein). [examined] reçoit le nombre de
     * fichiers vus jusqu'ici, pour afficher l'avancement.
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

    /** Lit les premiers octets du fichier et applique les règles. */
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

/** Un fichier réel (java.io.File) vu comme un [FileNode]. Sert aux tests et aux outils. */
class JavaFileNode(private val file: File) : FileNode {
    override val name: String get() = file.name
    override val length: Long get() = file.length()
    override val lastModified: Long get() = file.lastModified()
    override val id: String get() = file.path
    override fun open(): InputStream? = file.inputStream()
}

/** Un dossier réel (java.io.File) vu comme un [DirNode] ; liste null si le dossier n'est pas lisible. */
class JavaDirNode(private val dir: File) : DirNode {
    override val name: String get() = dir.name
    override fun children(): List<Any>? {
        val items = dir.listFiles() ?: return null
        return items.sortedBy { it.name }.map { if (it.isDirectory) JavaDirNode(it) else JavaFileNode(it) }
    }
}

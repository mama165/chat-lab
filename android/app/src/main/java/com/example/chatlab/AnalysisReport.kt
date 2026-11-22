package com.example.chatlab

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** Un fichier non lu et la raison. */
data class FileFailure(val name: String, val reason: FailureReason)

/** Le bilan d'un dossier à la fin de l'analyse. */
data class FolderReport(
    val name: String,
    val uri: String,
    /** Photos de ce dossier dans le carnet : analysées maintenant ou déjà connues. */
    val analyzed: Int,
    val failures: List<FileFailure>,
    /** Fichiers écartés parce que ce ne sont pas des images qu'on sait lire. */
    val ignored: Int,
    /** Sous-dossiers refusés par Android. */
    val refused: List<String>,
    /** Le dossier lui-même est refusé. */
    val rootRefused: Boolean,
) {
    val unread: Int get() = failures.size
    val hasProblem: Boolean get() = failures.isNotEmpty() || refused.isNotEmpty() || rootRefused
}

/** Le bilan de toute l'analyse, gardé pour l'écran Photos. */
data class RunReport(
    val finishedAt: Long,
    val durationMs: Long,
    /** Vrai si l'analyse a été arrêtée avant la fin. */
    val stopped: Boolean,
    val folders: List<FolderReport>,
) {
    val analyzed: Int get() = folders.sumOf { it.analyzed }
    val unread: Int get() = folders.sumOf { it.unread }
    val ignored: Int get() = folders.sumOf { it.ignored }
    val refusedFolders: Int get() = folders.sumOf { it.refused.size + if (it.rootRefused) 1 else 0 }
    val hasProblem: Boolean get() = folders.any { it.hasProblem }

    fun toJson(): String {
        val array = JSONArray()
        for (folder in folders) {
            array.put(
                JSONObject()
                    .put("name", folder.name)
                    .put("uri", folder.uri)
                    .put("analyzed", folder.analyzed)
                    .put("ignored", folder.ignored)
                    .put("root_refused", folder.rootRefused)
                    .put("refused", JSONArray(folder.refused))
                    .put(
                        "failures",
                        JSONArray().also { list ->
                            folder.failures.forEach { list.put(JSONObject().put("name", it.name).put("reason", it.reason.name)) }
                        },
                    ),
            )
        }
        return JSONObject()
            .put("finished_at", finishedAt)
            .put("duration_ms", durationMs)
            .put("stopped", stopped)
            .put("folders", array)
            .toString()
    }

    /** Le compte rendu en texte, à copier ou partager. */
    fun toText(): String = buildString {
        val state = if (stopped) "arrêtée avant la fin" else "terminée"
        appendLine("Analyse $state en ${clock(durationMs)} : $analyzed photo(s) analysée(s), $unread fichier(s) non lu(s).")
        for (folder in folders) {
            appendLine()
            appendLine("${folder.name} : ${folder.analyzed} analysée(s)" + if (folder.ignored > 0) ", ${folder.ignored} fichier(s) ignoré(s) (pas des images lisibles)" else "")
            if (folder.rootRefused) appendLine("  dossier refusé par Android")
            folder.refused.forEach { appendLine("  sous-dossier refusé par Android : $it") }
            folder.failures.forEach { appendLine("  ${it.name} (${it.reason.label})") }
        }
    }

    companion object {
        fun fromJson(text: String?): RunReport? {
            if (text.isNullOrBlank()) return null
            return try {
                val root = JSONObject(text)
                val folders = root.getJSONArray("folders")
                RunReport(
                    finishedAt = root.getLong("finished_at"),
                    durationMs = root.optLong("duration_ms", 0L),
                    stopped = root.optBoolean("stopped", false),
                    folders = (0 until folders.length()).map { index ->
                        val item = folders.getJSONObject(index)
                        val failures = item.optJSONArray("failures") ?: JSONArray()
                        val refused = item.optJSONArray("refused") ?: JSONArray()
                        FolderReport(
                            name = item.getString("name"),
                            uri = item.optString("uri"),
                            analyzed = item.optInt("analyzed"),
                            failures = (0 until failures.length()).map { i ->
                                val f = failures.getJSONObject(i)
                                FileFailure(
                                    f.getString("name"),
                                    FailureReason.entries.firstOrNull { it.name == f.optString("reason") } ?: FailureReason.ANALYSIS,
                                )
                            },
                            ignored = item.optInt("ignored"),
                            refused = (0 until refused.length()).map { refused.getString(it) },
                            rootRefused = item.optBoolean("root_refused"),
                        )
                    },
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Construit le bilan pendant l'analyse. Sûr à appeler depuis plusieurs travailleurs. */
class ReportBuilder(private val sources: List<SourceFolder>) {
    private val analyzed = Array(sources.size) { AtomicInteger() }
    private val failures = Array(sources.size) { java.util.Collections.synchronizedList(ArrayList<FileFailure>()) }
    private val ignored = IntArray(sources.size)
    private val refused = Array(sources.size) { ArrayList<String>() }
    private val rootRefused = BooleanArray(sources.size)

    /** Enregistre ce que le listage d'un dossier a trouvé : écartés, refusés, fichiers illisibles dès le départ. */
    @Synchronized
    fun scanned(index: Int, scan: ScanResult) {
        ignored[index] = scan.ignored.size
        refused[index].clear()
        refused[index].addAll(scan.refused)
        rootRefused[index] = scan.rootRefused
        scan.unreadable.forEach { failures[index].add(FileFailure(it.name, it.reason)) }
    }

    fun analyzed(index: Int) {
        analyzed[index].incrementAndGet()
    }

    fun failed(index: Int, name: String, reason: FailureReason) {
        failures[index].add(FileFailure(name, reason))
    }

    @Synchronized
    fun build(finishedAt: Long, durationMs: Long, stopped: Boolean): RunReport = RunReport(
        finishedAt = finishedAt,
        durationMs = durationMs,
        stopped = stopped,
        folders = sources.indices.map { i ->
            FolderReport(
                name = sources[i].name,
                uri = sources[i].uri,
                analyzed = analyzed[i].get(),
                failures = synchronized(failures[i]) { failures[i].toList() },
                ignored = ignored[i],
                refused = refused[i].toList(),
                rootRefused = rootRefused[i],
            )
        },
    )
}

/** Ce que l'analyse fait d'un fichier déjà dans le carnet. */
enum class Carry {
    /** Inchangé et complet : repris tel quel. */
    REUSE,

    /** Inchangé mais écrit avant que le dossier et le type existent : repris, avec ces champs complétés. */
    BACKFILL,

    /** Nouveau ou modifié : à analyser. */
    PROCESS,
}

/** Les fichiers à analyser, rangés par dossier, et ceux qu'on reprend sans calcul. */
data class AnalysisPlan(
    val folders: List<SourceFolder>,
    val scans: List<ScanResult>,
    val toProcess: List<PlanItem>,
    val carried: List<PlanItem>,
) {
    /** Nombre de fichiers à analyser dans le dossier [index] : le total de sa progression. */
    fun processCount(index: Int): Int = toProcess.count { it.folderIndex == index }

    companion object {
        fun build(folders: List<SourceFolder>, scans: List<ScanResult>, carry: (FileNode) -> Carry): AnalysisPlan {
            val process = ArrayList<PlanItem>()
            val carried = ArrayList<PlanItem>()
            scans.forEachIndexed { index, scan ->
                for (candidate in scan.candidates) {
                    val item = PlanItem(index, candidate)
                    if (carry(candidate.file) == Carry.PROCESS) process += item else carried += item
                }
            }
            return AnalysisPlan(folders, scans, process, carried)
        }
    }
}

data class PlanItem(val folderIndex: Int, val candidate: Candidate)

/** Garde dans le carnet les photos des dossiers qu'on a retirés de la liste. */
object LibraryMerge {
    /**
     * Les entrées de l'ancien carnet à garder : celles dont le dossier est connu et n'est plus dans
     * la liste actuelle. Les autres sont refaites par l'analyse (ou disparues avec leur fichier).
     */
    fun keepFromRemovedFolders(old: JSONArray?, currentFolders: Set<String>): List<JSONObject> {
        if (old == null) return emptyList()
        return (0 until old.length()).mapNotNull { old.optJSONObject(it) }
            .filter { it.optString("folder").isNotBlank() && it.optString("folder") !in currentFolders }
    }
}

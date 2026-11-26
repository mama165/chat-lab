package com.example.chatlab

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** An unread file and the reason. */
data class FileFailure(val name: String, val reason: FailureReason)

/** The report of a folder at the end of the analysis. */
data class FolderReport(
    val name: String,
    val uri: String,
    /** Photos of this folder in the library: analysed now or already known. */
    val analyzed: Int,
    val failures: List<FileFailure>,
    /** Files dropped because they are not images we can read. */
    val ignored: Int,
    /** Subfolders refused by Android. */
    val refused: List<String>,
    /** The folder itself is refused. */
    val rootRefused: Boolean,
) {
    val unread: Int get() = failures.size
    val hasProblem: Boolean get() = failures.isNotEmpty() || refused.isNotEmpty() || rootRefused
}

/** The report of the whole analysis, kept for the Photos screen. */
data class RunReport(
    val finishedAt: Long,
    val durationMs: Long,
    /** True if the analysis was stopped before the end. */
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

    /** The report as text, to copy or share. */
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

/** Builds the report during the analysis. Safe to call from several workers. */
class ReportBuilder(private val sources: List<SourceFolder>) {
    private val analyzed = Array(sources.size) { AtomicInteger() }
    private val failures = Array(sources.size) { java.util.Collections.synchronizedList(ArrayList<FileFailure>()) }
    private val ignored = IntArray(sources.size)
    private val refused = Array(sources.size) { ArrayList<String>() }
    private val rootRefused = BooleanArray(sources.size)

    /** Records what the listing of a folder found: dropped, refused, and files unreadable from the start. */
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

/** What the analysis does with a file already in the library. */
enum class Carry {
    /** Unchanged and complete: kept as it is. */
    REUSE,

    /** Unchanged but written before the folder and the type existed: kept, with these fields completed. */
    BACKFILL,

    /** New or changed: to analyse. */
    PROCESS,
}

/** The files to analyse, most recent first, and those kept without computing. */
data class AnalysisPlan(
    val folders: List<SourceFolder>,
    val scans: List<ScanResult>,
    val toProcess: List<PlanItem>,
    val carried: List<PlanItem>,
) {
    /** Number of files to analyse in the folder [index]: the total of its progress. */
    fun processCount(index: Int): Int = toProcess.count { it.folderIndex == index }

    companion object {
        fun build(folders: List<SourceFolder>, scans: List<ScanResult>, carry: (FileNode) -> Carry): AnalysisPlan {
            val process = ArrayList<PlanItem>()
            val carried = ArrayList<PlanItem>()
            // Two selected folders can share a photo (a folder and its subfolder): it is planned once.
            val all = scans.flatMapIndexed { index, scan -> scan.candidates.map { PlanItem(index, it) } }
            for (item in distinctById(all, { it.candidate.file.id })) {
                if (carry(item.candidate.file) == Carry.PROCESS) process += item else carried += item
            }
            // Most recent first: a photo just taken is analysed before the older ones. The file date is the
            // one known before reading the photo; for a camera photo it is the time it was taken.
            val recentFirst = process.sortedByDescending { it.candidate.file.lastModified }
            return AnalysisPlan(folders, scans, recentFirst, carried)
        }
    }
}

data class PlanItem(val folderIndex: Int, val candidate: Candidate)

/** Keeps in the library the photos of the folders removed from the list. */

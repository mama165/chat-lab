package com.example.chatlab

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

object AnalysisProgress {
    private val value = MutableStateFlow(Progress())
    val state: StateFlow<Progress> = value.asStateFlow()

    internal fun set(progress: Progress) {
        value.value = progress
    }

    /** Modifie l'état en place, sans perdre les champs de pause. */
    @Synchronized
    internal fun update(change: (Progress) -> Progress) {
        value.value = change(value.value)
    }

    /** Oublie le dernier message et le dernier compte rendu (Tout effacer). */
    fun clearReport() = update { if (it.running) it else Progress() }
}

/**
 * Analyse les dossiers choisis (sous-dossiers compris) dans un service de premier plan, pour que
 * le traitement continue quand on quitte l'application.
 *
 * Chaque fichier est reconnu par ses premiers octets (comme le scanner Go), jamais par son nom.
 * Les images sont traitées par WORKERS travailleurs en parallèle. Une photo inchangée (même
 * adresse, taille et date) est reprise du carnet sans calcul. Le carnet est enregistré dans le
 * dossier chatlab, et les photos restent à leur place d'origine : rien n'est copié de façon
 * permanente. À la fin, un bilan par dossier dit ce qui a été analysé et ce qui n'a pas pu l'être.
 */
class AnalysisService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_PAUSE || action == ACTION_RESUME || action == ACTION_STOP) {
            when (action) {
                ACTION_PAUSE -> pause()
                ACTION_RESUME -> resume()
                else -> stop()
            }
            // Un bouton d'une ancienne notification, sans analyse en cours : rien à garder en vie.
            if (current == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val chatlab = intent?.getStringExtra(EXTRA_CHATLAB)
        if (chatlab == null || AnalysisProgress.state.value.running) {
            stopSelf()
            return START_NOT_STICKY
        }
        val sources = Sources.read(this)
        if (sources.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        Notifications.createChannel(this)
        val started = System.currentTimeMillis()
        val control = AnalysisControl { state -> onControl(state) }
        current = control
        AnalysisProgress.set(
            Progress(
                running = true,
                startedAt = started,
                message = "Préparation…",
                folders = sources.map { FolderProgress(it.name, 0, 0) },
            ),
        )
        startForeground(Notifications.PROGRESS_ID, Notifications.progress(this, AnalysisProgress.state.value, true))

        scope.launch {
            try {
                run(chatlab, sources, started, control)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val message = "Analyse interrompue : ${e.message ?: e.javaClass.simpleName}"
                val state = AnalysisProgress.state.value
                finish(state.copy(running = false, message = message), message, null)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun run(chatlab: String, sources: List<SourceFolder>, started: Long, control: AnalysisControl) {
        val zone = TimeZone.getDefault()
        val oldLibrary = LibraryStore.read(this)
        val known = readKnown(oldLibrary)
        val report = ReportBuilder(sources)

        // 1. Lister chaque dossier. Un fichier déjà connu et inchangé n'est pas relu.
        val scans = sources.mapIndexed { index, folder ->
            control.checkpoint()
            val scan = FolderScanner.scan(SafDir.root(this, folder), reusable = { file -> reusableMime(known, file) })
            report.scanned(index, scan)
            scan
        }
        val plan = AnalysisPlan.build(sources, scans) { file -> carry(known, file) }

        // 2. Ce qui est déjà au carnet est repris tel quel ; le reste est à analyser.
        val carried = plan.carried.map { item ->
            report.analyzed(item.folderIndex)
            carriedResult(known.getValue(item.candidate.file.id), item, zone)
        }
        val total = plan.toProcess.size
        AnalysisProgress.update {
            it.copy(
                total = total,
                message = if (total == 0) "Rien de nouveau" else "",
                folders = sources.mapIndexed { i, f -> FolderProgress(f.name, 0, plan.processCount(i)) },
            )
        }
        notifyProgress()

        val results = arrayOfNulls<ImageResult>(total)
        val next = AtomicInteger(0)
        val errors = AtomicInteger(0)
        val saveLock = Any()
        var lastSave = System.currentTimeMillis()
        val keep = LibraryMerge.keepFromRemovedFolders(parseArray(oldLibrary), sources.map { it.name }.toSet())

        // Un analyseur par travailleur : chacun charge ses modèles et travaille seul. Sans rien à
        // analyser, on ne charge aucun modèle.
        val workers = if (total == 0) 0 else WORKERS
        val analyzers = List(workers) { Analyzer(this@AnalysisService, android.net.Uri.parse(chatlab)) }
        try {
            coroutineScope {
                analyzers.map { analyzer ->
                    async(Dispatchers.Default) {
                        while (true) {
                            // Point d'arrêt avant chaque photo : en pause on attend ici, à l'arrêt on sort.
                            try {
                                control.checkpoint()
                            } catch (stopped: AnalysisStopped) {
                                break
                            }
                            val index = next.getAndIncrement()
                            if (index >= total) break
                            val item = plan.toProcess[index]
                            val outcome = try {
                                analyzeOne(this@AnalysisService, analyzer, item, sources[item.folderIndex].name, zone, control::checkpoint)
                            } catch (stopped: AnalysisStopped) {
                                // La photo en cours est abandonnée ; elle sera reprise à la prochaine analyse.
                                break
                            }
                            when (outcome) {
                                is Outcome.Done -> {
                                    results[index] = outcome.result
                                    report.analyzed(item.folderIndex)
                                }
                                is Outcome.Failed -> {
                                    errors.incrementAndGet()
                                    report.failed(item.folderIndex, outcome.name, outcome.reason)
                                }
                            }
                            publish(item.folderIndex, item.candidate.file.name, errors.get())
                            // Carnet enregistré régulièrement : une fermeture ne fait perdre que
                            // les dernières images, et la relance reprend là où on s'était arrêté.
                            synchronized(saveLock) {
                                if (System.currentTimeMillis() - lastSave >= SAVE_INTERVAL_MS) {
                                    lastSave = System.currentTimeMillis()
                                    LibraryWriter.write(this@AnalysisService, carried + results.filterNotNull(), keep)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            val elapsed = AnalysisProgress.state.value.activeElapsed(System.currentTimeMillis())
            val stopped = control.state == RunState.STOPPING
            val finalResults = carried + results.filterNotNull()
            LibraryWriter.write(this@AnalysisService, finalResults, keep)
            val runReport = report.build(System.currentTimeMillis(), elapsed, stopped)
            ReportStore.saveRun(this@AnalysisService, runReport)
            val text = when {
                stopped -> "Analyse arrêtée : ${runReport.analyzed} photos dans le carnet, ce qui est fait est gardé."
                runReport.unread > 0 -> "Analyse terminée · ${runReport.analyzed} photos analysées · ${runReport.unread} fichiers non lus"
                else -> "Analyse terminée · ${runReport.analyzed} photos analysées"
            }
            finish(
                Progress(false, total, total, "", started, errors.get(), report = runReport.toText(), folders = AnalysisProgress.state.value.folders),
                text,
                runReport,
            )
        } finally {
            analyzers.forEach { it.close() }
        }
    }

    @Synchronized
    private fun publish(folderIndex: Int, name: String, errors: Int) {
        AnalysisProgress.update { it.advancing(folderIndex, name).copy(running = true, errors = errors) }
        val now = System.currentTimeMillis()
        val state = AnalysisProgress.state.value
        if (now - lastNotified > 1000 || state.done == state.total) {
            lastNotified = now
            notifyProgress()
        }
    }

    private fun notifyProgress() {
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.PROGRESS_ID, Notifications.progress(this, AnalysisProgress.state.value, current != null))
    }

    /** Pause et reprise se voient tout de suite à l'écran et dans la notification. */
    private fun onControl(state: RunState) {
        val now = System.currentTimeMillis()
        AnalysisProgress.update { progress ->
            when (state) {
                RunState.PAUSED -> progress.pausing(now)
                RunState.RUNNING -> progress.resuming(now)
                RunState.STOPPING -> progress.resuming(now).copy(message = "Arrêt en cours…")
            }
        }
        notifyProgress()
    }

    private fun finish(progress: Progress, text: String, report: RunReport?) {
        current = null
        AnalysisProgress.set(progress.copy(message = text, paused = false, running = false))
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.DONE_ID, Notifications.done(this, text, report))
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CHATLAB = "chatlab"
        const val ACTION_PAUSE = "com.example.chatlab.PAUSE"
        const val ACTION_RESUME = "com.example.chatlab.RESUME"
        const val ACTION_STOP = "com.example.chatlab.STOP"

        /** La commande de l'analyse en cours, s'il y en a une. */
        @Volatile
        internal var current: AnalysisControl? = null

        /** Fige l'analyse en cours : plus aucun calcul jusqu'à [resume]. */
        fun pause() = current?.pause() ?: false

        /** Reprend une analyse en pause, exactement là où elle s'était figée. */
        fun resume() = current?.resume() ?: false

        /** Termine l'analyse en gardant tout ce qui est fait. */
        fun stop() = current?.stop() ?: false

        /** Nombre d'images analysées en même temps. Chaque travailleur charge ses modèles. */
        const val WORKERS = 2

        /** Le carnet est enregistré au plus toutes les deux minutes pendant l'analyse. */
        const val SAVE_INTERVAL_MS = 120_000L

        fun start(context: Context, chatlab: String) {
            val intent = Intent(context, AnalysisService::class.java).putExtra(EXTRA_CHATLAB, chatlab)
            context.startForegroundService(intent)
        }
    }
}

/** Ce que devient un fichier après son analyse. */
private sealed interface Outcome {
    data class Done(val result: ImageResult) : Outcome
    data class Failed(val name: String, val reason: FailureReason) : Outcome
}

/**
 * Analyse une image pour un travailleur : copie provisoire, analyse, puis la copie est supprimée.
 * Une erreur est rangée dans l'une des raisons affichables ; l'arrêt demandé est relancé.
 */
private fun analyzeOne(
    context: Context,
    analyzer: Analyzer,
    item: PlanItem,
    folderName: String,
    zone: TimeZone,
    checkpoint: () -> Unit,
): Outcome {
    val file = item.candidate.file
    val name = file.name
    return try {
        val work = File(context.cacheDir, "analyse/${UUID.randomUUID()}").apply { mkdirs() }
        // Le dossier provisoire porte le nom d'origine, pour que le carnet garde le bon nom.
        val temp = File(work, name.replace('/', '_'))
        try {
            val input = file.open() ?: throw ImageFailure(FailureReason.INACCESSIBLE, "ouverture impossible")
            input.use { source -> temp.outputStream().use { source.copyTo(it) } }
            val started = System.nanoTime()
            val analyzed = analyzer.analyzeFile(temp, checkpoint)
            val millis = (System.nanoTime() - started) / 1_000_000
            val taken = Dates.exifToTaken(exifDate(temp)) ?: Dates.formatTaken(file.lastModified, zone)
            Outcome.Done(
                analyzed.copy(
                    source = file.id,
                    modified = file.lastModified,
                    size = if (file.length >= 0) file.length else temp.length(),
                    millis = millis,
                    taken = taken,
                    takenMs = Dates.parseTakenMillis(taken, zone) ?: file.lastModified,
                    folder = folderName,
                    mime = item.candidate.mime,
                ),
            )
        } finally {
            work.deleteRecursively()
        }
    } catch (stopped: AnalysisStopped) {
        throw stopped
    } catch (e: Throwable) {
        Outcome.Failed(name, Failures.reasonOf(e))
    }
}

private fun exifDate(file: File): String? = try {
    ExifInterface(file.path).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
} catch (e: Exception) {
    null
}

private fun parseArray(text: String?): JSONArray? = try {
    if (text == null) null else JSONArray(text)
} catch (e: Exception) {
    null
}

/** Les entrées du carnet existant qui ont une source, indexées par cette source. */
private fun readKnown(text: String?): Map<String, JSONObject> {
    val array = parseArray(text) ?: return emptyMap()
    return (0 until array.length())
        .mapNotNull { array.optJSONObject(it) }
        .filter { it.has("source") && it.optInt("analysis_version") == ANALYSIS_VERSION }
        .associateBy { it.getString("source") }
}

/** Le carnet connaît ce fichier tel qu'il est : même adresse, même taille, même date. */
private fun unchanged(known: Map<String, JSONObject>, file: FileNode): JSONObject? {
    val item = known[file.id] ?: return null
    if (file.length < 0) return null
    return item.takeIf { it.optLong("size") == file.length && it.optLong("modified") == file.lastModified }
}

/** Le type déjà connu d'un fichier inchangé : il n'est alors pas relu pendant le listage. */
internal fun reusableMime(known: Map<String, JSONObject>, file: FileNode): String? =
    unchanged(known, file)?.optString("mime")?.takeIf { it.isNotBlank() }

/** Reprendre, compléter ou analyser : ce que l'analyse fait d'un fichier. */
internal fun carry(known: Map<String, JSONObject>, file: FileNode): Carry {
    val item = unchanged(known, file) ?: return Carry.PROCESS
    return if (item.optString("mime").isNotBlank() && item.optString("folder").isNotBlank()) Carry.REUSE else Carry.BACKFILL
}

/** Rebâtit un résultat à partir d'une entrée du carnet, avec le dossier et le type d'aujourd'hui. */
private fun carriedResult(item: JSONObject, planItem: PlanItem, zone: TimeZone): ImageResult {
    val values = item.optJSONArray("vectors")?.optJSONArray(0)?.let { array ->
        FloatArray(array.length()) { array.getDouble(it).toFloat() }
    }
    val taken = item.optString("taken", "")
    val file = planItem.candidate.file
    return ImageResult(
        file = File(file.name),
        indoor = if (item.has("indoor")) item.getBoolean("indoor") else null,
        faces = item.optInt("faces", 0),
        portrait = item.optBoolean("portrait", false),
        vector = values,
        millis = 0,
        text = item.optString("text", ""),
        taken = taken,
        source = item.optString("source", ""),
        modified = item.optLong("modified", 0L),
        size = item.optLong("size", 0L),
        takenMs = item.optLong("taken_ms", 0L).takeIf { it > 0 } ?: Dates.parseTakenMillis(taken, zone) ?: 0L,
        folder = planItem.candidate.folder,
        mime = planItem.candidate.mime,
    )
}

/** Ce que chaque dossier contient, avant toute analyse : pour l'écran Photos. */
data class FolderOverview(
    val status: FolderStatus,
    /** Photos déjà dans le carnet et inchangées. */
    val known: Int,
    /** Photos nouvelles ou modifiées, à analyser. */
    val fresh: Int,
)

/** Lit les dossiers sans rien analyser : combien de photos, lesquelles sont nouvelles, quels refus. */
fun overview(context: Context): List<FolderOverview> {
    val sources = Sources.read(context)
    val known = readKnown(LibraryStore.read(context))
    return sources.map { folder ->
        ReadProgress.start(folder.uri)
        val scan = try {
            FolderScanner.scan(
                SafDir.root(context, folder),
                reusable = { file -> reusableMime(known, file) },
                examined = { count -> ReadProgress.report(folder.uri, count) },
            )
        } catch (e: Exception) {
            return@map FolderOverview(FolderStatus(folder, FolderStatus.State.REFUSED), 0, 0)
        } finally {
            ReadProgress.finish(folder.uri)
        }
        val fresh = scan.candidates.count { carry(known, it.file) == Carry.PROCESS }
        val state = when {
            scan.rootRefused -> FolderStatus.State.REFUSED
            scan.candidates.isEmpty() && scan.refused.isEmpty() -> FolderStatus.State.EMPTY
            else -> FolderStatus.State.READABLE
        }
        FolderOverview(
            FolderStatus(folder, state, scan.candidates.size, scan.ignored.size, scan.refused),
            known = scan.candidates.size - fresh,
            fresh = fresh,
        )
    }
}

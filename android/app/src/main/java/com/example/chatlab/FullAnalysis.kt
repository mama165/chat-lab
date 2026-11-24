package com.example.chatlab

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

object AnalysisProgress {
    private val value = MutableStateFlow(Progress())
    val state: StateFlow<Progress> = value.asStateFlow()

    internal fun set(progress: Progress) {
        value.value = progress
    }

    /** Changes the state in place, without losing the pause fields. */
    @Synchronized
    internal fun update(change: (Progress) -> Progress) {
        value.value = change(value.value)
    }

    /** Forgets the last message and the last report (Tout effacer). */
    fun clearReport() = update { if (it.running) it else Progress() }
}

/**
 * Analyses the chosen folders (subfolders included) in a foreground service, so that
 * the work goes on when the application is left.
 *
 * Each file is recognised by its first bytes (like the Go scanner), never by its name.
 * The images are processed by workers in parallel (see AnalysisLevel). An unchanged photo (same
 * address, size and date) is taken from the library without computing. The library is saved in the
 * chatlab folder, and the photos stay where they are: nothing is copied permanently.
 * At the end, a report per folder says what was analysed and what could not be.
 */
class AnalysisService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recorder: AnalysisRecorder? = null
    /** The time of each photo stage in the run in progress. */
    @Volatile private var stageTimes = StageTimes()
    /** The journal file of the run in progress, named in its analysis file. */
    private var runDiagnostics = ""
    /** The workers allowed at the start of the run, and the memory free then (both kept in the analysis file). */
    private var runStartWorkers = 0
    private var runMemoryAtStartMb = 0L
    private var diagnosticsSampler: Job? = null
    /** The memory guard: lowers the workers when the phone runs short of memory. */
    private var workerGuard: WorkerGuard? = null
    /** Set when Android asked for memory, until the next look of the guard. */
    @Volatile private var trimRequested = false
    private var lastNotified = 0L

    /** The level of the analysis running, read when it starts. */
    private var level = AnalysisLevel.DEFAULT

    /** Loads the models again after a pause, showing it on the screen. */
    private fun reloadModels(slot: AnalyzerSlot<Analyzer>) {
        AnalysisProgress.update { it.loadingStarted() }
        try {
            slot.get()
        } finally {
            AnalysisProgress.update { it.loadingDone() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Diagnostics.onAppStart(this)
        // Android asks the app to free memory: each request is written to the diagnostics.
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) {}

            override fun onLowMemory() {
                Diagnostics.event(this@AnalysisService, "lowMemory", Diagnostics.snapshot(this@AnalysisService))
            }

            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trimRequested = true
                Diagnostics.event(
                    this@AnalysisService,
                    "trimMemory",
                    Diagnostics.snapshot(this@AnalysisService).put("level", level),
                )
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_PAUSE || action == ACTION_RESUME || action == ACTION_STOP) {
            when (action) {
                ACTION_PAUSE -> pause()
                ACTION_RESUME -> resume()
                else -> stop()
            }
            // A button of an old notification, with no analysis running: nothing to keep alive.
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
                levelLabel = LevelStore.read(this).label,
            ),
        )
        startForeground(Notifications.PROGRESS_ID, Notifications.progress(this, AnalysisProgress.state.value, true))

        level = LevelStore.read(this)
        runDiagnostics = Diagnostics.openRun(this, level, sources.size)
        // The memory at the start decides how many workers may run: a worker needs its own models.
        val memory = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        val availAtStart = memory.availMem / (1024L * 1024L)
        // A forced number (test page) replaces the start budget; the guard during the run still applies.
        val forced = WorkerOverride.read(this)
        workerGuard = WorkerGuard(forced ?: MemoryBudget.workersAllowedAtStart(level.workers, availAtStart))
        runStartWorkers = workerGuard?.startLimit ?: 0
        runMemoryAtStartMb = availAtStart
        Diagnostics.event(
            this,
            "workersAtStart",
            JSONObject()
                .put("levelWorkers", level.workers)
                .put("allowed", workerGuard?.startLimit ?: 0)
                .put("availMb", availAtStart),
        )
        recorder = AnalysisRecorder(this).also {
            it.workers = workerGuard?.limit ?: level.workers
            it.start(scope)
        }
        // Every 2 seconds: memory, CPU, thermal state and progress, written at once to the diagnostics.
        diagnosticsSampler = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val state = AnalysisProgress.state.value
                // The memory guard looks at the same moment: it lowers the workers if the memory runs short.
                val memory = ActivityManager.MemoryInfo()
                getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
                val pressure = trimRequested
                trimRequested = false
                val availMb = memory.availMem / (1024L * 1024L)
                workerGuard?.observe(System.currentTimeMillis(), availMb, memory.lowMemory, pressure)?.let { limit ->
                    recorder?.workers = limit
                    Diagnostics.event(
                        this@AnalysisService,
                        "guard",
                        JSONObject()
                            .put("workersAllowed", limit)
                            .put("availMb", availMb)
                            .put("lowMemory", memory.lowMemory)
                            .put("trimRequested", pressure),
                    )
                }
                Diagnostics.event(
                    this@AnalysisService,
                    "sample",
                    Diagnostics.snapshot(this@AnalysisService)
                        .put("done", state.done)
                        .put("total", state.total)
                        .put("paused", state.paused)
                        .put("loadingModels", state.loadingModels),
                )
                delay(2_000)
            }
        }
        scope.launch {
            try {
                run(chatlab, sources, started, control)
            } catch (e: Throwable) {
                recorder?.cancel()
                recorder = null
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

        // 1. List each folder. A file already known and unchanged is not read again.
        val scans = sources.mapIndexed { index, folder ->
            control.checkpoint()
            val scan = FolderScanner.scan(Sources.rootOf(this, folder), reusable = { file -> reusableMime(known, file) })
            report.scanned(index, scan)
            scan
        }
        val plan = AnalysisPlan.build(sources, scans) { file -> carry(known, file) }

        // 2. What is already in the library is kept as it is; the rest is to analyse.
        val carried = plan.carried.map { item ->
            report.analyzed(item.folderIndex)
            carriedResult(requireNotNull(known.find(item.candidate.file)), item, zone)
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
        val photosDone = AtomicInteger(0)
        val saveLock = Any()
        var lastSave = System.currentTimeMillis()
        val keep = LibraryMerge.keepFromRemovedFolders(parseArray(oldLibrary), sources.map { it.name }.toSet())

        // One analyser per worker: each loads its models and works alone. With nothing to
        // analyse, no model is loaded.
        val workers = if (total == 0) 0 else (workerGuard?.limit ?: level.workers)
        val loads = Collections.synchronizedList(mutableListOf<ModelLoad>())
        stageTimes = StageTimes()
        val slots = List(workers) {
            AnalyzerSlot<Analyzer>(
                load = {
                    Analyzer(
                        this@AnalysisService,
                        android.net.Uri.parse(chatlab),
                        onModelDone = { name, millis -> AnalysisProgress.update { it.modelDone(name, millis) } },
                        onStage = { stage, millis -> stageTimes.add(stage, millis) },
                        onNativeMemory = { name, before, after ->
                            Diagnostics.event(
                                this@AnalysisService,
                                "modelMemory",
                                JSONObject()
                                    .put("model", name)
                                    .put("beforeMb", NativeMemory.mb(before))
                                    .put("afterMb", NativeMemory.mb(after))
                                    .put("deltaMb", NativeMemory.deltaMb(before, after)),
                            )
                        },
                    )
                },
                onLoad = { loads.add(it) },
                parts = { it.modelTimings },
            )
        }
        try {
            coroutineScope {
                slots.mapIndexed { workerIndex, slot ->
                    async(Dispatchers.Default) {
                        while (true) {
                            // Stop point before each photo. A pause releases the models here, and
                            // a resume loads them again before the next photo.
                            try {
                                control.boundary(
                                    onPaused = { slot.release() },
                                    onResumed = { reloadModels(slot) },
                                )
                            } catch (stopped: AnalysisStopped) {
                                break
                            }
                            // The guard lowered the workers: the highest ones stop here, and free their models.
                            if (workerIndex >= (workerGuard?.limit ?: Int.MAX_VALUE)) {
                                slot.release()
                                break
                            }
                            val index = next.getAndIncrement()
                            if (index >= total) break
                            val item = plan.toProcess[index]
                            AnalysisProgress.update { it.taking(item.folderIndex, item.candidate.file.name) }
                            val outcome = try {
                                analyzeOne(this@AnalysisService, slot, item, sources[item.folderIndex].name, zone, control::checkpointStep)
                            } catch (stopped: AnalysisStopped) {
                                // The current photo is dropped; it will be taken again in the next analysis.
                                break
                            }
                            when (outcome) {
                                is Outcome.Done -> {
                                    results[index] = outcome.result
                                    report.analyzed(item.folderIndex)
                                    val count = photosDone.incrementAndGet()
                                    if (NativeMemory.isPhotoPoint(count)) {
                                        Diagnostics.event(
                                            this@AnalysisService,
                                            "photoMemory",
                                            JSONObject()
                                                .put("photos", count)
                                                .put("worker", workerIndex)
                                                .put("nativeMb", NativeMemory.mb(android.os.Debug.getNativeHeapAllocatedSize())),
                                        )
                                    }
                                }
                                is Outcome.Failed -> {
                                    errors.incrementAndGet()
                                    report.failed(item.folderIndex, outcome.name, outcome.reason)
                                }
                            }
                            publish(item.folderIndex, item.candidate.file.name, errors.get())
                            // Library saved regularly: closing the app only loses
                            // the last images, and the restart resumes where it stopped.
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
            Diagnostics.event(
                this@AnalysisService,
                "workersEnd",
                JSONObject()
                    .put("levelWorkers", level.workers)
                    .put("workersAllowedAtEnd", workerGuard?.limit ?: 0)
                    .put("reductions", workerGuard?.reductions ?: 0),
            )
            val elapsed = AnalysisProgress.state.value.activeElapsed(System.currentTimeMillis())
            val stopped = control.state == RunState.STOPPING
            val finalResults = carried + results.filterNotNull()
            LibraryWriter.write(this@AnalysisService, finalResults, keep)
            val runReport = report.build(System.currentTimeMillis(), elapsed, stopped)
            ReportStore.saveRun(this@AnalysisService, runReport)
            val samples = recorder?.stop().orEmpty()
            recorder = null
            RunArchive.save(this@AnalysisService, recordOf(started, runReport, samples, stopped, level, loads.toList(), finalResults, stageTimes.totals(), runDiagnostics, runStartWorkers, runMemoryAtStartMb))
            val text = when {
                stopped -> "Analyse arrêtée : ${runReport.analyzed} photos dans le carnet, ce qui est fait est gardé."
                runReport.unread > 0 -> "Analyse terminée · ${runReport.analyzed} photos analysées · ${runReport.unread} fichiers non lus"
                else -> "Analyse terminée · ${runReport.analyzed} photos analysées"
            }
            // The memory guard's work is named at the end of the message, so that it is visible.
            val guard = workerGuard
            val guardNote = when {
                guard == null -> ""
                guard.reductions > 0 -> " · mémoire limitée : ${level.workers} → ${guard.limit} workers"
                WorkerOverride.read(this) != null -> " · nombre forcé pour l'essai : ${guard.startLimit} workers"
                guard.startLimit < level.workers -> " · mémoire limitée au départ : ${level.workers} → ${guard.startLimit} workers"
                else -> ""
            }
            finish(
                Progress(false, total, total, "", started, errors.get(), report = runReport.toText(), folders = AnalysisProgress.state.value.folders),
                text + guardNote,
                runReport,
            )
        } finally {
            slots.forEach { it.release() }
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

    /** Pause and resume show at once on the screen and in the notification. */
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

    /** Le fichier d'une analyse : ce qui s'est passé dans chaque dossier, et les relevés du téléphone. */
    private fun recordOf(
        started: Long,
        report: RunReport,
        samples: List<Sample>,
        stopped: Boolean,
        level: AnalysisLevel,
        loads: List<ModelLoad>,
        results: List<ImageResult>,
        stages: Map<String, Long>,
        diagnosticsRun: String,
        startWorkers: Int,
        memoryAtStartMb: Long,
    ): AnalysisRecord {
        val info = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: Exception) {
            null
        }
        return AnalysisRecord(
            startMs = started,
            endMs = System.currentTimeMillis(),
            level = level.key,
            workers = level.workers,
            stopped = stopped,
            appVersion = "${info?.versionName ?: "?"} (${info?.longVersionCode ?: 0})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.SDK_INT,
            cores = Runtime.getRuntime().availableProcessors(),
            folders = report.folders.map {
                FolderStat(it.name, it.analyzed, it.ignored, it.unread, it.refused.size + if (it.rootRefused) 1 else 0)
            },
            samples = samples,
            types = TypeCounts.of(results.map { it.mime }),
            modelLoads = loads,
            stages = stages,
            diagnosticsRun = diagnosticsRun,
            startWorkers = startWorkers,
            memoryAtStartMb = memoryAtStartMb,
        )
    }

    private fun finish(progress: Progress, text: String, report: RunReport?) {
        current = null
        diagnosticsSampler?.cancel()
        diagnosticsSampler = null
        Diagnostics.event(
            this,
            "runEnd",
            JSONObject()
                .put("done", progress.done)
                .put("total", progress.total)
                .put("errors", progress.errors)
                .put("message", text),
        )
        AnalysisProgress.set(progress.copy(message = text, paused = false, running = false))
        // The notification stays, no longer ongoing, and it is updated in place: there is only one.
        stopForeground(STOP_FOREGROUND_DETACH)
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.PROGRESS_ID, Notifications.done(this, text, report))
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

        /** The control of the running analysis, if there is one. */
        @Volatile
        internal var current: AnalysisControl? = null

        /** Fige l'analyse en cours : plus aucun calcul jusqu'à [resume]. */
        fun pause() = current?.pause() ?: false

        /** Resumes a paused analysis, exactly where it was frozen. */
        fun resume() = current?.resume() ?: false

        /** Ends the analysis, keeping everything done. */
        fun stop() = current?.stop() ?: false

        /** The library is saved at most every two minutes during the analysis. */
        const val SAVE_INTERVAL_MS = 120_000L

        fun start(context: Context, chatlab: String) {
            val intent = Intent(context, AnalysisService::class.java).putExtra(EXTRA_CHATLAB, chatlab)
            context.startForegroundService(intent)
        }
    }
}

/** What happens to a file after its analysis. */
private sealed interface Outcome {
    data class Done(val result: ImageResult) : Outcome
    data class Failed(val name: String, val reason: FailureReason) : Outcome
}

/**
 * Analyses an image for a worker: temporary copy, analysis, then the copy is deleted.
 * An error is filed under one of the displayable reasons; the requested stop is raised again.
 */
private fun analyzeOne(
    context: Context,
    slot: AnalyzerSlot<Analyzer>,
    item: PlanItem,
    folderName: String,
    zone: TimeZone,
    checkpoint: () -> Unit,
): Outcome {
    val file = item.candidate.file
    val name = file.name
    return try {
        val work = File(context.cacheDir, "analyse/${UUID.randomUUID()}").apply { mkdirs() }
        // The temporary folder keeps the original name, so that the library keeps the right name.
        val temp = File(work, name.replace('/', '_'))
        try {
            val input = file.open() ?: throw ImageFailure(FailureReason.INACCESSIBLE, "ouverture impossible")
            input.use { source -> temp.outputStream().use { source.copyTo(it) } }
            val started = System.nanoTime()
            val analyzed = slot.get().analyzeFile(temp, checkpoint)
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

/** The entries of the existing library that have a source, indexed by that source. */
private fun readKnown(text: String?): KnownLibrary {
    val array = parseArray(text) ?: return KnownLibrary(emptyMap())
    return KnownLibrary(
        (0 until array.length())
            .mapNotNull { array.optJSONObject(it) }
            .filter { it.has("source") && it.optInt("analysis_version") == ANALYSIS_VERSION }
            .associateBy { it.getString("source") },
    )
}

/**
 * Les photos déjà analysées : par leur adresse, ou, si elle a changé, par leur nom, leur taille
 * and their date (see [KnownFiles]).
 */
internal class KnownLibrary(private val byId: Map<String, JSONObject>) {
    private val byIdentity = KnownFiles.byIdentity(byId.values)

    fun find(file: FileNode): JSONObject? =
        KnownFiles.find(byId, byIdentity, file.id, file.name, file.length, file.lastModified)
}

/** The library knows this file as it is: same address, same size, same date. */
private fun unchanged(known: KnownLibrary, file: FileNode): JSONObject? {
    val item = known.find(file) ?: return null
    if (file.length < 0) return null
    return item.takeIf { it.optLong("size") == file.length && it.optLong("modified") == file.lastModified }
}

/** The type already known for an unchanged file: it is then not read during the listing. */
internal fun reusableMime(known: KnownLibrary, file: FileNode): String? =
    unchanged(known, file)?.optString("mime")?.takeIf { it.isNotBlank() }

/** Keep, complete or analyse: what the analysis does with a file. */
internal fun carry(known: KnownLibrary, file: FileNode): Carry {
    val item = unchanged(known, file) ?: return Carry.PROCESS
    return if (item.optString("mime").isNotBlank() && item.optString("folder").isNotBlank()) Carry.REUSE else Carry.BACKFILL
}

/** Rebuilds a result from an entry of the library, with today's folder and type. */
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

/** What each folder contains, before any analysis: for the Photos screen. */
data class FolderOverview(
    val status: FolderStatus,
    /** Photos already in the library and unchanged. */
    val known: Int,
    /** Photos nouvelles ou modifiées, à analyser. */
    val fresh: Int,
)

/** Reads the folders without analysing anything: how many photos, which are new, which are refused. */
fun overview(context: Context): List<FolderOverview> {
    val sources = Sources.read(context)
    val known = readKnown(LibraryStore.read(context))
    return sources.map { folder ->
        val root = Sources.rootOf(context, folder)
        val number = ReadProgress.start(folder.uri, FileTally.count(root))
        val scan = try {
            FolderScanner.scan(
                root,
                reusable = { file -> reusableMime(known, file) },
                examined = { count -> ReadProgress.report(folder.uri, number, count) },
            )
        } catch (e: Exception) {
            return@map FolderOverview(FolderStatus(folder, FolderStatus.State.REFUSED), 0, 0)
        } finally {
            ReadProgress.finish(folder.uri, number)
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

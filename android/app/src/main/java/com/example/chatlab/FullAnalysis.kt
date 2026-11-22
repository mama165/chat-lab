package com.example.chatlab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

object AnalysisProgress {
    private val value = MutableStateFlow(Progress())
    val state: StateFlow<Progress> = value.asStateFlow()

    internal fun set(progress: Progress) {
        value.value = progress
    }
}

/**
 * Analyse tout le dossier choisi (sous-dossiers compris) dans un service de premier plan,
 * pour que le traitement continue quand on quitte l'application.
 *
 * Les images sont traitées par WORKERS travailleurs en parallèle ; chaque travailleur a son
 * propre analyseur. Une photo inchangée (même source, taille et date) est reprise du carnet
 * sans être analysée de nouveau. Le carnet est enregistré dans le dossier chatlab, et les
 * photos restent à leur place d'origine : rien n'est copié de façon permanente.
 */
class AnalysisService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val chatlab = intent?.getStringExtra(EXTRA_CHATLAB)
        val source = intent?.getStringExtra(EXTRA_SOURCE)
        if (chatlab == null || source == null || AnalysisProgress.state.value.running) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val started = System.currentTimeMillis()
        AnalysisProgress.set(Progress(running = true, startedAt = started, message = "Préparation…"))
        startForeground(NOTIFICATION_ID, progressNotification(AnalysisProgress.state.value))

        scope.launch {
            try {
                val images = listImages(this@AnalysisService, Uri.parse(source))
                val total = images.size
                val known = readKnown(this@AnalysisService)
                val results = arrayOfNulls<ImageResult>(total)
                val next = AtomicInteger(0)
                val done = AtomicInteger(0)
                val errors = AtomicInteger(0)
                val reused = AtomicInteger(0)
                val saveLock = Any()
                val previousAll = Array(total) { previousResult(images[it], known) }
                var lastSave = System.currentTimeMillis()

                // Un analyseur par travailleur : chacun charge ses modèles et travaille seul.
                val analyzers = List(WORKERS) { Analyzer(this@AnalysisService, Uri.parse(chatlab)) }
                try {
                    coroutineScope {
                        analyzers.map { analyzer ->
                            async(Dispatchers.Default) {
                                while (true) {
                                    val index = next.getAndIncrement()
                                    if (index >= total) break
                                    val image = images[index]
                                    val (result, wasReused) = analyzeOne(this@AnalysisService, analyzer, image, known)
                                    results[index] = result
                                    if (wasReused) reused.incrementAndGet()
                                    if (result.error != null) errors.incrementAndGet()
                                    val finished = done.incrementAndGet()
                                    publish(
                                        Progress(true, finished, total, image.name ?: "image", started, errors.get()),
                                    )
                                    // Carnet enregistré régulièrement : une fermeture ne fait perdre que
                                    // les dernières images, et la relance reprend là où on s'était arrêté.
                                    synchronized(saveLock) {
                                        if (System.currentTimeMillis() - lastSave >= SAVE_INTERVAL_MS) {
                                            lastSave = System.currentTimeMillis()
                                            analyzers.first().writeLibrary(snapshot(results, previousAll))
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                    val all = results.map { it ?: error("résultat manquant") }
                    analyzers.first().writeLibrary(all)

                    val lines = StringBuilder()
                    all.forEach { lines.appendLine(describe(it)) }
                    val ok = all.count { it.error == null }
                    val elapsed = System.currentTimeMillis() - started
                    val header = "Compte rendu de l'analyse complète du dossier choisi, sous-dossiers compris.\n" +
                        "Analyse complète : $total fichier(s), ${reused.get()} repris du carnet, " +
                        "$ok ajouté(s), ${errors.get()} erreur(s), $WORKERS travailleurs, durée ${clock(elapsed)}\n"
                    finish(
                        Progress(false, total, total, "", started, errors.get(), report = header + lines),
                        "Analyse terminée en ${clock(elapsed)} : $ok image(s) ajoutée(s), ${errors.get()} erreur(s).",
                    )
                } finally {
                    analyzers.forEach { it.close() }
                }
            } catch (e: Throwable) {
                val message = "Analyse interrompue : ${e.message ?: e.javaClass.simpleName}"
                finish(
                    Progress(false, AnalysisProgress.state.value.done, AnalysisProgress.state.value.total, "", started,
                        AnalysisProgress.state.value.errors, message),
                    message,
                )
            }
        }
        return START_NOT_STICKY
    }

    @Synchronized
    private fun publish(progress: Progress) {
        AnalysisProgress.set(progress)
        val now = System.currentTimeMillis()
        if (now - lastNotified > 1000 || progress.done == progress.total) {
            lastNotified = now
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, progressNotification(progress))
        }
    }

    private fun finish(progress: Progress, text: String) {
        AnalysisProgress.set(progress.copy(message = text))
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(
            DONE_ID,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("ChatLab")
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build(),
        )
        stopSelf()
    }

    private fun progressNotification(progress: Progress): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Analyse en cours")
            .setContentText(progressLine(progress, System.currentTimeMillis()))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    /** Un appui sur la notification ramène à l'écran principal de l'application. */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Analyse des fichiers", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CHATLAB = "chatlab"
        const val EXTRA_SOURCE = "source"

        /** Nombre d'images analysées en même temps. Chaque travailleur charge ses modèles. */
        const val WORKERS = 2

        /** Le carnet est enregistré au plus toutes les deux minutes pendant l'analyse. */
        const val SAVE_INTERVAL_MS = 120_000L

        private const val CHANNEL = "analysis"
        private const val NOTIFICATION_ID = 1
        private const val DONE_ID = 2

        fun start(context: Context, chatlab: String, source: String) {
            val intent = Intent(context, AnalysisService::class.java)
                .putExtra(EXTRA_CHATLAB, chatlab)
                .putExtra(EXTRA_SOURCE, source)
            context.startForegroundService(intent)
        }
    }
}

/**
 * Analyse une image pour un travailleur. Une photo inchangée est reprise du carnet ; sinon
 * elle est copiée provisoirement, analysée, puis la copie est supprimée.
 * Renvoie le résultat et s'il a été repris.
 */
private fun analyzeOne(
    context: Context,
    analyzer: Analyzer,
    image: DocumentFile,
    known: Map<String, JSONObject>,
): Pair<ImageResult, Boolean> {
    val name = image.name ?: "image"
    val previous = previousResult(image, known)
    if (previous != null) return previous to true
    return try {
        // Le dossier provisoire porte le nom d'origine, pour que le carnet garde le bon nom.
        val work = File(context.cacheDir, "analyse/${UUID.randomUUID()}").apply { mkdirs() }
        val temp = File(work, name)
        try {
            copyTo(context, image, temp)
            val started = System.nanoTime()
            val analyzed = analyzer.analyzeFile(temp)
            val millis = (System.nanoTime() - started) / 1_000_000
            analyzed.copy(
                source = image.uri.toString(),
                modified = image.lastModified(),
                size = image.length(),
                millis = millis,
                taken = takenDate(temp) ?: fileDate(image.lastModified()),
            ) to false
        } finally {
            work.deleteRecursively()
        }
    } catch (e: Throwable) {
        ImageResult(File(name), null, 0, false, null, 0, e.message ?: e.javaClass.simpleName) to false
    }
}

/** Une ligne du compte rendu, dans le même format que l'analyse des images de test. */
private fun describe(r: ImageResult): String {
    if (r.error != null) return "${r.file.name} : erreur, ${r.error}"
    val place = when (r.indoor) {
        true -> "intérieur"
        false -> "extérieur"
        null -> "inconnu"
    }
    val text = if (r.text.isNotBlank()) "\n  texte : ${r.text}" else ""
    return "${r.file.name} : $place, ${r.faces} visage(s), ${r.millis} ms$text"
}

/** Toutes les images du dossier et de ses sous-dossiers. */
private fun listImages(context: Context, tree: Uri): List<DocumentFile> {
    val root = DocumentFile.fromTreeUri(context, tree) ?: error("dossier choisi inaccessible")
    val found = ArrayList<DocumentFile>()
    fun walk(folder: DocumentFile) {
        for (child in folder.listFiles()) {
            if (child.isDirectory) walk(child)
            else if (child.isFile && isImage(child.name)) found += child
        }
    }
    walk(root)
    return found
}

private fun isImage(name: String?): Boolean =
    name != null && name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp")

/** Copie provisoire d'une photo, pour l'analyser. */
private fun copyTo(context: Context, image: DocumentFile, target: File) {
    val input = context.contentResolver.openInputStream(image.uri) ?: error("lecture impossible : ${target.name}")
    input.use { source -> target.outputStream().use { source.copyTo(it) } }
}

/** Les entrées du carnet existant qui ont une source, indexées par cette source. */
private fun readKnown(context: Context): Map<String, JSONObject> {
    val text = LibraryStore.read(context) ?: return emptyMap()
    return try {
        val array = JSONArray(text)
        (0 until array.length())
            .map { array.getJSONObject(it) }
            .filter { it.has("source") && it.optInt("analysis_version") == ANALYSIS_VERSION }
            .associateBy { it.getString("source") }
    } catch (e: Exception) {
        emptyMap()
    }
}

/** Le résultat déjà connu d'une photo inchangée, ou null s'il faut l'analyser. */
private fun previousResult(image: DocumentFile, known: Map<String, JSONObject>): ImageResult? {
    val item = known[image.uri.toString()]?.takeIf {
        it.optLong("size") == image.length() && it.optLong("modified") == image.lastModified()
    } ?: return null
    return fromLibrary(item, File(image.name ?: "image"))
}

/**
 * Carnet partiel : les résultats déjà obtenus, et les photos connues pas encore atteintes,
 * pour qu'un enregistrement intermédiaire ne perde rien de ce qui était déjà dans le carnet.
 */
private fun snapshot(
    results: Array<ImageResult?>,
    previous: Array<ImageResult?>,
): List<ImageResult> = results.indices.mapNotNull { index ->
    results[index] ?: previous[index]
}

/** Rebâtit un résultat à partir d'une entrée du carnet, sans relancer l'analyse. */
private fun fromLibrary(item: JSONObject, file: File): ImageResult {
    val values = item.optJSONArray("vectors")?.optJSONArray(0)?.let { array ->
        FloatArray(array.length()) { array.getDouble(it).toFloat() }
    }
    return ImageResult(
        file = file,
        indoor = if (item.has("indoor")) item.getBoolean("indoor") else null,
        faces = item.optInt("faces", 0),
        portrait = item.optBoolean("portrait", false),
        vector = values,
        millis = 0,
        text = item.optString("text", ""),
        taken = item.optString("taken", ""),
        source = item.optString("source", ""),
        modified = item.optLong("modified", 0L),
        size = item.optLong("size", 0L),
    )
}

/** Date de prise de vue (EXIF), au format jj/mm/aaaa hh:mm, ou null si absente. */
private fun takenDate(file: File): String? {
    val raw = try {
        ExifInterface(file.path).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
    } catch (e: Exception) {
        null
    } ?: return null
    val date = raw.substringBefore(' ').split(':')
    val time = raw.substringAfter(' ', "").take(5)
    return if (date.size == 3) "${date[2]}/${date[1]}/${date[0]} $time".trim() else null
}

/** Date de modification du fichier, utilisée quand la photo n'a pas de date EXIF. */
private fun fileDate(millis: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(millis))

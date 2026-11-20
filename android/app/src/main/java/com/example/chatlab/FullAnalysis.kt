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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** État de l'analyse complète, lu par l'écran pour afficher la progression. */
data class Progress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val startedAt: Long = 0L,
    val errors: Int = 0,
    val message: String = "",
    val report: String = "",
)

object AnalysisProgress {
    private val value = MutableStateFlow(Progress())
    val state: StateFlow<Progress> = value.asStateFlow()

    internal fun set(progress: Progress) {
        value.value = progress
    }
}

/**
 * Analyse tout le dossier choisi (sous-dossiers compris) dans un service de premier plan,
 * pour que le traitement continue quand on quitte l'application. Les images sont copiées
 * dans l'espace privé de l'application avant d'être analysées, afin que la recherche puisse
 * les afficher. Une image déjà analysée et inchangée (même source, taille et date) est
 * reprise du carnet existant sans être analysée de nouveau. Le carnet library.json est
 * réécrit à la fin.
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
                Analyzer(this@AnalysisService, Uri.parse(chatlab)).use { analyzer ->
                    val images = listImages(this@AnalysisService, Uri.parse(source))
                    val total = images.size
                    val known = readKnown(this@AnalysisService)
                    val results = ArrayList<ImageResult>()
                    val lines = StringBuilder()
                    var errors = 0
                    var reused = 0
                    images.forEachIndexed { index, image ->
                        val name = image.name ?: "image"
                        publish(Progress(true, index, total, name, started, errors))
                        val sourceUri = image.uri.toString()
                        val target = targetFor(this@AnalysisService, sourceUri, name)
                        val previous = known[sourceUri]?.takeIf {
                            it.optLong("size") == image.length() &&
                                it.optLong("modified") == image.lastModified() &&
                                target.exists()
                        }
                        val result = try {
                            if (previous != null) {
                                reused++
                                fromLibrary(previous, target)
                            } else {
                                copyOnce(this@AnalysisService, image, target)
                                analyzer.analyzeFile(target).copy(
                                    source = sourceUri,
                                    modified = image.lastModified(),
                                    taken = takenDate(target) ?: fileDate(image.lastModified()),
                                )
                            }
                        } catch (e: Throwable) {
                            ImageResult(File(name), null, 0, false, null, 0, e.message ?: e.javaClass.simpleName)
                        }
                        if (result.error != null) errors++
                        results += result
                        lines.appendLine(describe(result))
                    }
                    analyzer.writeLibrary(results)
                    val ok = results.count { it.error == null }
                    val elapsed = System.currentTimeMillis() - started
                    val header = "Analyse complète : $total fichier(s), $reused repris du carnet, " +
                        "$ok ajouté(s), $errors erreur(s), durée ${clock(elapsed)}\n"
                    finish(
                        Progress(false, total, total, "", started, errors, report = header + lines),
                        "Analyse terminée : $ok image(s) ajoutée(s), $errors erreur(s).",
                    )
                }
            } catch (e: Throwable) {
                finish(
                    Progress(false, AnalysisProgress.state.value.done, AnalysisProgress.state.value.total, "", started,
                        AnalysisProgress.state.value.errors, "Analyse interrompue : ${e.message ?: e.javaClass.simpleName}"),
                    "Analyse interrompue : ${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
        return START_NOT_STICKY
    }

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

/** Ligne de texte affichée à l'écran et dans la notification. */
fun progressLine(progress: Progress, now: Long): String {
    if (!progress.running) return progress.message
    if (progress.total == 0) return progress.message.ifBlank { "Recherche des images…" }
    val elapsed = (now - progress.startedAt).coerceAtLeast(0L)
    val percent = progress.done * 100 / progress.total
    val remaining = if (progress.done > 0) elapsed * (progress.total - progress.done) / progress.done else 0L
    return buildString {
        append("${progress.done} / ${progress.total} fichiers ($percent %)")
        append(" · écoulé ${clock(elapsed)}")
        if (progress.done > 0) append(" · reste ~${clock(remaining)}")
        if (progress.errors > 0) append(" · ${progress.errors} erreur(s)")
        if (progress.current.isNotBlank()) append("\n${progress.current}")
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

private fun clock(millis: Long): String {
    val seconds = millis / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
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

/** Emplacement privé d'une image : filesDir/full/<empreinte de la source>/<nom>. */
private fun targetFor(context: Context, sourceUri: String, name: String): File {
    val key = MessageDigest.getInstance("SHA-1")
        .digest(sourceUri.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)
    return File(File(context.filesDir, "full/$key").apply { mkdirs() }, name)
}

/** Copie l'image vers target, sauf si une copie de même taille est déjà là. */
private fun copyOnce(context: Context, image: DocumentFile, target: File) {
    if (target.exists() && target.length() == image.length()) return
    val input = context.contentResolver.openInputStream(image.uri) ?: error("lecture impossible : ${target.name}")
    input.use { source -> target.outputStream().use { source.copyTo(it) } }
}

/** Les entrées du carnet existant qui ont une source, indexées par cette source. */
private fun readKnown(context: Context): Map<String, JSONObject> {
    val file = File(context.filesDir, "library.json")
    if (!file.exists()) return emptyMap()
    return try {
        val array = JSONArray(file.readText(Charsets.UTF_8))
        (0 until array.length())
            .map { array.getJSONObject(it) }
            .filter { it.has("source") }
            .associateBy { it.getString("source") }
    } catch (e: Exception) {
        emptyMap()
    }
}

/** Rebâtit un résultat à partir d'une entrée du carnet, sans relancer l'analyse. */
private fun fromLibrary(item: JSONObject, target: File): ImageResult {
    val values = item.optJSONArray("vectors")?.optJSONArray(0)?.let { array ->
        FloatArray(array.length()) { array.getDouble(it).toFloat() }
    }
    return ImageResult(
        file = target,
        indoor = if (item.has("indoor")) item.getBoolean("indoor") else null,
        faces = item.optInt("faces", 0),
        portrait = item.optBoolean("portrait", false),
        vector = values,
        millis = 0,
        text = item.optString("text", ""),
        taken = item.optString("taken", ""),
        source = item.optString("source", ""),
        modified = item.optLong("modified", 0L),
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

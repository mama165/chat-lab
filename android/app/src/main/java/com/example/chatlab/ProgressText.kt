package com.example.chatlab

/** The progress of a folder: files analysed out of files to analyse. */
data class FolderProgress(
    val name: String,
    val done: Int,
    val total: Int,
    /** The file being processed in this folder, empty if none. */
    val current: String = "",
) {
    /** Nothing new in this folder: it is not read again. */
    val nothingNew: Boolean get() = total == 0
    val finished: Boolean get() = done >= total
}

/** State of the whole analysis, read by the screen to show the progress. */
data class Progress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val startedAt: Long = 0L,
    val errors: Int = 0,
    val message: String = "",
    val report: String = "",
    /** True when the analysis is frozen. */
    val paused: Boolean = false,
    /** Start of the current pause, 0 otherwise. */
    val pausedAt: Long = 0L,
    /** Total duration of the pauses already finished. */
    val pausedTotal: Long = 0L,
    /** The progress of each folder, in the order of the list. */
    val folders: List<FolderProgress> = emptyList(),
    /** Workers reloading their models after a pause; 0 when none is loading. */
    val loadingModels: Int = 0,
    /** Each model loaded since the last reload, with its loading time in milliseconds. */
    val modelSteps: Map<String, Long> = emptyMap(),
) {
    /** A worker starts reloading its models. The models loaded before are forgotten. */
    fun loadingStarted(): Progress = copy(loadingModels = loadingModels + 1, modelSteps = emptyMap())

    /** A model is loaded: the first time it is seen is the one kept. */
    fun modelDone(name: String, millis: Long): Progress =
        if (modelSteps.containsKey(name)) this else copy(modelSteps = modelSteps + (name to millis))

    /** A worker has its models again. Never below zero. */
    fun loadingDone(): Progress = copy(loadingModels = (loadingModels - 1).coerceAtLeast(0))

    /** Which screen the analysis shows: the models reloading, the pause, or the running list. */
    fun runningScreen(): RunningScreen = when {
        loadingModels > 0 -> RunningScreen.RELOADING
        paused -> RunningScreen.PAUSED
        else -> RunningScreen.RUNNING
    }

    /** Part faite, de 0 à 1. */
    val fraction: Float
        get() = if (total > 0) done.toFloat() / total else 0f

    /** Temps de calcul réel, pauses exclues. */
    fun activeElapsed(now: Long): Long {
        val pausing = if (paused && pausedAt > 0) now - pausedAt else 0L
        return (now - startedAt - pausedTotal - pausing).coerceAtLeast(0L)
    }

    /** Time left estimated from the current pace, or null if it is too early to saye. */
    fun remaining(now: Long): Long? {
        if (done <= 0 || total <= 0) return null
        return activeElapsed(now) * (total - done) / done
    }

    /** One more file is done in the folder [index]; [name] is the file that has just'être traité. */
    /** A worker takes a photo of this folder: the folder starts being read, before its photo is done. */
    fun taking(index: Int, name: String): Progress {
        if (index !in folders.indices) return this
        return copy(folders = folders.mapIndexed { i, f -> if (i == index) f.copy(current = name) else f })
    }

    fun advancing(index: Int, name: String): Progress {
        if (index !in folders.indices) return copy(done = done + 1, current = name)
        val updated = folders.mapIndexed { i, f -> if (i == index) f.copy(done = f.done + 1, current = name) else f }
        return copy(done = done + 1, current = name, folders = updated)
    }

    /** Met en pause : l'horloge s'arrête. */
    fun pausing(now: Long): Progress = if (paused) this else copy(paused = true, pausedAt = now)

    /** Resumes: the duration of the pause is removed from the elapsed time. */
    fun resuming(now: Long): Progress =
        if (!paused) this else copy(paused = false, pausedAt = 0L, pausedTotal = pausedTotal + (now - pausedAt).coerceAtLeast(0L))
}

/** Line of text shown on the screen and in the notification. */
fun progressLine(progress: Progress, now: Long): String {
    if (!progress.running) return progress.message
    if (progress.total == 0) return progress.message.ifBlank { "Recherche des images…" }
    val percent = progress.done * 100 / progress.total
    val remaining = progress.remaining(now)
    return buildString {
        append("${progress.done} / ${progress.total} fichiers ($percent %)")
        if (progress.paused) {
            append(" · en pause")
        } else {
            append(" · écoulé ${clock(progress.activeElapsed(now))}")
            if (remaining != null) append(" · reste ~${clock(remaining)}")
        }
        if (progress.errors > 0) append(" · ${progress.errors} erreur(s)")
        if (progress.current.isNotBlank() && !progress.paused) append("\n${progress.current}")
    }
}

/** "3:07": minutes and seconds. */
fun clock(millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** "Environ 2 minutes restantes", "Environ 1 heure et 52 minutes restantes", for the analysis screen. */
fun remainingText(millis: Long?): String = when {
    millis == null -> "Estimation du temps restant…"
    millis < 60_000L -> "Moins d'une minute restante"
    else -> {
        val minutes = ((millis + 30_000L) / 60_000L).toInt()
        if (minutes == 1) "Environ 1 minute restante" else "Environ ${durationText(minutes)} restantes"
    }
}

/** A duration in French, in minutes under an hour, then in hours and minutes: "50 minutes", "2 heures et 13 minutes". */
fun durationText(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    if (hours == 0) return if (minutes == 1) "1 minute" else "$minutes minutes"
    val hoursText = if (hours == 1) "1 heure" else "$hours heures"
    return when (minutes) {
        0 -> hoursText
        1 -> "$hoursText et 1 minute"
        else -> "$hoursText et $minutes minutes"
    }
}

/** La marque d'un dossier dans la liste de l'analyse : fini, en cours, ou en attente. */
enum class FolderMark { FINISHED, RUNNING, WAITING }

/** The names of the models, as the analysis file and the screens show them, in loading order. */
object ModelNames {
    const val IMAGES = "Modèle des images"
    const val TEXTS = "Modèle des textes"
    const val FACES = "Détection des visages"
    val ORDER = listOf(IMAGES, TEXTS, FACES)
}

/** One row of the reloading view: loaded (with its time), being loaded, or still to come. */
enum class ModelRowState { DONE, LOADING, WAITING }

/** The rows of the reloading view: the models in loading order, each with its state. */
fun reloadRows(steps: Map<String, Long>, loading: Boolean): List<Pair<String, ModelRowState>> {
    var current = true
    return ModelNames.ORDER.map { name ->
        when {
            steps.containsKey(name) -> name to ModelRowState.DONE
            loading && current -> {
                current = false
                name to ModelRowState.LOADING
            }
            else -> {
                current = false
                name to ModelRowState.WAITING
            }
        }
    }
}

/** The screens of an analysis in progress: running, paused (view 2.2), models reloading (view 2.3). */
enum class RunningScreen { RUNNING, PAUSED, RELOADING }

/**
 * What the gauge of a folder shows: null while it turns (started, no photo finished yet), then
 * the share of its photos done.
 */
fun FolderProgress.gaugeFraction(): Float? =
    if (mark() != FolderMark.RUNNING || done == 0) null else Progress01.of(done, total)

/** Le dossier est fini (ou n'a rien de nouveau), il est en cours s'il a commencé, sinon il attend. */
fun FolderProgress.mark(): FolderMark = when {
    nothingNew || finished -> FolderMark.FINISHED
    done > 0 || current.isNotBlank() -> FolderMark.RUNNING
    else -> FolderMark.WAITING
}

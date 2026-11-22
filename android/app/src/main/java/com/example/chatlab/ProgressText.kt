package com.example.chatlab

/** La progression d'un dossier : fichiers analysés sur fichiers à analyser. */
data class FolderProgress(
    val name: String,
    val done: Int,
    val total: Int,
    /** Le fichier en cours dans ce dossier, vide si aucun. */
    val current: String = "",
) {
    /** Rien de nouveau dans ce dossier : il n'est pas relu. */
    val nothingNew: Boolean get() = total == 0
    val finished: Boolean get() = done >= total
}

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
    /** Vrai quand l'analyse est figée. */
    val paused: Boolean = false,
    /** Début de la pause en cours, 0 sinon. */
    val pausedAt: Long = 0L,
    /** Durée totale des pauses déjà terminées. */
    val pausedTotal: Long = 0L,
    /** La progression de chaque dossier, dans l'ordre de la liste. */
    val folders: List<FolderProgress> = emptyList(),
) {
    /** Part faite, de 0 à 1. */
    val fraction: Float
        get() = if (total > 0) done.toFloat() / total else 0f

    /** Temps de calcul réel, pauses exclues. */
    fun activeElapsed(now: Long): Long {
        val pausing = if (paused && pausedAt > 0) now - pausedAt else 0L
        return (now - startedAt - pausedTotal - pausing).coerceAtLeast(0L)
    }

    /** Temps restant estimé d'après le rythme actuel, ou null s'il est trop tôt pour le dire. */
    fun remaining(now: Long): Long? {
        if (done <= 0 || total <= 0) return null
        return activeElapsed(now) * (total - done) / done
    }

    /** Un fichier de plus est fait dans le dossier [index] ; [name] est le fichier qui vient d'être traité. */
    fun advancing(index: Int, name: String): Progress {
        if (index !in folders.indices) return copy(done = done + 1, current = name)
        val updated = folders.mapIndexed { i, f -> if (i == index) f.copy(done = f.done + 1, current = name) else f }
        return copy(done = done + 1, current = name, folders = updated)
    }

    /** Met en pause : l'horloge s'arrête. */
    fun pausing(now: Long): Progress = if (paused) this else copy(paused = true, pausedAt = now)

    /** Reprend : la durée de la pause est retirée du temps écoulé. */
    fun resuming(now: Long): Progress =
        if (!paused) this else copy(paused = false, pausedAt = 0L, pausedTotal = pausedTotal + (now - pausedAt).coerceAtLeast(0L))
}

/** Ligne de texte affichée à l'écran et dans la notification. */
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

/** « 3:07 » : minutes et secondes. */
fun clock(millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** « Environ 2 minutes restantes », « Environ 1 heure et 52 minutes restantes », pour l'écran d'analyse. */
fun remainingText(millis: Long?): String = when {
    millis == null -> "Estimation du temps restant…"
    millis < 60_000L -> "Moins d'une minute restante"
    else -> {
        val minutes = ((millis + 30_000L) / 60_000L).toInt()
        if (minutes == 1) "Environ 1 minute restante" else "Environ ${durationText(minutes)} restantes"
    }
}

/** Une durée en français, en minutes sous l'heure, puis en heures et minutes : « 50 minutes », « 2 heures et 13 minutes ». */
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

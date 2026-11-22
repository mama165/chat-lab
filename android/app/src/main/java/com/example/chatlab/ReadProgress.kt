package com.example.chatlab

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Où en est la lecture de chaque dossier (nombre de fichiers examinés), pour l'afficher pendant
 * que la lecture se fait. Un dossier absent de la liste n'est pas en cours de lecture.
 *
 * Une lecture reçoit un numéro au départ. Une lecture relancée (par exemple après « Tout
 * effacer ») ne peut plus être arrêtée : l'ancienne continue jusqu'au bout, mais ses chiffres
 * n'atteignent plus l'écran. Sans cela, deux lectures écrivaient dans le même compteur.
 */
object ReadProgress {
    /** Une mise à jour toutes les [EVERY] fichiers : l'écran reste fluide sur un gros dossier. */
    const val EVERY = 50

    private val state = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = state.asStateFlow()

    /** La lecture en cours pour chaque dossier. */
    private val current = HashMap<String, Int>()
    private var lastNumber = 0

    /** Commence une lecture du dossier, et renvoie son numéro. */
    @Synchronized
    fun start(folder: String): Int {
        val number = ++lastNumber
        current[folder] = number
        state.update { it + (folder to 0) }
        return number
    }

    @Synchronized
    fun report(folder: String, number: Int, examined: Int) {
        if (current[folder] != number || examined % EVERY != 0) return
        state.update { it + (folder to examined) }
    }

    @Synchronized
    fun finish(folder: String, number: Int) {
        if (current[folder] != number) return
        current.remove(folder)
        state.update { it - folder }
    }
}

package com.example.chatlab

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Où en est la lecture de chaque dossier (nombre de fichiers examinés), pour l'afficher pendant
 * que la lecture se fait. Un dossier absent de la liste n'est pas en cours de lecture.
 */
object ReadProgress {
    /** Une mise à jour toutes les [EVERY] fichiers : l'écran reste fluide sur un gros dossier. */
    const val EVERY = 50

    private val state = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = state.asStateFlow()

    fun start(folder: String) = state.update { it + (folder to 0) }

    fun report(folder: String, examined: Int) {
        if (examined % EVERY != 0) return
        state.update { it + (folder to examined) }
    }

    fun finish(folder: String) = state.update { it - folder }
}

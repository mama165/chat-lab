package com.example.chatlab

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** État de commande d'une analyse : en marche, figée (pause) ou en train de s'arrêter. */
enum class RunState { RUNNING, PAUSED, STOPPING }

/** Levée quand l'analyse doit s'arrêter : la photo en cours est abandonnée, le reste est gardé. */
class AnalysisStopped : RuntimeException("analyse arrêtée")

/**
 * Commande d'une analyse : pause, reprise et arrêt.
 *
 * Les travailleurs appellent [checkpoint] entre deux étapes d'une photo. En pause, ils y restent
 * bloqués sans rien calculer, jusqu'à [resume] (uniquement sur demande) ou [stop]. Rien n'est
 * perdu : la photo en cours reprend à l'étape où elle s'était figée.
 */
class AnalysisControl(private val onChange: (RunState) -> Unit = {}) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var current = RunState.RUNNING

    val state: RunState
        get() = lock.withLock { current }

    /** Fige l'analyse. Sans effet si elle est déjà en pause ou en train de s'arrêter. */
    fun pause(): Boolean = move(from = RunState.RUNNING, to = RunState.PAUSED)

    /** Reprend une analyse en pause. */
    fun resume(): Boolean = move(from = RunState.PAUSED, to = RunState.RUNNING)

    /** Demande l'arrêt : les travailleurs, même en pause, sortent à leur prochain point d'arrêt. */
    fun stop(): Boolean {
        val moved = lock.withLock {
            if (current == RunState.STOPPING) return false
            current = RunState.STOPPING
            changed.signalAll()
            true
        }
        if (moved) onChange(RunState.STOPPING)
        return moved
    }

    /** Attend tant que l'analyse est en pause ; lève [AnalysisStopped] si l'arrêt est demandé. */
    fun checkpoint() {
        lock.withLock {
            while (current == RunState.PAUSED) changed.await()
            if (current == RunState.STOPPING) throw AnalysisStopped()
        }
    }

    private fun move(from: RunState, to: RunState): Boolean {
        val moved = lock.withLock {
            if (current != from) return false
            current = to
            changed.signalAll()
            true
        }
        if (moved) onChange(to)
        return moved
    }
}

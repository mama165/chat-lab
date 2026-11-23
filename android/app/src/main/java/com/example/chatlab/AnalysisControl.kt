package com.example.chatlab

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Control state of an analysis: running, frozen (paused) or stopping. */
enum class RunState { RUNNING, PAUSED, STOPPING }

/** Thrown when the analysis must stop: the current photo is dropped, the rest is kept. */
class AnalysisStopped : RuntimeException("analyse arrêtée")

/**
 * Control of an analysis: pause, resume and stop.
 *
 * Workers call [checkpoint] between two steps of a photo. When paused, they stay there
 * blocked without computing anything, until [resume] (only on request) or [stop]. Nothing is
 * lost: the current photo resumes at the step where it was frozen.
 */
class AnalysisControl(private val onChange: (RunState) -> Unit = {}) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var current = RunState.RUNNING

    val state: RunState
        get() = lock.withLock { current }

    /** Freezes the analysis. No effect if it is already paused or stopping. */
    fun pause(): Boolean = move(from = RunState.RUNNING, to = RunState.PAUSED)

    /** Resumes a paused analysis. */
    fun resume(): Boolean = move(from = RunState.PAUSED, to = RunState.RUNNING)

    /** Requests a stop: the workers, even when paused, leave at their next stop point. */
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

    /** Waits while the analysis is paused; throws [AnalysisStopped] if a stop is requested. */
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

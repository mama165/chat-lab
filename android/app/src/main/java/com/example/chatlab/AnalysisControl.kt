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
 * A pause takes effect at the next photo boundary: the photo in progress is finished, then the
 * worker releases its models ([boundary] calls [onPaused]) and waits without computing anything.
 * On [resume], the worker reloads its models ([onResumed]) before taking the next photo. A stop
 * is raised at the next stop point, in the photo or at the boundary.
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

    /**
     * Stop point inside a photo: only a stop is raised. A pause does not freeze the photo in
     * progress, it takes effect at the next boundary.
     */
    fun checkpointStep() {
        if (state == RunState.STOPPING) throw AnalysisStopped()
    }

    /** Waits while the analysis is paused; throws [AnalysisStopped] if a stop is requested. */
    fun checkpoint() {
        lock.withLock {
            while (current == RunState.PAUSED) changed.await()
            if (current == RunState.STOPPING) throw AnalysisStopped()
        }
    }

    /**
     * Stop point between two photos. When paused, [onPaused] runs once before the wait (models
     * released), and [onResumed] runs after it (models reloaded), unless a stop comes first.
     */
    fun boundary(onPaused: () -> Unit = {}, onResumed: () -> Unit = {}) {
        val paused = lock.withLock { current == RunState.PAUSED }
        if (paused) {
            onPaused()
            lock.withLock {
                while (current == RunState.PAUSED) changed.await()
            }
        }
        if (state == RunState.STOPPING) throw AnalysisStopped()
        if (paused) onResumed()
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

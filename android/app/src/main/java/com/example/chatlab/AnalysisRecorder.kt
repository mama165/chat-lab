package com.example.chatlab

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Records, during an analysis, the app's CPU, its memory, the battery and the heat, at
 * regular intervals. The records are kept for the analysis file.
 */
class AnalysisRecorder(private val context: Context, private val intervalMs: Long = 10_000L) {
    private val samples = ArrayList<Sample>()
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private var job: Job? = null
    private var startedAt = 0L
    private var lastWall = 0L
    private var lastCpu = 0L

    /** Workers allowed right now: the memory guard changes it during the run. */
    @Volatile var workers: Int = 0

    fun start(scope: CoroutineScope) {
        startedAt = System.currentTimeMillis()
        lastWall = startedAt
        lastCpu = Process.getElapsedCpuTime()
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(intervalMs)
                if (isActive) sample()
            }
        }
    }

    /** Stops the records, takes a last one and returns all the records. */
    suspend fun stop(): List<Sample> {
        job?.cancelAndJoin()
        job = null
        sample()
        return synchronized(samples) { samples.toList() }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private fun sample() {
        val now = System.currentTimeMillis()
        val cpuNow = Process.getElapsedCpuTime()
        val wall = (now - lastWall).coerceAtLeast(1L)
        // The app's CPU time, relative to the duration and to all the cores: 100% = all the cores.
        val cpuPercent = ((cpuNow - lastCpu).toFloat() / wall / cores * 100f).coerceIn(0f, 100f)
        lastWall = now
        lastCpu = cpuNow
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
        val temperature = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        } else {
            0
        }
        synchronized(samples) {
            samples += Sample(
                tSec = ((now - startedAt) / 1000).toInt(),
                cpuPercent = cpuPercent,
                memMb = (memory.totalPss / 1024),
                batteryPct = if (level < 0) -1 else level * 100 / scale,
                batteryTempC = temperature,
                thermal = thermal,
                workers = workers,
            )
        }
    }
}

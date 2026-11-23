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
 * Relève, pendant une analyse, le CPU de l'appli, sa mémoire, la batterie et la chauffe, à
 * intervalles réguliers. Les relevés sont gardés pour le fichier d'analyse.
 */
class AnalysisRecorder(private val context: Context, private val intervalMs: Long = 10_000L) {
    private val samples = ArrayList<Sample>()
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private var job: Job? = null
    private var startedAt = 0L
    private var lastWall = 0L
    private var lastCpu = 0L

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

    /** Arrête les relevés, prend un dernier relevé et renvoie tous les relevés. */
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
        // Temps CPU de l'appli rapporté à la durée et à l'ensemble des cœurs : 100 % = tous les cœurs.
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
            )
        }
    }
}

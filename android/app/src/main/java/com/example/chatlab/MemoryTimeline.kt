package com.example.chatlab

import android.content.Context
import android.os.Debug
import org.json.JSONObject

/**
 * Samples the memory every few seconds into the journal ("memSample"): the native heap (allocated, reserved,
 * free), the process memory as Android counts it, the thumbnails on screen, the decodings running, and the text
 * model's calls. Comparing the lines shows which part grows, and when.
 */
object MemoryTimeline {
    private const val PERIOD_MS = 2_000L
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        Thread({
            while (true) {
                runCatching { sample(app) }
                Thread.sleep(PERIOD_MS)
            }
        }, "MemoryTimeline").apply {
            isDaemon = true
            start()
        }
    }

    private fun sample(context: Context) {
        val mb = 1024L * 1024L
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val data = JSONObject()
            // Native heap: what is in use, what the allocator has reserved, what is free inside that reservation.
            .put("nativeAllocMb", Debug.getNativeHeapAllocatedSize() / mb)
            .put("nativeReservedMb", Debug.getNativeHeapSize() / mb)
            .put("nativeFreeMb", Debug.getNativeHeapFreeSize() / mb)
            // Process memory as Android counts it (values in kB, converted to MB).
            .put("totalPssMb", info.totalPss / 1024)
            .put("nativePssMb", info.nativePss / 1024)
            .put("dalvikPssMb", info.dalvikPss / 1024)
            .put("graphicsMb", (info.getMemoryStat("summary.graphics")?.toLongOrNull() ?: 0L) / 1024)
            // Java heap.
            .put("javaUsedMb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / mb)
            // Thumbnails: on screen now, and decoding.
            .put("thumbMb", ThumbStats.megabytes())
            .put("thumbs", ThumbStats.count())
            .put("decodesRunning", ThumbStats.running())
            .put("decodesPeak", ThumbStats.peak())
            .put("decodedTotalMb", ThumbStats.decodedMb())
            // Text model.
            .put("encodes", TextModels.encodeCount())
            .put("cacheHits", TextModels.cacheHitCount())
        Diagnostics.event(context, "memSample", data)
    }
}

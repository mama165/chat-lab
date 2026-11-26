package com.example.chatlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class AnalysisRecordTest {
    private val zone: TimeZone = TimeZone.getTimeZone("Europe/Paris")
    private val start = 1_795_000_000_000L

    private fun sample(t: Int, cpu: Float, mem: Int, battery: Int = 80) = Sample(t, cpu, mem, battery, 36f, 1)

    private fun record(samples: List<Sample> = emptyList(), analyzed: Int = 100, durationMs: Long = 100_000L) = AnalysisRecord(threadsPerSession = 3, 
        startMs = start,
        endMs = start + durationMs,
        level = "equilibre",
        workers = 2,
        stopped = false,
        appVersion = "abc1234",
        device = "Pixel 10a",
        androidVersion = 16,
        cores = 8,
        folders = listOf(
            FolderStat("Camera", analyzed = analyzed, ignored = 3, unread = 1, refused = 0),
            FolderStat("Download", analyzed = 0, ignored = 2, unread = 0, refused = 1),
        ),
        samples = samples,
    )

    @Test
    fun theJsonRoundTripKeepsEverything() {
        val original = record(listOf(sample(0, 10f, 400), sample(10, 62.5f, 780, 79)))
        val text = original.toJson().toString()
        val back = AnalysisRecord.fromJson(JSONObject(text))
        assertEquals(original.level, back.level)
        assertEquals(original.workers, back.workers)
        assertEquals(original.device, back.device)
        assertEquals(original.androidVersion, back.androidVersion)
        assertEquals(original.cores, back.cores)
        assertEquals(original.appVersion, back.appVersion)
        assertEquals(original.folders, back.folders)
        assertEquals(original.samples.size, back.samples.size)
        assertEquals(62.5f, back.samples[1].cpuPercent, 0.001f)
        assertEquals(780, back.samples[1].memMb)
        assertEquals(79, back.samples[1].batteryPct)
        assertEquals(original.durationMs, back.durationMs)
        assertEquals(original.threadsPerSession, back.threadsPerSession)
    }

    @Test
    fun theJsonHasTheThreeBlocks() {
        val json = record().toJson()
        assertTrue(json.has("run"))
        assertTrue(json.has("device"))
        assertTrue(json.has("folders"))
        assertTrue(json.has("samples"))
    }

    @Test
    fun theDurationIsNeverNegative() {
        assertEquals(0L, record(durationMs = -5_000L).durationMs)
    }

    @Test
    fun theTotalsAddUpOverTheFolders() {
        val r = record(analyzed = 100)
        assertEquals(100, r.analyzed)
        assertEquals(5, r.ignored)
    }

    @Test
    fun theSummaryGivesAverageAndPeaks() {
        val s = record(listOf(sample(0, 10f, 400), sample(10, 50f, 780), sample(20, 30f, 600)), durationMs = 20_000L).summary()
        assertEquals(30f, s.avgCpu, 0.001f)
        assertEquals(50f, s.peakCpu, 0.001f)
        assertEquals(780, s.peakMemMb)
        assertEquals(100 / 20.0, s.photosPerSecond, 0.0001)
    }

    @Test
    fun theSummaryOfAnEmptyRunIsZero() {
        val s = record(emptyList(), analyzed = 0, durationMs = 0L).summary()
        assertEquals(0f, s.avgCpu, 0.0001f)
        assertEquals(0f, s.peakCpu, 0.0001f)
        assertEquals(0, s.peakMemMb)
        assertEquals(0.0, s.photosPerSecond, 0.0001)
        assertEquals(null, s.batteryStart)
    }

    @Test
    fun theSummaryKeepsTheBatteryAtTheStartAndTheEnd() {
        val s = record(listOf(sample(0, 1f, 1, battery = 90), sample(10, 1f, 1, battery = 84))).summary()
        assertEquals(90, s.batteryStart)
        assertEquals(84, s.batteryEnd)
    }

    @Test
    fun theThermalMaximumIsTheHighestSeen() {
        val samples = listOf(Sample(0, 1f, 1, 80, 30f, 0), Sample(10, 1f, 1, 80, 33f, 2), Sample(20, 1f, 1, 80, 31f, 1))
        assertEquals(2, record(samples).summary().thermalMax)
    }

    @Test
    fun theFileNameIsTheStartDateAndTime() {
        // 2026-11-23 13:30 UTC = 14:30 in Paris.
        val utc = 1_795_000_000_000L
        val name = AnalysisFiles.nameFor(java.util.GregorianCalendar.getInstance(zone).apply {
            clear(); set(2026, 10, 23, 14, 30)
        }.timeInMillis, zone)
        assertEquals("2026-11-23_1430.json", name)
        assertTrue(AnalysisFiles.nameFor(utc, zone).endsWith(".json"))
    }

    @Test
    fun downsampleKeepsTheFirstAndTheLastPoint() {
        val many = (0 until 100).map { sample(it * 10, it.toFloat(), 1) }
        val small = AnalysisFiles.downsample(many, 10)
        assertEquals(10, small.size)
        assertEquals(0, small.first().tSec)
        assertEquals(990, small.last().tSec)
    }

    @Test
    fun downsampleLeavesASmallCurveAlone() {
        val few = listOf(sample(0, 1f, 1), sample(10, 2f, 2))
        assertEquals(few, AnalysisFiles.downsample(few, 10))
    }

    @Test
    fun downsampleToZeroOrOnePoint() {
        val many = (0 until 5).map { sample(it, 1f, 1) }
        assertEquals(emptyList<Sample>(), AnalysisFiles.downsample(many, 0))
        assertEquals(0, AnalysisFiles.downsample(many, 1).single().tSec)
    }
}

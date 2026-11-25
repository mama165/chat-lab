package com.example.chatlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerChangesTest {
    private fun sample(t: Int, workers: Int) =
        Sample(t, 10f, 100, 80, 30f, 0, workers)

    @Test
    fun changesAreTheMomentsWhereTheNumberChanged() {
        val samples = listOf(sample(0, 4), sample(60, 4), sample(120, 3), sample(180, 3), sample(240, 2), sample(300, 1))
        assertEquals(
            listOf(
                ChartMath.WorkerChange(0, 4),
                ChartMath.WorkerChange(120, 3),
                ChartMath.WorkerChange(240, 2),
                ChartMath.WorkerChange(300, 1),
            ),
            ChartMath.workerChanges(samples),
        )
    }

    @Test
    fun aRunWithoutChangeHasOnlyItsStart() {
        val samples = listOf(sample(0, 2), sample(60, 2), sample(120, 2))
        assertEquals(listOf(ChartMath.WorkerChange(0, 2)), ChartMath.workerChanges(samples))
    }

    @Test
    fun samplesWithoutWorkersGiveNoChange() {
        val samples = listOf(sample(0, 0), sample(60, 0))
        assertTrue(ChartMath.workerChanges(samples).isEmpty())
        assertTrue(ChartMath.workerChanges(emptyList()).isEmpty())
    }

    @Test
    fun aRecordMadeBeforeTheWorkersWereKeptStillReads() {
        val json = JSONObject(
            """
            {"run":{"start":"2026-11-23T14:30:00","end":"2026-11-23T14:35:00","durationS":300,
                    "level":"equilibre","workers":2,"stopped":false},
             "device":{"model":"Pixel 10a","android":37,"cores":8,"appVersion":"1.0"},
             "folders":[],
             "samples":[{"t":0,"cpu":10.0,"memMb":100,"battery":80,"batteryTempC":30.0,"thermal":0}]}
            """.trimIndent(),
        )
        val record = AnalysisRecord.fromJson(json)
        assertEquals(0, record.samples.single().workers)
    }

    @Test
    fun aRecordKeepsTheWorkersOfEachSample() {
        val json = JSONObject(
            """
            {"run":{"start":"2026-11-23T14:30:00","end":"2026-11-23T14:35:00","durationS":300,
                    "level":"maximal","workers":4,"stopped":false},
             "device":{"model":"Pixel 10a","android":37,"cores":8,"appVersion":"1.0"},
             "folders":[],
             "samples":[{"t":0,"cpu":10.0,"memMb":100,"battery":80,"batteryTempC":30.0,"thermal":0,"workers":4},
                        {"t":60,"cpu":10.0,"memMb":100,"battery":80,"batteryTempC":30.0,"thermal":0,"workers":3}]}
            """.trimIndent(),
        )
        val record = AnalysisRecord.fromJson(json)
        assertEquals(listOf(4, 3), record.samples.map { it.workers })
        assertEquals(
            listOf(ChartMath.WorkerChange(0, 4), ChartMath.WorkerChange(60, 3)),
            ChartMath.workerChanges(record.samples),
        )
    }
}

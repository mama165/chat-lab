package com.example.chatlab

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class AnalysisReportTest {
    private val folders = listOf(SourceFolder("content://cam", "Camera"), SourceFolder("content://drive", "Google Drive · Photos"))

    private class Node(override val name: String) : FileNode {
        override val length = 10L
        override val lastModified = 1L
        override val id = "id://$name"
        override fun open(): InputStream? = null
    }

    private fun scan(name: String, candidates: Int = 0, ignored: Int = 0, refused: List<String> = emptyList(), root: Boolean = false) =
        ScanResult(
            name,
            (1..candidates).map { Candidate(Node("f$it.jpg"), FileSniffer.JPEG, name) },
            (1..ignored).map { IgnoredFile("x$it.txt", null, name) },
            emptyList(),
            refused,
            root,
        )

    @Test
    fun theBuilderCountsAnalysedAndFailedFilesPerFolder() {
        val builder = ReportBuilder(folders)
        repeat(3) { builder.analyzed(0) }
        builder.analyzed(1)
        builder.failed(1, "Vacances_2025.jpg", FailureReason.INACCESSIBLE)
        builder.failed(1, "Scan.jpg", FailureReason.UNREADABLE)
        val report = builder.build(1000L, 5000L, stopped = false)
        assertEquals(3, report.folders[0].analyzed)
        assertEquals(1, report.folders[1].analyzed)
        assertEquals(2, report.folders[1].unread)
        assertEquals(4, report.analyzed)
        assertEquals(2, report.unread)
        assertTrue(report.hasProblem)
        assertFalse(report.folders[0].hasProblem)
    }

    @Test
    fun scanResultsFeedIgnoredRefusedAndEarlyFailures() {
        val builder = ReportBuilder(folders)
        builder.scanned(
            0,
            ScanResult(
                "Camera", emptyList(), List(4) { IgnoredFile("n$it", null, "Camera") },
                listOf(UnreadableFile("vide.jpg", FailureReason.EMPTY, "Camera")), listOf("Privé"), rootRefused = false,
            ),
        )
        builder.scanned(1, scan("Drive", root = true))
        val report = builder.build(0, 0, false)
        assertEquals(4, report.folders[0].ignored)
        assertEquals(listOf("Privé"), report.folders[0].refused)
        assertEquals(listOf(FileFailure("vide.jpg", FailureReason.EMPTY)), report.folders[0].failures)
        assertTrue(report.folders[1].rootRefused)
        assertEquals(2, report.refusedFolders)
        assertEquals(4, report.ignored)
    }

    @Test
    fun scanningAgainReplacesTheEarlierScan() {
        val builder = ReportBuilder(folders)
        builder.scanned(0, scan("Camera", refused = listOf("A")))
        builder.scanned(0, scan("Camera", refused = listOf("B")))
        assertEquals(listOf("B"), builder.build(0, 0, false).folders[0].refused)
    }

    @Test
    fun aCleanAnalysisHasNoProblem() {
        val builder = ReportBuilder(folders)
        builder.analyzed(0)
        assertFalse(builder.build(0, 0, false).hasProblem)
    }

    @Test
    fun theBuilderIsSafeAcrossThreads() {
        val builder = ReportBuilder(folders)
        val threads = List(8) { Thread { repeat(500) { builder.analyzed(0); builder.failed(1, "f", FailureReason.UNREADABLE) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val report = builder.build(0, 0, false)
        assertEquals(4000, report.folders[0].analyzed)
        assertEquals(4000, report.folders[1].unread)
    }

    @Test
    fun jsonRoundTripKeepsEverything() {
        val builder = ReportBuilder(folders)
        builder.scanned(0, scan("Camera", ignored = 2, refused = listOf("Privé", "Voyage/Secret")))
        builder.analyzed(0)
        builder.failed(1, "a.jpg", FailureReason.INACCESSIBLE)
        builder.failed(1, "b.png", FailureReason.UNREADABLE)
        builder.failed(1, "c.jpg", FailureReason.EMPTY)
        builder.failed(1, "d.jpg", FailureReason.ANALYSIS)
        val report = builder.build(123456L, 7000L, stopped = true)
        assertEquals(report, RunReport.fromJson(report.toJson()))
    }

    @Test
    fun fromJsonToleratesGarbage() {
        assertNull(RunReport.fromJson(null))
        assertNull(RunReport.fromJson(""))
        assertNull(RunReport.fromJson("pas du json"))
        assertNull(RunReport.fromJson("{}"))
    }

    @Test
    fun anUnknownReasonFallsBackOnAnalysisError() {
        val json = JSONObject()
            .put("finished_at", 1L)
            .put(
                "folders",
                JSONArray().put(
                    JSONObject().put("name", "X").put("analyzed", 0)
                        .put("failures", JSONArray().put(JSONObject().put("name", "a.jpg").put("reason", "INCONNUE"))),
                ),
            )
        assertEquals(FailureReason.ANALYSIS, RunReport.fromJson(json.toString())!!.folders[0].failures[0].reason)
    }

    @Test
    fun theTextReportListsEachFileWithItsReason() {
        val builder = ReportBuilder(folders)
        builder.analyzed(0)
        builder.failed(1, "Vacances_2025.jpg", FailureReason.INACCESSIBLE)
        builder.scanned(0, scan("Camera", ignored = 3, refused = listOf("Privé")))
        val text = builder.build(0, 125_000L, stopped = false).toText()
        assertTrue(text, text.contains("Analyse terminée en 2:05"))
        assertTrue(text, text.contains("1 photo(s) analysée(s), 1 fichier(s) non lu(s)"))
        assertTrue(text, text.contains("Vacances_2025.jpg (fichier inaccessible)"))
        assertTrue(text, text.contains("sous-dossier refusé par Android : Privé"))
        assertTrue(text, text.contains("3 fichier(s) ignoré(s)"))
    }

    @Test
    fun aStoppedAnalysisSaysSo() {
        val text = ReportBuilder(folders).build(0, 1000L, stopped = true).toText()
        assertTrue(text, text.contains("arrêtée avant la fin"))
    }

    @Test
    fun thePlanSplitsNewFilesFromCarriedOnes() {
        val scans = listOf(scan("Camera", candidates = 5), scan("Drive", candidates = 3))
        val plan = AnalysisPlan.build(folders, scans) { if (it.name == "f1.jpg") Carry.REUSE else Carry.PROCESS }
        assertEquals(2, plan.carried.size)
        assertEquals(6, plan.toProcess.size)
        assertEquals(4, plan.processCount(0))
        assertEquals(2, plan.processCount(1))
    }

    @Test
    fun aBackfilledFileIsCarriedNotProcessed() {
        val plan = AnalysisPlan.build(folders, listOf(scan("Camera", candidates = 2), scan("Drive"))) { Carry.BACKFILL }
        assertTrue(plan.toProcess.isEmpty())
        assertEquals(2, plan.carried.size)
    }

    @Test
    fun aFolderWithNothingNewHasNothingToProcess() {
        val plan = AnalysisPlan.build(folders, listOf(scan("Camera", candidates = 3), scan("Drive"))) { Carry.REUSE }
        assertEquals(0, plan.processCount(0))
        assertEquals(0, plan.processCount(1))
    }

    @Test
    fun photosOfRemovedFoldersStayInTheLibrary() {
        val old = JSONArray()
            .put(JSONObject().put("id", "a").put("folder", "Camera"))
            .put(JSONObject().put("id", "b").put("folder", "Ancien dossier"))
            .put(JSONObject().put("id", "c"))
            .put(JSONObject().put("id", "d").put("folder", "Screenshots"))
        val kept = LibraryMerge.keepFromRemovedFolders(old, setOf("Camera", "Screenshots"))
        assertEquals(listOf("b"), kept.map { it.getString("id") })
    }

    @Test
    fun nothingIsKeptWithoutAnOldLibrary() {
        assertTrue(LibraryMerge.keepFromRemovedFolders(null, setOf("Camera")).isEmpty())
        assertTrue(LibraryMerge.keepFromRemovedFolders(JSONArray(), setOf("Camera")).isEmpty())
    }
}

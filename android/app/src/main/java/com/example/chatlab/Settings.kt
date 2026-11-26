package com.example.chatlab

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatlab.ui.Palette
import com.example.chatlab.ui.PageHeader
import com.example.chatlab.ui.SectionLabel
import com.example.chatlab.ui.SettingRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings page, opened by the gear: the last analysis, the copy and the metrics, and erasing everything. */
@Composable
fun SettingsPage(
    dataVersion: Int,
    onBack: () -> Unit,
    onOpen: (Page) -> Unit,
    onWiped: () -> Unit,
) {
    val context = LocalContext.current
    val lastDate = remember(dataVersion) { ReportStore.lastDate(context) }
    val history = remember(dataVersion) { RunArchive.list(context).size }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        PageHeader("Paramètres", onBack = onBack)
        Column {
            SectionLabel("Analyse")
            SettingRow("Dernière analyse", lastDate?.let { formatLongDate(it) } ?: "jamais")
            SettingRow("Performance", LevelStore.read(context).label, onClick = { onOpen(Page.LEVEL) })
            SettingRow("Historique des analyses", "$history", onClick = { onOpen(Page.HISTORY) })
        }
        Column {
            SectionLabel("Sauvegarde et mesures")
            ReportRow()
            MetricsRow()
        }
        WipeSection(dataVersion = dataVersion, onWiped = onWiped)
        Spacer(Modifier.height(16.dp))
    }
}

/** "26 novembre 2026", for the last analysis. */
private fun formatLongDate(date: SimpleDate): String =
    "${date.day} ${Dates.MONTH_NAMES[date.month - 1].lowercase()} ${date.year}"

/** Métriques: the folder of the measurements, grey when it is not there or not reachable. */
@Composable
private fun MetricsRow() {
    val context = LocalContext.current
    val available by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) { OpenFolder.exists("diagnostics") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Métriques", fontSize = 15.sp, color = Palette.Ink)
            PillButton("Ouvrir", enabled = available) { OpenFolder.open(context, "diagnostics") }
        }
        Text(
            "Les mesures de l'app, pour comprendre les analyses et les lenteurs.",
            fontSize = 12.sp,
            color = Palette.Muted,
        )
    }
}

/** The steps of erasing: asking for the second press, then erased. */
private enum class WipeStep { IDLE, ASKED, DONE }

/**
 * Tout effacer, in place: a first press asks for a second one, which erases what the app created.
 * Once erased, or when there is nothing to erase, the button is grey.
 */
@Composable
private fun WipeSection(dataVersion: Int, onWiped: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(WipeStep.IDLE) }
    var version by remember { mutableStateOf(0) }
    val nothingToErase by produceState(initialValue = false, dataVersion, version) {
        value = withContext(Dispatchers.IO) { !hasCreatedData(context) }
    }
    val grey = nothingToErase || step == WipeStep.DONE
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        WipeButton(
            label = when {
                grey -> "Tout effacer"
                step == WipeStep.ASKED -> "Appuyer à nouveau pour confirmer"
                else -> "Tout effacer"
            },
            grey = grey,
            onClick = {
                if (step == WipeStep.IDLE) {
                    step = WipeStep.ASKED
                } else {
                    eraseCreatedData(context)
                    Toast.makeText(context, "Tout est effacé.", Toast.LENGTH_SHORT).show()
                    step = WipeStep.DONE
                    version++
                    onWiped()
                }
            },
        )
        if (step == WipeStep.ASKED) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("⚠", fontSize = 16.sp, color = Palette.Danger)
                Text("Cette opération est irréversible.", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            }
            WipeLine("✕", Palette.Danger, "Les données créées par l'application")
            WipeLine("✓", Palette.Accent, "Vos fichiers originaux restent intacts")
            WipeLine("✓", Palette.Accent, "Les modèles d'IA")
        }
        if (step == WipeStep.DONE) {
            WipeLine("✓", Palette.Accent, "Données créées par l'application supprimées")
            WipeLine("✓", Palette.Accent, "Vos fichiers originaux restent intacts")
        }
    }
}

/** The erase button: red, or grey when nothing can be erased. */
@Composable
private fun WipeButton(label: String, grey: Boolean, onClick: () -> Unit) {
    val color = if (grey) Palette.Disabled else Palette.Danger
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, color, RoundedCornerShape(12.dp))
            .clickable(enabled = !grey, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** One line of the erase details: a cross or a tick, and its text. */
@Composable
private fun WipeLine(icon: String, color: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(icon, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.width(18.dp))
        Text(text, fontSize = 14.sp, color = Palette.Ink)
    }
}

/** True when the app has created something: the analysis record, the recent searches, a report or the folders. */
private fun hasCreatedData(context: Context): Boolean =
    AnalysisRecordFiles.size(context.filesDir) > 0 ||
        RecentStore.read(context).isNotEmpty() ||
        ReportStore.read(context) != null ||
        Sources.read(context).isNotEmpty()

/** Erases what the app created, and nothing else: the photos of the gallery and the models stay. */
private fun eraseCreatedData(context: Context) {
    LibraryStore.wipeAnalysis(context)
    RecentStore.clear(context)
    ReportStore.clear(context)
    AnalysisProgress.clearReport()
    Sources.clear(context)
}

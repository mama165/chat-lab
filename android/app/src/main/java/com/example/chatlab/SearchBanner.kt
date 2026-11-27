package com.example.chatlab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatlab.ui.Palette
import kotlinx.coroutines.delay

/** What the bottom line shows: nothing, the analysis in progress, or the notice that it has finished. */
enum class BannerKind { HIDDEN, RUNNING, PAUSED, FINISHED }

/**
 * [notice] is set when an analysis has just finished, and cleared once the user has opened it:
 * an old analysis does not keep the notice on screen.
 */
fun analysisBannerKind(running: Boolean, done: Int, total: Int, notice: Boolean, paused: Boolean = false): BannerKind = when {
    running && paused -> BannerKind.PAUSED
    running -> BannerKind.RUNNING
    notice && total > 0 && done >= total -> BannerKind.FINISHED
    else -> BannerKind.HIDDEN
}

/**
 * Bottom line of the search, above the tabs: the analysis in progress with its time left,
 * then "Analyse terminée" and a link back to the analysis, right after it has finished.
 */
@Composable
fun AnalysisBanner(notice: Boolean, onOpen: () -> Unit) {
    val progress by AnalysisProgress.state.collectAsState()
    val kind = analysisBannerKind(progress.running, progress.done, progress.total, notice, progress.paused)
    if (kind == BannerKind.HIDDEN) return
    val finished = kind == BannerKind.FINISHED

    val now by produceState(initialValue = System.currentTimeMillis(), progress.running) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val percent = if (progress.total > 0) progress.done * 100 / progress.total else 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (finished) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier.size(16.dp).clip(CircleShape).background(Palette.Accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✓", fontSize = 10.sp, color = Color.White)
                    }
                    Text("Analyse terminée", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
                }
                Text(
                    "Voir l'analyse ›",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.Accent,
                    modifier = Modifier.clickable(onClick = onOpen).padding(4.dp),
                )
            } else if (kind == BannerKind.PAUSED) {
                Text("Analyse en pause", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("$percent %", fontSize = 13.sp, color = Palette.Muted)
                    PlayPauseButton(paused = true, onClick = { AnalysisService.resume() })
                }
            } else {
                Text("Analyse en cours", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "$percent % · ${remainingText(progress.remaining(now))}",
                        fontSize = 13.sp,
                        color = Palette.Muted,
                    )
                    PlayPauseButton(paused = false, onClick = { AnalysisService.pause() })
                }
            }
        }
        if (!finished) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Palette.Line),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(percent / 100f)
                        .height(3.dp)
                        .background(Palette.Accent),
                )
            }
        }
    }
}

/**
 * Pause or play of the analysis, a round outlined button. It acts on the same state as the notification and the
 * Photos tab, so the three always agree.
 */
@Composable
private fun PlayPauseButton(paused: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .border(1.dp, Palette.Accent, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (paused) "Reprendre l'analyse" else "Mettre l'analyse en pause" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(12.dp)) {
            if (paused) {
                val path = Path().apply {
                    moveTo(size.width * 0.15f, 0f)
                    lineTo(size.width, size.height / 2)
                    lineTo(size.width * 0.15f, size.height)
                    close()
                }
                drawPath(path, Palette.Accent)
            } else {
                val bar = size.width * 0.32f
                drawRect(Palette.Accent, topLeft = Offset(0f, 0f), size = Size(bar, size.height))
                drawRect(Palette.Accent, topLeft = Offset(size.width - bar, 0f), size = Size(bar, size.height))
            }
        }
    }
}

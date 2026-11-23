package com.example.chatlab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

/**
 * Bottom line of the search, above the tabs: the analysis in progress with its time left,
 * then, once it is finished, "Analyse terminée" and a link back to the analysis.
 * Nothing is shown when no analysis has run.
 */
@Composable
fun AnalysisBanner(onOpen: () -> Unit) {
    val progress by AnalysisProgress.state.collectAsState()
    val finished = !progress.running && progress.total > 0 && progress.done >= progress.total
    if (!progress.running && !finished) return

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
            } else {
                Text("Analyse en cours", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
                Text(
                    "$percent % · ${remainingText(progress.remaining(now))}",
                    fontSize = 13.sp,
                    color = Palette.Muted,
                )
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

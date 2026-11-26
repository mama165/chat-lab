package com.example.chatlab

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatlab.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The states of the copy: nothing made yet, the copy being written, the copy ready. */
private enum class ReportState { IDLE, RUNNING, DONE }

/**
 * The "Rapport" line of the settings: a backup copy of the analysed photos, made on request.
 * The same place shows Générer, then En cours (a thin bar slides under it), then Ouvrir.
 */
@Composable
fun ReportRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ReportState.IDLE) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Rapport", fontSize = 15.sp, color = Palette.Ink)
            Box(
                modifier = Modifier
                    .width(110.dp)
                    .height(32.dp)
                    .clip(CircleShape)
                    .border(1.dp, Palette.Accent, CircleShape)
                    .clickable(enabled = state != ReportState.RUNNING) {
                        when (state) {
                            ReportState.IDLE -> {
                                state = ReportState.RUNNING
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { LibraryExport.run(context) }
                                    }
                                    state = if (result.isSuccess) ReportState.DONE else ReportState.IDLE
                                    if (result.isFailure) {
                                        Toast.makeText(context, "Copie impossible", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            ReportState.DONE -> OpenFolder.open(context, "snapshots")
                            ReportState.RUNNING -> Unit
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    ReportState.IDLE -> Text("Générer", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
                    ReportState.RUNNING -> RunningLabel()
                    ReportState.DONE -> Text("Ouvrir", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
                }
            }
        }
        Text(
            "Copie de sauvegarde des photos analysées",
            fontSize = 12.sp,
            color = Palette.Muted,
        )
    }
}

/** "En cours" with a thin bar that slides under it, so the person sees that something is running. */
@Composable
private fun RunningLabel() {
    val transition = rememberInfiniteTransition(label = "report")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1200, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "bar",
    )
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            "En cours",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.Muted,
            modifier = Modifier.align(Alignment.Center),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (110 * progress - 44).dp)
                .width(44.dp)
                .height(2.dp)
                .background(Palette.Accent),
        )
    }
}

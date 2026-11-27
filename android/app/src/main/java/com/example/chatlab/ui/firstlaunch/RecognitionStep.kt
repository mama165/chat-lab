package com.example.chatlab.ui.firstlaunch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import com.example.chatlab.R

private val Ink = Color(0xFF1E2120)
private val Navy = Color(0xFF1F3A68)
private val BoxFill = Color(0xFFF1F3F7)
private val Muted = Color(0xFF6B7280)
private val Check = Color(0xFF000000)

private val PhotoRes = listOf(R.drawable.gaufre_1, R.drawable.gaufre_2, R.drawable.gaufre_3, R.drawable.gaufre_4)
private val PhotoX = listOf(12.dp, 74.dp, 136.dp, 198.dp)

/** Temps écoulé depuis l'arrivée de l'étape, en secondes, mis à jour à chaque image. */
@Composable
private fun rememberStepClock(): Float {
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> seconds = (now - start) / 1_000_000_000f }
        }
    }
    return seconds
}

/**
 * Étape « Petit tour des photos » : les photos tombent dans la boîte, les deux premières scintillent
 * l'une après l'autre, puis la coche apparaît. [onPickPhotos] ouvre le choix du dossier de photos ;
 * le bouton est utilisable dès l'arrivée de l'étape.
 */
@Composable
fun RecognitionStep(onPickPhotos: () -> Unit) {
    val t = rememberStepClock()
    val density = LocalDensity.current.density
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(RecognitionTimeline.blockAlpha(t))
    ) {
        Text(
            text = "Où se cachent vos photos ? 🕵️",
            color = Ink,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Pour les retrouver, il faut d’abord les parcourir. Choisissez le dossier où elles se trouvent.",
            color = Ink,
            fontSize = 15.sp,
            lineHeight = 22.sp
        )
        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .background(BoxFill)
        ) {
            Row(
                modifier = Modifier
                    .padding(start = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(text = "Petit tour des photos 🧐", color = Ink, fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                for (i in 0 until 3) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .alpha(RecognitionTimeline.dotAlpha(t, i))
                            .background(Check, CircleShape)
                    )
                }
            }

            for (i in 0 until RecognitionTimeline.PHOTO_COUNT) {
                val shimmer = when (i) {
                    0 -> RecognitionTimeline.shimmerBrightness(t, RecognitionTimeline.firstShimmerStart)
                    1 -> RecognitionTimeline.shimmerBrightness(t, RecognitionTimeline.secondShimmerStart)
                    else -> 1f
                }
                val brightness = ColorFilter.colorMatrix(
                    ColorMatrix(
                        floatArrayOf(
                            shimmer, 0f, 0f, 0f, 0f,
                            0f, shimmer, 0f, 0f, 0f,
                            0f, 0f, shimmer, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
                Image(
                    painter = painterResource(PhotoRes[i]),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = brightness,
                    modifier = Modifier
                        .offset(x = PhotoX[i], y = 34.dp)
                        .size(50.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .graphicsLayer {
                            alpha = RecognitionTimeline.photoAlpha(t, i)
                            translationY = RecognitionTimeline.photoOffsetDp(t, i) * density
                        }
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 12.dp)
                    .alpha(RecognitionTimeline.checkAlpha(t))
            ) {
                Text(text = "✓", color = Check, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .border(1.2.dp, Check, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "i", color = Check, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Aucune de vos données n’est supprimée ni collectée.",
                color = Check,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Navy, RoundedCornerShape(24.dp))
                .clickable(onClick = onPickPhotos),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "Choisir mes photos", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

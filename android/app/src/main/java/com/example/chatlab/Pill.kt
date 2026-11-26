package com.example.chatlab

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatlab.ui.Palette

/** The round action of a settings line, always the same size. Grey when the action is not possible. */
@Composable
fun PillButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val text = if (enabled) Palette.Accent else Color(0xFF9CA3AF)
    val border = if (enabled) Palette.Accent else Color(0xFFD1D5DB)
    Box(
        modifier = Modifier
            .width(110.dp)
            .height(32.dp)
            .clip(CircleShape)
            .border(1.dp, border, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = text)
    }
}

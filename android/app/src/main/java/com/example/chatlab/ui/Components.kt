package com.example.chatlab.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatlab.R

/** Title of a tab, with the settings gear on the right. */
@Composable
fun TabTitle(
    title: String,
    onSettings: () -> Unit,
    subtitle: String? = null,
    onAdd: (() -> Unit)? = null,
    onFilters: (() -> Unit)? = null,
    filtersCount: Int = 0,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, modifier = Modifier.weight(1f))
            if (onAdd != null) {
                // A plus in a light circle, next to the settings: it adds a folder to analyse.
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Palette.Field)
                        .clickable(onClick = onAdd)
                        .semantics { contentDescription = "Ajouter un dossier" },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val line = 2.dp.toPx()
                        drawLine(Palette.Accent, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = line, cap = StrokeCap.Round)
                        drawLine(Palette.Accent, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = line, cap = StrokeCap.Round)
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            if (onFilters != null) {
                // The filters sit next to the settings wheel, with a badge for the filters active.
                Box(modifier = Modifier.size(40.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(Palette.Field)
                            .clickable(onClick = onFilters)
                            .semantics { contentDescription = "Filtres" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_filtres), contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
                    }
                    if (filtersCount > 0) {
                        // As in the design: a small navy disc with the number, over the top-right edge of the circle.
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 2.dp, y = (-2).dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Palette.Accent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$filtersCount", fontSize = 10.sp, color = Color.White)
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Palette.Field)
                    .clickable(onClick = onSettings)
                    .semantics { contentDescription = "Réglages" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_molette), contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
            }
        }
        if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = Palette.Muted)
    }
}

/** Header of a separate page: back arrow, title, and an optional action on the right. */
@Composable
fun PageHeader(title: String, onBack: () -> Unit, action: String? = null, actionEnabled: Boolean = true, onAction: () -> Unit = {}) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "‹",
            fontSize = 30.sp,
            color = Palette.Muted,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .semantics { contentDescription = "Retour" }
                .padding(horizontal = 8.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(
                action,
                fontSize = 14.sp,
                color = if (actionEnabled) Palette.Accent else Palette.Disabled,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = actionEnabled, onClick = onAction)
                    .padding(8.dp),
            )
        }
    }
}

/** Section title, in grey. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, right: String? = null, onRight: () -> Unit = {}) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, modifier = Modifier.weight(1f))
        if (right != null) {
            Text(right, fontSize = 14.sp, color = Palette.Accent, modifier = Modifier.clickable(onClick = onRight).padding(4.dp))
        }
    }
}

/** Exclusive choice in segments, as on the Filtres page. */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    /** A small line under the label, for example the number of photos of this choice. */
    sub: (T) -> String? = { null },
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Field)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (option in options) {
            val on = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Color.White else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        label(option),
                        fontSize = 13.sp,
                        color = if (on) Palette.Accent else Palette.Muted,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                    sub(option)?.let { Text(it, fontSize = 11.sp, color = Palette.Muted, maxLines = 1) }
                }
            }
        }
    }
}

/** Filter label; with [onRemove], a cross removes it. */
@Composable
fun FilterTag(label: String, onRemove: (() -> Unit)?) {
    Text(
        if (onRemove != null) "$label ✕" else label,
        fontSize = 13.sp,
        color = Palette.Accent,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Soft)
            .then(if (onRemove != null) Modifier.clickable(onClick = onRemove) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Palette.Accent) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White,
            disabledContainerColor = Palette.Disabled,
            disabledContentColor = Color.White,
        ),
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Palette.Accent),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Palette.Accent),
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 15.sp)
    }
}

/** Small filled square, the stop symbol. */
@Composable
fun StopSquare(color: Color = Palette.Accent) {
    Box(Modifier.size(12.dp).background(color))
}

/** A settings line: title on the left, value on the right, chevron if it opens something. */
@Composable
fun SettingRow(title: String, value: String = "", color: Color = Palette.Ink, onClick: (() -> Unit)? = null) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 15.sp, color = color, modifier = Modifier.weight(1f))
            Text(value + if (onClick != null) " ›" else "", fontSize = 14.sp, color = Palette.Muted)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
    }
}

/** Thin separator with a text in the middle ("Moins proches · 6"). */
@Composable
fun TextDivider(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Palette.Line))
        Text(text, fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Palette.Line))
    }
}

/** Bottom bar: the two tabs. */
@Composable
fun BottomTabs(selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        Row(
            modifier = Modifier.fillMaxWidth().background(Color.White).padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            listOf("Rechercher", "Photos").forEachIndexed { index, label ->
                val on = index == selected
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) Palette.Accent else Palette.Muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (on) Palette.Soft else Color.Transparent)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Coloured border around a thumbnail (newly added photos). */
fun Modifier.glow(alpha: Float): Modifier =
    if (alpha <= 0f) this else this.border(3.dp, Palette.Soft.copy(alpha = alpha), RoundedCornerShape(14.dp))


/** A single-choice line: circle, label, number of photos on the right, chevron if it opens a page. */
@Composable
fun RadioRow(label: String, count: String, selected: Boolean, onClick: () -> Unit, chevron: Boolean = false, detail: String? = null) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .border(2.dp, if (selected) Palette.Accent else Color(0xFFC4C9D1), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.Accent))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 15.sp, color = Palette.Ink, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                if (detail != null) Text(detail, fontSize = 12.sp, color = Palette.Muted)
            }
            Text(count + if (chevron) " ›" else "", fontSize = 13.sp, color = Palette.Muted)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
    }
}

/** A multiple-choice chip, with the number of photos. Greyed and dotted if nothing matches itrespond. */
@Composable
fun ChoiceChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val empty = count == 0 && !selected
    Text(
        (if (selected) "✓ " else "") + label + " $count",
        fontSize = 14.sp,
        color = when {
            selected -> Color.White
            empty -> Color(0xFF9AA3AF)
            else -> Palette.Ink
        },
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Palette.Accent else Color.Transparent)
            .border(1.dp, if (selected) Palette.Accent else Palette.Line, RoundedCornerShape(18.dp))
            .clickable(enabled = !empty, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** A switch with its explanation. */
@Composable
fun SwitchRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 15.sp, color = Palette.Ink)
            Text(detail, fontSize = 12.sp, color = Palette.Muted)
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Palette.Accent),
        )
    }
}

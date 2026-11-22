package com.example.chatlab.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatlab.R

/** Titre d'un onglet, avec la molette des réglages à droite. */
@Composable
fun TabTitle(title: String, onSettings: () -> Unit, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, modifier = Modifier.weight(1f))
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

/** En-tête d'une page à part : flèche de retour, titre, et une action facultative à droite. */
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

/** Intitulé de section, en gris. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, right: String? = null, onRight: () -> Unit = {}) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, modifier = Modifier.weight(1f))
        if (right != null) {
            Text(right, fontSize = 14.sp, color = Palette.Accent, modifier = Modifier.clickable(onClick = onRight).padding(4.dp))
        }
    }
}

/** Choix exclusif en segments, comme sur la page Filtres. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
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
                Text(
                    label(option),
                    fontSize = 13.sp,
                    color = if (on) Palette.Accent else Palette.Muted,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Étiquette de filtre ; avec [onRemove], une croix la retire. */
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

/** Petit carré plein, symbole d'arrêt. */
@Composable
fun StopSquare(color: Color = Palette.Accent) {
    Box(Modifier.size(12.dp).background(color))
}

/** Une ligne de réglage : titre à gauche, valeur à droite, chevron si elle ouvre quelque chose. */
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

/** Séparation fine avec un texte au milieu (« Moins proches · 6 »). */
@Composable
fun TextDivider(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Palette.Line))
        Text(text, fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Palette.Line))
    }
}

/** Barre du bas : les deux onglets. */
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

/** Bord coloré autour d'une vignette (photos nouvellement ajoutées). */
fun Modifier.glow(alpha: Float): Modifier =
    if (alpha <= 0f) this else this.border(3.dp, Palette.Soft.copy(alpha = alpha), RoundedCornerShape(14.dp))

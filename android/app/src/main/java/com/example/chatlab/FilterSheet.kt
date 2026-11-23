package com.example.chatlab

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatlab.ui.Palette
import com.example.chatlab.ui.PrimaryButton

/** The four categories of the filter sheet, in the order of the chips. */
enum class SheetCategory(val label: String) {
    DATE("Date"),
    PLACE("Lieu"),
    PEOPLE("Personnes"),
    TYPE("Type"),
}

/** Date presets offered in the sheet; the hour and the 24 hours are kept in the model only. */
val SheetDatePresets = listOf(
    DatePreset.ALL,
    DatePreset.TODAY,
    DatePreset.LAST_7D,
    DatePreset.LAST_30D,
    DatePreset.LAST_12M,
)

/** The value shown after a category's name once it is set, "" if it is not: "Date · 7 derniers jours". */
fun sheetValue(category: SheetCategory, filters: Filters): String {
    val prefix = "${category.label} · "
    return filters.chips().firstOrNull { it.label.startsWith(prefix) }?.label?.removePrefix(prefix) ?: ""
}

/**
 * The filter sheet: it rises from the bottom edge, over the search, the analysis and the tabs.
 * Its chips choose the category shown below; each choice applies at once, "Appliquer" closes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.FilterSheet(
    state: SearchUiState,
    visible: Boolean,
    onChooseDates: () -> Unit,
    onClose: () -> Unit,
) {
    var category by remember { mutableStateOf(SheetCategory.DATE) }
    val filters = state.filters

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x24141821))
                .clickable(onClick = onClose),
        )
    }

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.Page)
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SheetCategory.entries.forEach { item ->
                        val value = sheetValue(item, filters)
                        CategoryPill(
                            label = if (value.isEmpty()) item.label else "${item.label} · $value",
                            selected = item == category,
                            applied = value.isNotEmpty(),
                            onClick = { category = item },
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clickable(onClick = onClose)
                        .semantics { contentDescription = "Fermer les filtres" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", fontSize = 16.sp, color = Palette.Ink)
                }
            }

            Crossfade(targetState = category, label = "filtre") { shown ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    when (shown) {
                        SheetCategory.DATE -> {
                            SheetDatePresets.forEach { preset ->
                                SheetRow(
                                    label = preset.label,
                                    selected = filters.date.range == null && filters.date.preset == preset,
                                    onClick = { state.filters = filters.copy(date = DateFilter(preset)) },
                                )
                            }
                            SheetRow(label = "Choisir des dates", selected = false, chevron = true, onClick = onChooseDates)
                        }
                        SheetCategory.PLACE -> {
                            SheetRow(
                                label = "Intérieur",
                                selected = filters.place == Place.INDOOR,
                                onClick = { state.filters = filters.copy(place = toggledPlace(filters.place, Place.INDOOR)) },
                            )
                            SheetRow(
                                label = "Extérieur",
                                selected = filters.place == Place.OUTDOOR,
                                onClick = { state.filters = filters.copy(place = toggledPlace(filters.place, Place.OUTDOOR)) },
                            )
                        }
                        SheetCategory.PEOPLE -> People.entries.forEach { people ->
                            SheetRow(
                                label = people.label,
                                selected = filters.people == people,
                                onClick = { state.filters = filters.copy(people = people) },
                            )
                        }
                        SheetCategory.TYPE -> FileKind.entries.forEach { kind ->
                            SheetRow(
                                label = kind.label,
                                selected = filters.kind == kind,
                                onClick = { state.filters = filters.copy(kind = kind) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            PrimaryButton("Appliquer", onClick = onClose)
        }
    }
}

/** Tapping the place chosen again removes it. */
fun toggledPlace(current: Place, tapped: Place): Place = if (current == tapped) Place.ALL else tapped

@Composable
private fun CategoryPill(label: String, selected: Boolean, applied: Boolean, onClick: () -> Unit) {
    val background = when {
        applied -> Palette.Accent
        selected -> Palette.Soft
        else -> Color.White
    }
    Text(
        label,
        fontSize = 14.sp,
        color = if (applied) Color.White else if (selected) Palette.Accent else Palette.Ink,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .border(1.dp, if (applied || selected) Palette.Accent else Palette.Line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun SheetRow(label: String, selected: Boolean, chevron: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            fontSize = 15.sp,
            color = if (chevron) Palette.Accent else Palette.Ink,
            fontWeight = if (chevron) FontWeight.SemiBold else FontWeight.Normal,
        )
        when {
            chevron -> Text("›", fontSize = 18.sp, color = Palette.Muted)
            selected -> Text("✓", fontSize = 16.sp, color = Palette.Accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The filters applied, under the recent searches: same size as the recent searches, blue, with a cross to remove each. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActiveFilterPills(chips: List<FilterChip>, onRemove: (FilterChip) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Palette.Soft)
                    .clickable { onRemove(chip) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(chip.label, fontSize = 14.sp, color = Palette.Accent, maxLines = 1)
                Text("✕", fontSize = 12.sp, color = Palette.Accent)
            }
        }
    }
}

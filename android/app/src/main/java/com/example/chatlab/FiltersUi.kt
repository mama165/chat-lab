package com.example.chatlab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatlab.ui.ChoiceChip
import com.example.chatlab.ui.PageHeader
import com.example.chatlab.ui.Palette
import com.example.chatlab.ui.PrimaryButton
import com.example.chatlab.ui.RadioRow
import com.example.chatlab.ui.SectionLabel
import com.example.chatlab.ui.Segmented
import com.example.chatlab.ui.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Page Filtres : date, lieu, personnes, gros plan, texte, dossiers, types et ressemblance. À côté
 * de chaque choix, le nombre de résultats qu'on aurait en le choisissant, avec les autres filtres.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FiltersPage(
    state: SearchUiState,
    entries: List<LibraryEntry>,
    removalsVersion: Int,
    onBack: () -> Unit,
    onDates: () -> Unit,
) {
    val context = LocalContext.current
    val filters = state.filters
    val time = remember(filters, state.query) { When(System.currentTimeMillis()) }
    val queryKey = state.query.trim().lowercase()
    val removed = remember(queryKey, removalsVersion) { Removals.sourcesFor(context, queryKey) }
    val folderOptions = remember(entries) { FilterOptions.folders(entries) }
    val typeOptions = remember(entries) { FilterOptions.types(entries) }
    val facets by produceState<Facets?>(initialValue = null, filters, entries, state.ranked, state.query, removed) {
        value = withContext(Dispatchers.Default) {
            FacetCounts.compute(entries, state.query, state.ranked, filters, removed, time)
        }
    }
    fun count(read: (Facets) -> Int?): String = facets?.let(read)?.toString() ?: "…"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        PageHeader("Filtres", onBack = onBack, action = "Réinitialiser", actionEnabled = !filters.isDefault) {
            state.filters = Filters()
        }
        Text(
            buildString {
                if (state.query.isNotBlank()) append("Pour « ${state.query} » · ")
                append(if (filters.activeCount == 0) "aucun filtre actif" else if (filters.activeCount == 1) "1 filtre actif" else "${filters.activeCount} filtres actifs")
                append(" · à droite, le nombre de photos")
            },
            fontSize = 13.sp,
            color = Palette.Muted,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        )
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                SectionLabel("Date de prise de vue")
                DatePreset.entries.forEach { preset ->
                    RadioRow(
                        label = preset.label,
                        count = count { it.datePresets[preset] },
                        selected = filters.date.range == null && filters.date.preset == preset,
                        onClick = { state.filters = filters.copy(date = DateFilter(preset)) },
                    )
                }
                val range = filters.date.range
                if (range != null) {
                    RadioRow(
                        label = rangeTitle(range, time),
                        detail = "Dates choisies · ${range.dayPart.label.lowercase()}",
                        count = count { it.customRange } + " · Modifier",
                        selected = true,
                        onClick = onDates,
                        chevron = true,
                    )
                } else {
                    RadioRow("Choisir des dates…", "", selected = false, onClick = onDates, chevron = true)
                }
                Text(
                    "Date et heure lues dans la photo ; sinon, date du fichier.",
                    fontSize = 11.sp,
                    color = Palette.Muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            FilterGroup("Lieu") {
                Segmented(
                    Place.entries, filters.place, { Filters.placeLabel(it) },
                    onSelect = { state.filters = filters.copy(place = it) },
                    sub = { place -> facets?.places?.get(place)?.toString() },
                )
            }
            FilterGroup("Personnes") {
                Segmented(
                    People.entries, filters.people, { it.label },
                    onSelect = { state.filters = filters.copy(people = it) },
                    sub = { people -> facets?.people?.get(people)?.toString() },
                )
            }
            SwitchRow(
                "Visage en gros plan",
                "Portraits : un visage occupe une bonne part de la photo",
                filters.closeUp,
            ) { state.filters = filters.copy(closeUp = it) }
            FilterGroup("Texte sur la photo", "Tickets, factures, panneaux, captures d'écran…") {
                Segmented(
                    TextFilter.entries, filters.text, { it.label },
                    onSelect = { state.filters = filters.copy(text = it) },
                    sub = { text -> facets?.texts?.get(text)?.toString() },
                )
            }
            if (folderOptions.isNotEmpty()) {
                FilterGroup("Dossier", right = "trouvés dans vos photos") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        folderOptions.forEach { option ->
                            ChoiceChip(
                                label = option.name,
                                count = facets?.folders?.get(option.name) ?: option.count,
                                selected = option.name in filters.folders,
                            ) {
                                state.filters = filters.copy(folders = filters.folders.toggled(option.name))
                            }
                        }
                    }
                }
            }
            if (typeOptions.isNotEmpty()) {
                FilterGroup("Type de fichier", "Seuls les types trouvés dans vos photos et que l'application sait lire.") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        typeOptions.forEach { option ->
                            ChoiceChip(
                                label = FileSniffer.label(option.mime),
                                count = facets?.types?.get(option.mime) ?: option.count,
                                selected = option.mime in filters.types,
                            ) {
                                state.filters = filters.copy(types = filters.types.toggled(option.mime))
                            }
                        }
                    }
                }
            }
            FilterGroup(
                "Ressemblance",
                "Point de départ des photos ressemblantes. « Voir des photos moins proches » permet ensuite d'aller plus loin, sans changer ce réglage.",
                right = "photos par description",
            ) {
                Segmented(
                    Resemblance.entries, filters.resemblance, { it.label },
                    onSelect = { state.filters = filters.copy(resemblance = it) },
                    sub = { level -> facets?.resemblance?.get(level)?.toString() },
                )
            }
        }
        Column(modifier = Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val total = facets?.total
            PrimaryButton(
                when {
                    state.query.isBlank() -> if (total == null) "Appliquer" else "Afficher ${Formats.photos(total)}"
                    total == null -> "Afficher les résultats"
                    else -> "Afficher ${Formats.results(total)}"
                },
                onClick = onBack,
            )
            Text(
                "Le nombre se met à jour à chaque choix. La flèche revient aussi aux résultats.",
                fontSize = 12.sp,
                color = Palette.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun <T> Set<T>.toggled(item: T): Set<T> = if (item in this) this - item else this + item

private fun rangeTitle(range: DateRange, time: When): String {
    val month = { d: SimpleDate -> "${d.day} ${Dates.MONTHS[d.month - 1]} ${d.year}" }
    return if (range.from == range.to) month(range.from) else "Du ${month(range.from)} au ${month(range.to)}"
}

@Composable
private fun FilterGroup(title: String, hint: String? = null, right: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title, right = null)
        if (right != null) Text(right, fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.padding(top = 0.dp))
        content()
        if (hint != null) Text(hint, fontSize = 12.sp, color = Palette.Muted)
    }
}

/** Page Dates : début et fin, raccourcis, calendrier avec les jours qui ont des photos, moment de la journée. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DatesPage(state: SearchUiState, entries: List<LibraryEntry>, onBack: () -> Unit) {
    val time = remember { When(System.currentTimeMillis()) }
    val initial = state.filters.date.range
    var selection by remember { mutableStateOf(DateSelection.of(initial)) }
    var dayPart by remember { mutableStateOf(initial?.dayPart ?: DayPart.ALL) }
    var month by remember { mutableStateOf((initial?.from ?: time.today).let { it.year to it.month }) }
    val photoDays = remember(entries) { FilterOptions.photoDays(entries, time.zone) }
    val range = selection.toRange(dayPart)
    val count = remember(range, entries) {
        range?.let { r -> entries.count { Filters(date = DateFilter(range = r)).matches(it, time) } } ?: 0
    }
    val weeks = remember(month, photoDays) { CalendarGrid.month(month.first, month.second, photoDays, time.zone) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        PageHeader("Dates", onBack = onBack, action = "Effacer", actionEnabled = selection.from != null) {
            selection = DateSelection()
            dayPart = DayPart.ALL
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DateField("Du", selection.from, active = selection.to == null && selection.from == null, modifier = Modifier.weight(1f))
                DateField("Au", selection.to ?: selection.from, active = selection.from != null && selection.to == null, modifier = Modifier.weight(1f))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateShortcut.entries.forEach { shortcut ->
                    Text(
                        shortcut.label,
                        fontSize = 14.sp,
                        color = Palette.Ink,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .border(1.dp, Palette.Line, RoundedCornerShape(18.dp))
                            .clickable {
                                val r = shortcut.range(time)
                                selection = DateSelection(r.from, r.to)
                                month = r.from.year to r.from.month
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹",
                    fontSize = 24.sp,
                    color = Palette.Muted,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { month = CalendarGrid.previous(month.first, month.second) }
                        .semantics { contentDescription = "Mois précédent" }
                        .padding(horizontal = 12.dp),
                )
                Text(
                    CalendarGrid.title(month.first, month.second),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.Ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "›",
                    fontSize = 24.sp,
                    color = Palette.Muted,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { month = CalendarGrid.next(month.first, month.second) }
                        .semantics { contentDescription = "Mois suivant" }
                        .padding(horizontal = 12.dp),
                )
            }
            Row {
                CalendarGrid.WEEKDAYS.forEach {
                    Text(it, fontSize = 11.sp, color = Palette.Muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            weeks.forEach { week ->
                Row {
                    week.forEach { cell ->
                        DayCell(cell, selection, modifier = Modifier.weight(1f)) { day -> selection = selection.tap(day) }
                    }
                }
            }
            Text("Un point sous un jour : des photos ce jour-là.", fontSize = 11.sp, color = Palette.Muted)
            SectionLabel("Heure de la journée")
            Segmented(DayPart.entries, dayPart, { it.label.removePrefix("Toute la ").replaceFirstChar { c -> c.uppercase() } }, onSelect = { dayPart = it })
        }
        Column(modifier = Modifier.padding(vertical = 14.dp)) {
            PrimaryButton(
                if (range == null) "Choisissez des dates"
                else "Valider · ${Formats.photos(count)} · ${Dates.rangeLabel(range.from, range.to, time.today)}",
                enabled = range != null,
                onClick = {
                    state.filters = state.filters.copy(date = DateFilter(range = range))
                    onBack()
                },
            )
        }
    }
}

@Composable
private fun DateField(label: String, date: SimpleDate?, active: Boolean, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, if (active) Palette.Accent else Palette.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, fontSize = 11.sp, color = Palette.Muted)
        Text(
            date?.let { "${it.day} ${Dates.MONTHS[it.month - 1]} ${it.year}" } ?: "—",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.Ink,
        )
    }
}

@Composable
private fun DayCell(cell: CalendarCell, selection: DateSelection, modifier: Modifier, onTap: (SimpleDate) -> Unit) {
    val day = cell.day
    if (day == null) {
        Box(modifier.aspectRatio(1f))
        return
    }
    val inside = selection.contains(day)
    val edge = day == selection.from || day == (selection.to ?: selection.from)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    edge -> Palette.Accent
                    inside -> Palette.Soft
                    else -> androidx.compose.ui.graphics.Color.Transparent
                },
            )
            .clickable { onTap(day) }
            .semantics { contentDescription = "jour ${day.day}" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${day.day}",
                fontSize = 14.sp,
                color = if (edge) androidx.compose.ui.graphics.Color.White else if (inside) Palette.Accent else Palette.Ink,
                fontWeight = if (edge) FontWeight.SemiBold else FontWeight.Normal,
            )
            Box(
                Modifier
                    .padding(top = 1.dp)
                    .height(4.dp)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(if (cell.hasPhotos) (if (edge) androidx.compose.ui.graphics.Color.White else Palette.Accent) else androidx.compose.ui.graphics.Color.Transparent),
            )
        }
    }
}

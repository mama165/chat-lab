package com.example.chatlab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.chatlab.ui.BottomTabs
import com.example.chatlab.ui.Palette

/** L'écran principal : deux onglets (Rechercher, Photos) et les pages à part. */
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by remember { mutableStateOf(Page.TABS) }
    // D'où l'on est venu pour choisir des dossiers : l'onglet Photos, ou les réglages.
    var storageBack by remember { mutableStateOf(Page.TABS) }
    val search = remember { SearchUiState() }
    // Augmentent quand le carnet ou les retraits changent, pour relire ce qui est enregistré.
    var dataVersion by remember { mutableIntStateOf(0) }
    var removalsVersion by remember { mutableIntStateOf(0) }
    val progress by AnalysisProgress.state.collectAsState()

    LaunchedEffect(progress.running) {
        if (!progress.running) dataVersion++
    }
    val entries = remember(dataVersion, tab) { Library.load(context) }

    BackHandler(enabled = page != Page.TABS) { page = page.parent() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Page)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        when (page) {
            Page.FILTERS -> FiltersPage(search, entries, removalsVersion, onBack = { page = Page.TABS }, onDates = { page = Page.DATES })
            Page.DATES -> DatesPage(search, entries, onBack = { page = Page.FILTERS })
            Page.SETTINGS -> SettingsPage(
                entries = entries,
                dataVersion = dataVersion,
                removalsVersion = removalsVersion,
                onBack = { page = Page.TABS },
                onOpen = { page = it },
                onFoldersChanged = { dataVersion++ },
                onAddFolders = {
                    storageBack = Page.SETTINGS
                    page = Page.STORAGE
                },
            )
            Page.STORAGE -> StorageChooser(
                onDone = {
                    dataVersion++
                    page = storageBack
                },
                onBack = { page = storageBack },
            )
            Page.REMOVED -> RemovedPage(onBack = { page = Page.SETTINGS }, onChanged = { removalsVersion++ })
            Page.WIPE -> WipePage(
                entries = entries,
                onBack = { page = Page.SETTINGS },
                onWiped = {
                    search.reset()
                    dataVersion++
                    removalsVersion++
                },
            )
            Page.TESTS -> TestsPage(onBack = { page = Page.SETTINGS })
            Page.TABS -> {
                val content = Modifier.weight(1f)
                if (tab == 0) {
                    SearchScreen(
                        state = search,
                        entries = entries,
                        removalsVersion = removalsVersion,
                        onRemovalsChanged = { removalsVersion++ },
                        onSettings = { page = Page.SETTINGS },
                        onFilters = { page = Page.FILTERS },
                        modifier = content,
                    )
                } else {
                    PhotosScreen(
                        entries = entries,
                        onSettings = { page = Page.SETTINGS },
                        onAddFolders = {
                            storageBack = Page.TABS
                            page = Page.STORAGE
                        },
                        modifier = content,
                    )
                }
                BottomTabs(selected = tab, onSelect = { tab = it })
            }
        }
    }
}

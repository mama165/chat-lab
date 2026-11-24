package com.example.chatlab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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

/** The main screen: two tabs (Rechercher, Photos) and the separate pages. */
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by remember { mutableStateOf(Page.TABS) }
    // Where we came from to choose folders: the Photos tab, or the settings.
    var storageBack by remember { mutableStateOf(Page.TABS) }
    // The analysis file opened from the history (its name in ChatLab/analyses).
    var openedRun by remember { mutableStateOf("") }
    val search = remember { SearchUiState() }
    // Increase when the library or the removals change, to read again what is saved.
    var dataVersion by remember { mutableIntStateOf(0) }
    var removalsVersion by remember { mutableIntStateOf(0) }
    val progress by AnalysisProgress.state.collectAsState()

    // The notice "Analyse terminée" appears only when a run ends while the app is open.
    var wasRunning by remember { mutableStateOf(progress.running) }
    LaunchedEffect(progress.running) {
        if (!progress.running) dataVersion++
        if (progress.running) search.analysisDone = false
        else if (wasRunning) search.analysisDone = true
        wasRunning = progress.running
    }
    val entries = remember(dataVersion, tab) { Library.load(context) }

    BackHandler(enabled = page != Page.TABS) { page = page.parent() }
    BackHandler(enabled = search.sheetOpen) { search.sheetOpen = false }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Page)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        when (page) {
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
            Page.LEVEL -> LevelPage(onBack = { page = Page.SETTINGS })
            Page.HISTORY -> AnalysisHistoryPage(
                // The history is opened from the Photos tab: its arrow goes back there.
                onBack = { page = Page.TABS },
                onOpen = {
                    openedRun = it
                    page = Page.RUN
                },
            )
            Page.RUN -> AnalysisDetailPage(name = openedRun, onBack = { page = Page.HISTORY })
            Page.TABS -> {
                val content = Modifier.weight(1f)
                if (tab == 0) {
                    SearchScreen(
                        state = search,
                        entries = entries,
                        removalsVersion = removalsVersion,
                        onRemovalsChanged = { removalsVersion++ },
                        onSettings = { page = Page.SETTINGS },
                        onFilters = { search.sheetOpen = true },
                        modifier = content,
                    )
                } else {
                    PhotosScreen(
                        entries = entries,
                        onSettings = { page = Page.SETTINGS },
                        onHistory = { page = Page.HISTORY },
                        onAddFolders = {
                            storageBack = Page.TABS
                            page = Page.STORAGE
                        },
                        modifier = content,
                    )
                }
                if (tab == 0) AnalysisBanner(
                    notice = search.analysisDone,
                    onOpen = {
                        search.analysisDone = false
                        tab = 1
                    },
                )
                BottomTabs(selected = tab, onSelect = { tab = it })
            }
        }
    }
    FilterSheet(
        state = search,
        entries = entries,
        visible = search.sheetOpen,
        onClose = { search.sheetOpen = false },
    )
    }
}

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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.chatlab.ui.BottomTabs
import com.example.chatlab.ui.Palette

/**
 * The first screen: while the access, the chatlab folder or its models are missing, the setup page
 * is shown instead of the tabs, so the search never says "Aucune photo" for a missing access.
 */
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var setupVersion by remember { mutableIntStateOf(0) }
    // null while the checks run in the background: nothing is shown yet.
    val missing by produceState<List<SetupStep>?>(initialValue = null, setupVersion) {
        value = withContext(Dispatchers.IO) { StartupSetup.missing(context) }
    }
    val steps = missing
    // Once the setup has started in this session, its last card stays until "Suivant" is pressed.
    var setupInProgress by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(steps) { if (steps != null && steps.isNotEmpty()) setupInProgress = true }
    when {
        steps == null -> Unit
        steps.isNotEmpty() -> StartupSetupPage(missing = steps, onChanged = { setupVersion++ })
        setupInProgress -> StartupSetupPage(missing = steps, onChanged = { setupVersion++ }, onNext = { setupInProgress = false })
        else -> AppContent()
    }
}

/** The app once set up: two tabs (Rechercher, Photos) and the separate pages. */
@Composable
private fun AppContent() {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by remember { mutableStateOf(Page.TABS) }
    // Where we came from to choose folders: the Photos tab, or the settings.
    var storageBack by remember { mutableStateOf(Page.TABS) }
    var addBack by remember { mutableStateOf(Page.TABS) }
    // The analysis file opened from the history (its name in ChatLab/analyses).
    var openedRun by remember { mutableStateOf("") }
    val search = remember { SearchUiState() }
    // Increase when the library or the removals change, to read again what is saved.
    var dataVersion by remember { mutableIntStateOf(0) }
    val progress by AnalysisProgress.state.collectAsState()


    // The notice "Analyse terminée" appears only when a run ends while the app is open.
    var wasRunning by remember { mutableStateOf(progress.running) }
    LaunchedEffect(progress.running) {
        if (!progress.running) dataVersion++
        if (progress.running) search.analysisDone = false
        else if (wasRunning) search.analysisDone = true
        wasRunning = progress.running
    }
    // The library is read in the background, once per change of the data: not on each change of tab, which
    // used to freeze the screen while the whole file was parsed.
    // The search reads the compact store, not library.json: the store is read by parts, so this does not
    // depend on the memory free at the start of the app.
    val libraryCache = remember {
        VersionedCache {
            val store = CompactLibrary(context)
            try {
                store.loadAll()
            } finally {
                store.close()
            }
        }
    }
    // null until the library is read: "still loading" is not the same as "no photo yet".
    // The whole library is read when the data changes as a whole. During an analysis, the photos just saved
    // are added to the library in memory: the search follows them without reading everything again.
    val library by produceState<List<LibraryEntry>?>(initialValue = null, dataVersion) {
        value = withContext(Dispatchers.IO) { libraryCache.get(dataVersion).also { LiveLibrary.replaceAll(it) } }
    }
    val liveVersion by LiveLibrary.changes.collectAsState()
    val loaded = library != null
    val entries = remember(loaded, liveVersion) { if (loaded) LiveLibrary.snapshot() else emptyList() }
    val libraryLoading = !loaded

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
                dataVersion = dataVersion,
                onBack = { page = Page.TABS },
                onWiped = {
                    search.reset()
                    dataVersion++
                },
            )
            Page.STORAGE -> StorageChooser(
                onDone = {
                    dataVersion++
                    page = storageBack
                },
                onBack = { page = storageBack },
            )
            Page.ADD_FOLDER -> AddFolderPage(
                onBack = {
                    dataVersion++
                    page = addBack
                },
            )
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
                        loading = libraryLoading,
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
                        onAddMore = {
                            addBack = Page.TABS
                            page = Page.ADD_FOLDER
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

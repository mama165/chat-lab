package com.example.chatlab

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.Switch
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.draw.alpha
import com.example.chatlab.ui.firstlaunch.RecognitionStep
import kotlinx.coroutines.delay
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.documentfile.provider.DocumentFile
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.chatlab.ui.Palette
import com.example.chatlab.ui.PrimaryButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment

/** What ChatLab needs before it can search, in the order the setup page shows them. */
enum class SetupStep { ACCESS, CHATLAB, MODELS, PHOTOS }

/** The checks made at each launch: access to the files, the chatlab folder, and its models subfolder. */
object StartupSetup {
    /** The subfolders ChatLab writes to, created in the chatlab folder when they are missing. */
    val WRITE_FOLDERS = listOf("analyses", "diagnostics", "snapshots")

    /** The steps still missing, in order. Empty when the app can be used. */
    fun missing(context: Context): List<SetupStep> {
        val chatlabChosen = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_FOLDER, null) != null
        if (chatlabChosen) ensureWriteFolders(context)
        val modelsFound = chatlabChosen && modelsFolderFound(context)
        val photosChosen = Sources.read(context).isNotEmpty()
        return missingSteps(StorageAccess.granted(), chatlabChosen, modelsFound, photosChosen)
    }

    /** The chatlab folder that was chosen, if there is one. */
    private fun root(context: Context): DocumentFile? {
        val uri = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)
            ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(uri))?.let { ModelFiles.chatlabDir(it) }
    }

    /** Creates the subfolders ChatLab writes to, if they are not there yet (an app restart keeps them). */
    private fun ensureWriteFolders(context: Context) {
        val chatlab = root(context) ?: return
        for (name in WRITE_FOLDERS) {
            if (chatlab.findFile(name)?.isDirectory != true) chatlab.createDirectory(name)
        }
    }

    /** What the chatlab folder holds, for the setup page: each item with whether it is there. */
    fun inventory(context: Context): List<Pair<String, Boolean>> {
        val chatlab = root(context)
        return (listOf("models") + WRITE_FOLDERS).map { name ->
            name to (chatlab?.findFile(name)?.isDirectory == true)
        }
    }

    /** True if the chatlab folder holds a readable models subfolder. */
    private fun modelsFolderFound(context: Context): Boolean = try {
        ModelFiles.folder(context)
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * The rule, without Android: a missing access or folder is listed. Without the chatlab folder,
 * its models cannot be checked, so they count as missing too. At least one folder of photos is
 * needed too: nothing can be searched before the photos have been walked through.
 */
internal fun missingSteps(
    access: Boolean,
    chatlabChosen: Boolean,
    modelsFound: Boolean,
    photosChosen: Boolean,
): List<SetupStep> = buildList {
    if (!access) add(SetupStep.ACCESS)
    if (!chatlabChosen) add(SetupStep.CHATLAB)
    if (!modelsFound) add(SetupStep.MODELS)
    if (!photosChosen) add(SetupStep.PHOTOS)
}

/**
 * The stage shown in the stepper of the first-launch view: 0 access, 1 workspace (chatlab folder and
 * models), 2 photos, 3 when everything is there.
 */
internal fun setupStage(missing: List<SetupStep>): Int = when {
    SetupStep.ACCESS in missing -> 0
    SetupStep.CHATLAB in missing || SetupStep.MODELS in missing -> 1
    SetupStep.PHOTOS in missing -> 2
    else -> 3
}

/**
 * How long a card stays on screen before the next one comes, as in view 1.1: after the workspace is
 * found the explorer and the checks are shown (2.6 s), after the photos the last card comes (1.6 s).
 */
internal fun revealDelayMs(shownStage: Int): Long = when (shownStage) {
    1 -> 2600L
    2 -> 1600L
    else -> 0L
}

/**
 * The first screen when a step is missing, in the layout of the first-launch view: the stepper, then one
 * card at a time, sliding in from the right as in the view. [onNext] is given only once everything is there,
 * for the last card "Tout est prêt" with "Suivant".
 */
@Composable
fun StartupSetupPage(missing: List<SetupStep>, onChanged: () -> Unit, onNext: (() -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnChanged by rememberUpdatedState(onChanged)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentOnChanged()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val pickers = rememberFolderPickers(onChanged = onChanged)
    val chatlab = remember(missing) { folderName(context, KEY_FOLDER) }
    val found by produceState(initialValue = emptyList<Pair<String, Boolean>>(), missing) {
        value = withContext(Dispatchers.IO) { StartupSetup.inventory(context) }
    }
    val photoFolder by produceState<String?>(initialValue = null, missing) {
        value = withContext(Dispatchers.IO) { Sources.read(context).firstOrNull()?.name }
    }
    val stage = setupStage(missing)
    // The card on screen follows the real stage with a delay, so the explorer and the checks can be seen.
    var shown by remember { mutableIntStateOf(stage) }
    LaunchedEffect(stage) {
        if (stage > shown) {
            delay(revealDelayMs(shown))
            shown = stage
        } else {
            shown = stage
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Premiers pas", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
        Stepper(stage = shown)
        AnimatedContent(
            targetState = shown,
            transitionSpec = {
                (slideInHorizontally(tween(500)) { it * 11 / 10 } + fadeIn(tween(500))) togetherWith
                    (slideOutHorizontally(tween(500)) { -it * 11 / 10 } + fadeOut(tween(500)))
            },
            label = "setup card",
        ) { current ->
            when {
                current == 0 -> AccessCard(onOpenSettings = { StorageAccess.openSettings(context) })
                current == 1 -> WorkspaceCard(
                    missing = missing,
                    chatlab = chatlab,
                    found = found,
                    onChoose = pickers.chooseChatlab,
                )
                current == 2 -> RecognitionStep(onPickPhotos = pickers.addSource)
                onNext != null -> FinalCard(folder = photoFolder ?: "vos photos", onNext = onNext)
                else -> Unit
            }
        }
    }
}

/** The access card of view 1.1: one line and a switch. The switch opens the settings of the access. */
@Composable
private fun AccessCard(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Field)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Autoriser l'accès à tous les fichiers", fontSize = 15.sp, color = Palette.Ink, modifier = Modifier.weight(1f))
        Switch(checked = false, onCheckedChange = { onOpenSettings() })
    }
}

/**
 * The workspace card of view 1.1: the question and "Mon workspace"; once the models are found, the explorer
 * and the two checks. If the models are not in the folder, view 1.1.1 offers to choose another one.
 */
@Composable
private fun WorkspaceCard(
    missing: List<SetupStep>,
    chatlab: String?,
    found: List<Pair<String, Boolean>>,
    onChoose: () -> Unit,
) {
    val chatlabMissing = SetupStep.CHATLAB in missing
    val modelsMissing = SetupStep.MODELS in missing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Field)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Qui va nous aider à reconnaître vos photos ? 🤖", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
        Text("Choisissez le workspace qui contient le dossier models.", fontSize = 15.sp, color = Palette.Ink, lineHeight = 22.sp)
        when {
            chatlabMissing -> PrimaryButton("Mon workspace", onClick = onChoose)
            modelsMissing -> {
                Text("✕  Modèles introuvables dans ce dossier", fontSize = 15.sp, color = Palette.Ink)
                PrimaryButton("Choisir un autre workspace", onClick = onChoose)
            }
            else -> ExplorerReveal(chatlab = chatlab, found = found)
        }
    }
}

/** "Ouverture de l'explorateur" with its dots, then the two checks of view 1.1, one after the other. */
@Composable
private fun ExplorerReveal(chatlab: String?, found: List<Pair<String, Boolean>>) {
    var checksShown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1200)
        checksShown = true
    }
    Text("Ouverture de l'explorateur", fontSize = 15.sp, color = Palette.Ink)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { index -> RevealDot(index) }
    }
    if (checksShown) {
        Text("✓  Modèles trouvés", fontSize = 15.sp, color = Color.Black)
        Text("✓  Analyses, diagnostics et snapshots prêts", fontSize = 15.sp, color = Color.Black)
    }
}

/** One of the three dots of the explorer, pulsing with a delay of 0.3 s between them. */
@Composable
private fun RevealDot(index: Int) {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 900
                0.2f at 0 using LinearEasing
                1f at 297 using LinearEasing
                0.2f at 900
            },
            initialStartOffset = StartOffset(index * 300),
        ),
        label = "dot alpha",
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .alpha(alpha)
            .background(Color.Black, CircleShape),
    )
}

/** The stepper of the first-launch view: the done stages are filled, the current one is outlined. */
@Composable
private fun Stepper(stage: Int) {
    val labels = listOf("Accès", "Workspace", "Photos")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val done = index < stage
                val current = index == stage
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (done) Palette.Accent else Color.White)
                        .border(1.5.dp, if (done || current) Palette.Accent else Color(0xFFD1D5DB), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (done) "✓" else "${index + 1}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (done) Color.White else if (current) Palette.Accent else Color(0xFF9AA3AF),
                    )
                }
                Text(label, fontSize = 12.sp, color = Palette.Muted)
            }
        }
    }
}

/** "Tout est prêt" (view 1.1): the folder found, three lines, and "Suivant". */
@Composable
private fun FinalCard(folder: String, onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Field)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tout est prêt 🎉", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
        Text("On a trouvé vos photos dans $folder 📸", fontSize = 15.sp, color = Palette.Ink)
        Text("✓  Votre workspace est prêt", fontSize = 15.sp, color = Color.Black)
        Text("✓  $folder est en route pour la recherche 🔍", fontSize = 15.sp, color = Color.Black)
        Text("✓  Vous pouvez chercher une photo par description ✨", fontSize = 15.sp, color = Color.Black)
        PrimaryButton("Suivant", onClick = onNext)
    }
}

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import com.example.chatlab.ui.OutlineButton
import com.example.chatlab.ui.firstlaunch.RecognitionStep
import com.example.chatlab.ui.PrimaryButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
 * The first screen when a step is missing, in place of the tabs, in the layout of the first-launch view:
 * a stepper, one card per stage, then "Tout est prêt" with "Suivant" once everything is there
 * ([onNext] is given only for that last card). It is checked again each time the app comes back to the
 * front, so the tabs open by themselves once everything is there.
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
        Stepper(stage = stage)

        if (stage == 3 && onNext != null) {
            FinalCard(folder = photoFolder ?: "vos photos", onNext = onNext)
        } else {
            SetupCard(
                title = "Autoriser l'accès à tous les fichiers",
                done = SetupStep.ACCESS !in missing,
                detail = "Sans cet accès, ChatLab ne voit que les dossiers choisis un par un. " +
                    "Android ouvre ses réglages : activez « Autoriser l'accès à tous les fichiers », puis revenez ici.",
                action = "Ouvrir les réglages",
                onAction = { StorageAccess.openSettings(context) },
            )
            SetupCard(
                title = "Qui va nous aider à reconnaître vos photos ? 🤖",
                done = SetupStep.CHATLAB !in missing && SetupStep.MODELS !in missing,
                doneLines = found.map { (name, ok) -> name to ok },
                locked = SetupStep.ACCESS in missing,
                detail = "Choisissez le workspace qui contient le dossier models.",
                hint = if (SetupStep.CHATLAB !in missing && SetupStep.MODELS in missing) {
                    "Modèles introuvables dans ce dossier."
                } else {
                    null
                },
                action = if (SetupStep.CHATLAB in missing) "Mon workspace" else "Choisir un autre workspace",
                onAction = pickers.chooseChatlab,
                secondary = if (SetupStep.CHATLAB !in missing && SetupStep.MODELS in missing) {
                    "Vérifier à nouveau" to onChanged
                } else {
                    null
                },
                doneText = "Workspace ${chatlab ?: "chatlab"} : modèles trouvés",
            )
            if (SetupStep.PHOTOS in missing && stage == 2) {
                // The photos walk-through, animated as in the view; the button is usable from the start.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Palette.Field)
                        .padding(18.dp),
                ) {
                    RecognitionStep(onPickPhotos = pickers.addSource)
                }
            } else {
                SetupCard(
                    title = "Où se cachent vos photos ? 🕵️",
                    done = SetupStep.PHOTOS !in missing,
                    doneText = "Dossier de photos choisi",
                    locked = stage < 2,
                    detail = "Pour les retrouver, il faut d’abord les parcourir. Choisissez le dossier où elles se trouvent.",
                    action = "Choisir mes photos",
                    onAction = pickers.addSource,
                )
            }
        }
    }
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
        listOf(
            "Votre workspace est prêt",
            "$folder est en route pour la recherche 🔍",
            "Vous pouvez chercher une photo par description ✨",
        ).forEach { line ->
            Text("✓  $line", fontSize = 15.sp, color = Color.Black)
        }
        PrimaryButton("Suivant", onClick = onNext)
    }
}

@Composable
private fun SetupCard(
    title: String,
    done: Boolean,
    detail: String,
    action: String,
    onAction: () -> Unit,
    doneText: String = "",
    doneLines: List<Pair<String, Boolean>> = emptyList(),
    hint: String? = null,
    secondary: Pair<String, () -> Unit>? = null,
    locked: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (locked) Color(0xFFF7F8FA) else Palette.Field)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (locked) Color(0xFF9AA3AF) else Palette.Ink,
        )
        if (done) {
            if (doneText.isNotEmpty()) Text("✓  $doneText", fontSize = 14.sp, color = Color.Black)
            doneLines.forEach { (name, ok) ->
                Text("${if (ok) "✓" else "✕"}  $name", fontSize = 13.sp, color = if (ok) Color.Black else Palette.Muted)
            }
        } else if (!locked) {
            Text(detail, fontSize = 13.sp, color = Palette.Muted)
            if (hint != null) Text(hint, fontSize = 13.sp, color = Palette.Ink)
            PrimaryButton(action, onClick = onAction)
            if (secondary != null) OutlineButton(secondary.first, onClick = secondary.second)
        }
    }
}

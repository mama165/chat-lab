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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.chatlab.ui.Palette
import com.example.chatlab.ui.PrimaryButton

/** What ChatLab needs before it can search, in the order the setup page shows them. */
enum class SetupStep { ACCESS, CHATLAB, MODELS }

/** The checks made at each launch: access to the files, the chatlab folder, and its models subfolder. */
object StartupSetup {
    /** The steps still missing, in order. Empty when the app can be used. */
    fun missing(context: Context): List<SetupStep> {
        val chatlabChosen = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_FOLDER, null) != null
        val modelsFound = chatlabChosen && modelsFolderFound(context)
        return missingSteps(StorageAccess.granted(), chatlabChosen, modelsFound)
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
 * its models cannot be checked, so they count as missing too.
 */
internal fun missingSteps(access: Boolean, chatlabChosen: Boolean, modelsFound: Boolean): List<SetupStep> = buildList {
    if (!access) add(SetupStep.ACCESS)
    if (!chatlabChosen) add(SetupStep.CHATLAB)
    if (!modelsFound) add(SetupStep.MODELS)
}

/**
 * The first screen when a step is missing, in place of the tabs: it says why each access is needed,
 * and it is checked again each time the app comes back to the front, so the tabs open by themselves
 * once everything is there.
 */
@Composable
fun StartupSetupPage(missing: List<SetupStep>, onChanged: () -> Unit) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Avant de chercher", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
        Text(
            "ChatLab a besoin de trois éléments pour lire vos photos. Les photos restent sur le téléphone et ne sont jamais modifiées.",
            fontSize = 14.sp,
            color = Palette.Muted,
        )
        SetupCard(
            number = 1,
            title = "Accès à tous les fichiers",
            detail = "Sans cet accès, ChatLab ne voit que les dossiers choisis un par un, et ne peut pas lire le dossier chatlab. " +
                "Android ouvre ses réglages : activez « Autoriser l'accès à tous les fichiers », puis revenez ici.",
            done = SetupStep.ACCESS !in missing,
            doneText = "Accès accordé",
            action = "Ouvrir les réglages",
            onAction = { StorageAccess.openSettings(context) },
        )
        SetupCard(
            number = 2,
            title = "Le dossier chatlab",
            detail = "Choisissez le dossier nommé chatlab. ChatLab y enregistre le carnet.",
            done = SetupStep.CHATLAB !in missing,
            doneText = "Choisi : ${chatlab ?: "chatlab"}",
            action = "Choisir le dossier chatlab",
            onAction = pickers.chooseChatlab,
        )
        SetupCard(
            number = 3,
            title = "Le sous-dossier models",
            detail = "Il se trouve dans le dossier chatlab et contient les fichiers des modèles.",
            done = SetupStep.MODELS !in missing,
            doneText = "Trouvé dans le dossier chatlab",
            hint = if (SetupStep.CHATLAB in missing) {
                "Choisissez d'abord le dossier chatlab."
            } else {
                "Introuvable dans le dossier chatlab : vérifiez que le sous-dossier models y est, puis revenez ici."
            },
            action = "Vérifier à nouveau",
            onAction = onChanged,
        )
        Text(
            "Les dossiers de photos à analyser se choisissent ensuite, dans l'onglet Photos.",
            fontSize = 13.sp,
            color = Palette.Muted,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun SetupCard(
    number: Int,
    title: String,
    detail: String,
    done: Boolean,
    doneText: String,
    action: String,
    onAction: () -> Unit,
    hint: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Field)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "$number · $title",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.Ink,
        )
        if (done) {
            Text("$doneText ✓", fontSize = 14.sp, color = Palette.Accent)
        } else {
            Text(detail, fontSize = 13.sp, color = Palette.Muted)
            if (hint != null) Text(hint, fontSize = 13.sp, color = Palette.Ink)
            PrimaryButton(action, onClick = onAction)
        }
    }
}

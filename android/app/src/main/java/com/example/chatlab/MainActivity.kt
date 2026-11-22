package com.example.chatlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.chatlab.ui.theme.AndroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            LibraryStore.saveErrorReport(applicationContext, "Plantage : $error\n\n${error.stackTraceToString()}")
            previousHandler?.uncaughtException(thread, error)
        }
        setContent {
            AndroidTheme {
                MainScreen()
            }
        }
    }
}

const val PREFERENCES = "chatlab"

/** Le dossier chatlab (modèles, carnet, retraits), choisi par l'utilisateur. */
const val KEY_FOLDER = "folder"

/** Le dossier des photos à analyser. */
const val KEY_SOURCE = "source"

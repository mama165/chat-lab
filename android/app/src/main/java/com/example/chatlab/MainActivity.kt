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
        // Diagnostics first: it reads why the app ended last time, and wraps the handler below.
        Diagnostics.onAppStart(this)
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

/** The chatlab folder (models, library, removals), chosen by the user. */
const val KEY_FOLDER = "folder"

/** The folder of the photos to analyse. */
const val KEY_SOURCE = "source"

package com.example.chatlab

import android.content.Context

/** The analysis level chosen in the settings, kept on the phone. */
object LevelStore {
    private const val KEY = "analysis_level"

    fun read(context: Context): AnalysisLevel =
        AnalysisLevel.fromKey(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null))

    fun write(context: Context, level: AnalysisLevel) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, level.key).apply()
    }
}

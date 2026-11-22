package com.example.chatlab

import android.content.Context

/** Les recherches récentes, gardées dans les préférences de l'application. */
object RecentStore {
    private const val KEY = "recent_searches"

    fun read(context: Context): List<String> =
        RecentSearches.decode(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null))

    fun add(context: Context, query: String): List<String> {
        val list = RecentSearches.add(read(context), query)
        write(context, list)
        return list
    }

    fun clear(context: Context) = write(context, emptyList())

    private fun write(context: Context, list: List<String>) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(KEY, RecentSearches.encode(list)).apply()
    }
}

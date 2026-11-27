package com.example.chatlab

import android.content.Context

/** The recent searches, kept in the preferences of the application. */
object RecentStore {
    private const val KEY = "recent_searches"

    fun read(context: Context): List<String> =
        RecentSearches.decode(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null))

    fun add(context: Context, query: String): List<String> {
        val list = RecentSearches.add(read(context), query)
        write(context, list)
        return list
    }

    /** Removes a search from the list kept on the phone: it does not come back after a restart. */
    fun remove(context: Context, query: String): List<String> {
        val list = RecentSearches.remove(read(context), query)
        write(context, list)
        return list
    }

    fun clear(context: Context) = write(context, emptyList())

    private fun write(context: Context, list: List<String>) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(KEY, RecentSearches.encode(list)).apply()
    }
}

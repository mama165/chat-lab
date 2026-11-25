package com.example.chatlab

/**
 * Keeps one item per identifier, in the order of the list. Items with a blank identifier are all kept.
 * By default the first item of an identifier is kept; with [keepLast], the last one (the newest).
 */
internal fun <T> distinctById(items: List<T>, idOf: (T) -> String, keepLast: Boolean = false): List<T> {
    val winner = HashMap<String, Int>()
    items.forEachIndexed { index, item ->
        val id = idOf(item)
        if (id.isBlank()) return@forEachIndexed
        if (keepLast || !winner.containsKey(id)) winner[id] = index
    }
    return items.filterIndexed { index, item ->
        val id = idOf(item)
        id.isBlank() || winner[id] == index
    }
}

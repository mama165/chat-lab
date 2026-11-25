package com.example.chatlab

/**
 * Which folders the chooser shows. A folder whose photos are counted and come to zero is hidden:
 * it has nothing to analyse. A folder not counted yet stays visible until its count is known, and a
 * folder already added stays visible whatever its count.
 */
object ChooserFilter {
    fun visible(path: String, counts: Map<String, Int>, locked: Set<String>): Boolean =
        path in locked || counts[path] != 0
}

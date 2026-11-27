package com.example.chatlab.ui.firstlaunch

/** Un point d'une piste : à l'instant [t] (en secondes), la valeur [v]. */
data class Key(val t: Float, val v: Float)

/**
 * Valeur d'une piste à l'instant [t]. Entre deux points, la valeur est interpolée linéairement,
 * comme les keyframes des vues. Avant le premier point, la valeur du premier point ; après le dernier, celle du dernier.
 */
fun track(t: Float, keys: List<Key>): Float {
    if (keys.isEmpty()) return 0f
    if (t <= keys.first().t) return keys.first().v
    for (i in 0 until keys.size - 1) {
        val a = keys[i]
        val b = keys[i + 1]
        if (t <= b.t) {
            if (b.t == a.t) return b.v
            return a.v + (b.v - a.v) * ((t - a.t) / (b.t - a.t))
        }
    }
    return keys.last().v
}

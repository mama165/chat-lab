package com.example.chatlab.ui.firstlaunch

/**
 * Chronologie de l'étape « Petit tour des photos », reprise de la vue 1.1 (carte 3).
 * Le temps 0 est l'arrivée de la carte. Toutes les valeurs sont en secondes.
 */
object RecognitionTimeline {
    const val PHOTO_COUNT = 4

    /** Début de la chute de la photo [index] : les photos tombent avec 0,35 s d'écart. */
    fun landingStart(index: Int): Float = 0.9f + index * 0.35f

    /** Opacité d'une photo : invisible avant sa chute, visible 0,05 s après son début. */
    fun photoAlpha(t: Float, index: Int): Float {
        val s = landingStart(index)
        return track(t, listOf(Key(s, 0f), Key(s + 0.05f, 1f)))
    }

    /** Décalage vertical d'une photo en dp : elle tombe de -70 à 0 en 0,5 s. */
    fun photoOffsetDp(t: Float, index: Int): Float {
        val s = landingStart(index)
        return track(t, listOf(Key(s, -70f), Key(s + 0.5f, 0f)))
    }

    /** Début de la reconnaissance de la première photo : juste après sa chute. */
    val firstShimmerStart: Float = landingStart(0) + 0.5f

    /** Chaque photo scintille pendant 2 secondes, la deuxième juste après la première. */
    const val SHIMMER_DURATION = 2f

    /** Début de la reconnaissance de la deuxième photo. */
    val secondShimmerStart: Float = firstShimmerStart + SHIMMER_DURATION

    /** Fin de la reconnaissance : la coche apparaît 0,2 s après la fin de la deuxième photo. */
    val checkStart: Float = secondShimmerStart + 2.2f

    /** Luminosité d'une photo qui scintille (1 hors scintillement). Elle alterne 1,6 et 0,65 toutes les 0,2 s. */
    fun shimmerBrightness(t: Float, start: Float): Float {
        if (t < start || t >= start + SHIMMER_DURATION) return 1f
        val keys = mutableListOf<Key>()
        var k = 0
        var time = start
        while (time < start + SHIMMER_DURATION - 1e-4f) {
            keys += Key(time, if (k % 2 == 0) 1.6f else 0.65f)
            k++
            time += 0.2f
        }
        keys += Key(start + SHIMMER_DURATION, 1f)
        return track(t, keys)
    }

    /** Opacité de la coche. */
    fun checkAlpha(t: Float): Float = track(t, listOf(Key(checkStart, 0f), Key(checkStart + 0.2f, 1f)))

    /** Opacité de l'ensemble de l'étape : apparaît en 0,4 s. */
    fun blockAlpha(t: Float): Float = track(t, listOf(Key(0f, 0f), Key(0.4f, 1f)))

    /** Opacité d'un point des trois petits points : ils s'allument en séquence, cycle de 0,9 s, décalés de 0,3 s. */
    fun dotAlpha(t: Float, index: Int): Float {
        val delay = index * 0.3f
        if (t < delay) return 0.2f
        val p = (t - delay) % 0.9f
        return track(p, listOf(Key(0f, 0.2f), Key(0.297f, 1f), Key(0.9f, 0.2f)))
    }
}

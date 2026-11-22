package com.example.chatlab

import kotlin.math.max
import kotlin.math.roundToInt

/** Calculs de la lecture de texte qui ne dépendent ni d'OpenCV ni du modèle. */
object OcrMath {
    /**
     * Décodage CTC : à chaque pas, le symbole le plus probable ; les répétitions et le blanc
     * (index 0) sont ignorés. Renvoie le texte et la confiance moyenne des caractères retenus.
     */
    fun decode(steps: Array<FloatArray>, vocabulary: List<String>): Pair<String, Float> {
        val text = StringBuilder()
        var previous = -1
        var confidence = 0.0
        var kept = 0
        for (step in steps) {
            if (step.isEmpty()) continue
            var best = 0
            for (i in step.indices) if (step[i] > step[best]) best = i
            if (best != 0 && best != previous && best < vocabulary.size) {
                text.append(vocabulary[best])
                confidence += step[best]
                kept++
            }
            previous = best
        }
        return text.toString() to if (kept == 0) 0f else (confidence / kept).toFloat()
    }

    /** Arrondi au multiple de 32 le plus proche, au moins 32, comme l'attend le détecteur. */
    fun roundTo32(value: Double): Int = max(32, (value / 32).roundToInt() * 32)
}

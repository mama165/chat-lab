package com.example.chatlab

import android.util.Log
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Stress test: the real app, the real library and the real models, driven like a person would:
 * types searches (fast, slow, corrected), scrolls the results and taps "plus de photos", for a
 * given number of minutes (argument "minutes", 60 by default). Each step is logged under the tag
 * StressTest. A crash stops the run: the journal and the logcat show what happened before.
 *
 * Run it on the phone, with the app and this test installed (see the message that comes with it).
 */
@RunWith(AndroidJUnit4::class)
class StressTest {
    // No fixtures: the library and the models are the real ones.
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val phrases = listOf(
        "voiture", "chaussures", "plage", "2 personnes", "camion", "France", "Turquie", "gaufre",
        "gouffre", "QR code", "3 personnes", "un coucher de soleil sur la mer",
        "deux personnes qui mangent à table", "une facture ou un document",
        "un paysage de montagne enneigée", "un chien dans l'herbe", "une photo prise de nuit",
        "2 femmes qui sourient",
    )

    @Test
    fun searchesUnderLoad() {
        val minutes = InstrumentationRegistry.getArguments().getString("minutes")?.toLongOrNull() ?: 60L
        val started = System.currentTimeMillis()
        val deadline = started + minutes * 60_000L
        val random = Random(System.nanoTime())
        var step = 0
        while (System.currentTimeMillis() < deadline) {
            step++
            val phrase = phrases.random(random)
            val error = runCatching { oneSearch(phrase, random) }.exceptionOrNull()
            val elapsed = (System.currentTimeMillis() - started) / 1000
            Log.i(TAG, "step=$step elapsedS=$elapsed phrase=\"$phrase\" error=${error?.javaClass?.simpleName ?: "none"}")
        }
        Log.i(TAG, "end steps=$step minutes=$minutes")
    }

    private fun oneSearch(phrase: String, random: Random) {
        val field = compose.onNode(hasSetTextAction())
        field.performTextReplacement("")
        if (random.nextInt(3) == 0) {
            // Typed fast, then corrected: the search starts again from the new text.
            field.performTextInput(phrase.take(phrase.length / 2))
            Thread.sleep(random.nextLong(100, 600))
            field.performTextReplacement(phrase)
        } else {
            field.performTextInput(phrase)
        }
        Thread.sleep(random.nextLong(1500, 4000))
        repeat(random.nextInt(0, 4)) {
            compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp() }
            Thread.sleep(700)
        }
        if (random.nextInt(4) == 0) {
            val more = compose.onAllNodesWithText("Voir des photos", substring = true)
            if (more.fetchSemanticsNodes().isNotEmpty()) {
                more.onFirst().performClick()
                Thread.sleep(2000)
            }
        }
    }

    private companion object {
        const val TAG = "StressTest"
    }
}

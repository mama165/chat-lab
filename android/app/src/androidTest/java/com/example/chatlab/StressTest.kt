package com.example.chatlab

import android.util.Log
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Stress test: the real app, the real library and the real models, driven like a person would.
 *
 * Three rhythms are mixed at random:
 * - calm: a search, a pause of a few seconds, sometimes scrolling and "plus de photos";
 * - reading: a search, then a long pause (6 to 12 s), as if the results were looked at;
 * - burst: about ten searches in three seconds (type a part, delete, type again), to pile them up.
 *
 * The argument "minutes" sets the duration (3 by default). Each step is logged under the tag
 * StressTest. A crash stops the run: the logcat and the journal show what happened before.
 */
@RunWith(AndroidJUnit4::class)
class StressTest {
    // No fixtures: the library and the models are the real ones.
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val phrases = listOf(
        // Things in the library: food, objects, places.
        "gâteau", "gaufre", "chaussures", "fleurs", "fenêtre", "porte", "tiroir", "meuble",
        "cuisine", "jardin", "table", "canapé", "lampe", "vaisselle", "plante verte",
        // Shops and brands.
        "Ikea", "Leclerc",
        // Vehicles, places, people.
        "voiture", "camion", "plage", "France", "Turquie", "2 personnes", "3 personnes",
        "2 femmes qui sourient", "bébé", "un chien dans l'herbe",
        // Descriptions.
        "un coucher de soleil sur la mer", "deux personnes qui mangent à table",
        "un gâteau d'anniversaire", "des fleurs sur une table", "une fenêtre ouverte",
        "une porte en bois", "un meuble blanc dans une pièce claire", "une photo prise de nuit",
    )

    /**
     * Slow and visible: the given words (argument "words", comma-separated), each written at once, then
     * half of it, then cleared, with a pause (argument "pause", in ms) after each change so that the
     * results can be seen and read in the logcat.
     */
    @Test
    fun wordsSlowly() {
        val args = InstrumentationRegistry.getArguments()
        val words = args.getString("words")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return
        val pause = args.getString("pause")?.toLongOrNull() ?: 10_000L
        val minutes = args.getString("minutes")?.toLongOrNull() ?: 3L
        val deadline = System.currentTimeMillis() + minutes * 60_000L
        val field = compose.onNode(hasSetTextAction())
        val expect = args.getString("expect")?.toIntOrNull() ?: 1
        val failures = mutableListOf<String>()
        var round = 0
        while (System.currentTimeMillis() < deadline) {
            round++
            for (word in words) {
                field.performTextReplacement(word)
                // Checks that photos appear for the word: at least "expect" thumbnails, within 30 s.
                val started = System.currentTimeMillis()
                val shown = runCatching {
                    compose.waitUntil(30_000L) { photoTiles() >= expect }
                }.isSuccess
                val tiles = photoTiles()
                val ms = System.currentTimeMillis() - started
                val ok = shown && tiles >= expect
                Log.i(TAG, "check round=$round word=\"$word\" tiles=$tiles expected=$expect ms=$ms ok=$ok")
                if (!ok) failures += "\"$word\" (round $round): $tiles photos, $expect attendues"
                Thread.sleep(pause)
                Log.i(TAG, "visible round=$round word=\"$word\" written=full pauseMs=$pause")
                field.performTextReplacement(word.take(word.length / 2))
                Thread.sleep(pause / 2)
                Log.i(TAG, "visible round=$round word=\"$word\" written=half pauseMs=${pause / 2}")
                field.performTextReplacement("")
                Thread.sleep(pause / 2)
            }
        }
        Log.i(TAG, "visible end rounds=$round failures=${failures.size}")
        assertTrue("Aucune photo affichée pour : ${failures.joinToString("; ")}", failures.isEmpty())
    }

    /** Thumbnails on screen: each one is described by the name of its photo. */
    private fun photoTiles(): Int =
        listOf(".jpg", ".jpeg", ".png", ".heic", ".webp").sumOf { extension ->
            compose.onAllNodes(hasContentDescription(extension, substring = true, ignoreCase = true))
                .fetchSemanticsNodes().size
        }

    @Test
    fun searchesUnderLoad() {
        val minutes = InstrumentationRegistry.getArguments().getString("minutes")?.toLongOrNull() ?: 3L
        val started = System.currentTimeMillis()
        val deadline = started + minutes * 60_000L
        val random = Random(System.nanoTime())
        var step = 0
        while (System.currentTimeMillis() < deadline) {
            step++
            val rhythm = when (random.nextInt(10)) {
                in 0..1 -> "burst"
                in 2..5 -> "calm"
                else -> "reading"
            }
            val phrase = phrases.random(random)
            val error = runCatching { oneStep(rhythm, phrase, random) }.exceptionOrNull()
            val elapsed = (System.currentTimeMillis() - started) / 1000
            Log.i(TAG, "step=$step elapsedS=$elapsed rhythm=$rhythm phrase=\"$phrase\" error=${error?.javaClass?.simpleName ?: "none"}")
        }
        Log.i(TAG, "end steps=$step minutes=$minutes")
    }

    private fun oneStep(rhythm: String, phrase: String, random: Random) {
        when (rhythm) {
            "burst" -> burst(random)
            else -> {
                typeOnce(phrase, random)
                Thread.sleep(if (rhythm == "reading") random.nextLong(6_000, 12_000) else random.nextLong(1_500, 4_000))
                if (rhythm == "calm") scrollAndMore(random)
            }
        }
    }

    /** One search: sometimes typed fast then corrected, sometimes typed at once. */
    private fun typeOnce(phrase: String, random: Random) {
        val field = compose.onNode(hasSetTextAction())
        field.performTextReplacement("")
        if (random.nextInt(3) == 0) {
            field.performTextInput(phrase.take(phrase.length / 2))
            Thread.sleep(random.nextLong(100, 600))
            field.performTextReplacement(phrase)
        } else {
            field.performTextInput(phrase)
        }
    }

    /** About ten searches in three seconds: a part of a word, cleared, then another, with no pause. */
    private fun burst(random: Random) {
        val field = compose.onNode(hasSetTextAction())
        repeat(10) {
            val word = phrases.random(random)
            field.performTextReplacement(word.take(random.nextInt(1, word.length + 1)))
            Thread.sleep(random.nextLong(80, 250))
            field.performTextReplacement("")
            Thread.sleep(random.nextLong(60, 150))
        }
        field.performTextReplacement(phrases.random(random))
        Thread.sleep(random.nextLong(1_000, 2_000))
    }

    private fun scrollAndMore(random: Random) {
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

package com.example.chatlab

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real text model many times on the phone, as successive searches do, and logs the native
 * memory before and after each call. Needs the model folder already chosen in the app (on the phone),
 * and reads the logs with: adb logcat -s TextMemory
 *
 * Not part of the normal test run: it takes several minutes and needs the real model.
 */
@RunWith(AndroidJUnit4::class)
class TextMemoryTest {
    @Test
    fun repeatedSearchesKeepNativeMemoryStable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The known-vectors file would answer without the model: remove it so the model is really used.
        File(context.filesDir, "query_vectors.json").delete()

        val words = listOf(
            "une rose rouge dans un jardin", "deux personnes devant la mer", "un chien sur la plage",
            "une facture scannée", "un coucher de soleil", "une voiture bleue dans la rue",
            "trois enfants qui jouent", "une montagne enneigée", "une tasse de café",
            "un paysage de forêt en automne",
        )
        val rounds = 30
        Log.i(TAG, "start rounds=$rounds words=${words.size}")
        var first = -1L
        var last = -1L
        repeat(rounds) { round ->
            words.forEachIndexed { index, text ->
                val before = Debug.getNativeHeapAllocatedSize() / MB
                val started = System.currentTimeMillis()
                // A different sentence each round, otherwise the cache would answer and nothing would be encoded.
                TextModels.vectorFor(context, "$text ($round)")
                val ms = System.currentTimeMillis() - started
                val after = Debug.getNativeHeapAllocatedSize() / MB
                Log.i(TAG, "round=$round index=$index beforeMb=$before afterMb=$after ms=$ms")
                if (first < 0) first = before
                last = before
            }
        }
        Log.i(TAG, "end first=$first last=$last")
    }

    private companion object {
        const val TAG = "TextMemory"
        const val MB = 1024L * 1024L
    }
}

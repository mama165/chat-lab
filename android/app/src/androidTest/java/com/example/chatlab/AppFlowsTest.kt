package com.example.chatlab

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * Full flows on the emulator, as a person would do them: type, filter, choose,
 * remove, restore, open the settings, erase everything. Each test starts from a library of 5 photos.
 */
@RunWith(AndroidJUnit4::class)
class AppFlowsTest {
    /** Puts the test library in place before the screen opens. */
    private val fixtures = object : ExternalResource() {
        override fun before() {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            target.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
            for (name in listOf("retraits.json", "compte_rendu.txt")) File(target.filesDir, name).delete()
            assets.open("query_vectors.json").use { input -> File(target.filesDir, "query_vectors.json").outputStream().use { input.copyTo(it) } }
            File(target.filesDir, "library.json").writeText(datedLibrary(assets.open("library.json").use { String(it.readBytes()) }))
        }
    }

    /**
     * Gives each photo of the library a folder, a type and a shooting time relative to
     * now, so that the date filters are tested whatever the day of the run.
     */
    private fun datedLibrary(json: String): String {
        val now = System.currentTimeMillis()
        val day = 24 * 3600 * 1000L
        val facts = mapOf(
            "affiche_soldes.png" to Triple("Camera", "image/png", now - 30 * 60 * 1000L),
            "facture_fictive.png" to Triple("Documents", "image/png", now - 3 * day),
            "mot_herbe.png" to Triple("Camera", "image/webp", now - 20 * day),
            "panneau_sortie.png" to Triple("Documents", "image/png", now - 200 * day),
            "ticket_wifi.png" to Triple("Camera", "image/png", now - 40 * day),
        )
        val array = org.json.JSONArray(json)
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val (folder, mime, taken) = facts.getValue(item.getString("name"))
            item.put("folder", folder).put("mime", mime).put("taken_ms", taken)
        }
        return array.toString()
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(fixtures).around(compose)

    private fun type(text: String) = compose.onNode(hasSetTextAction()).performTextInput(text)

    private fun waitFor(text: String, timeout: Long = 10_000) = compose.waitUntil(timeout) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun back() = compose.onNodeWithContentDescription("Retour").performClick()

    /** "Rechercher" is both the title and the bottom tab. */
    private fun onSearchScreen() = compose.onAllNodesWithText("Rechercher").onFirst().assertExists()

    @Test
    fun emptySearchShowsTheLibrary() {
        onSearchScreen()
        compose.onNodeWithText("5 photos, dans le carnet").assertExists()
        compose.onNodeWithContentDescription("Réglages").assertExists()
        compose.onNodeWithContentDescription("Filtres").assertExists()
    }

    @Test
    fun aWordFindsThePhotoWhoseTextContainsIt() {
        type("facture")
        compose.onNodeWithText("Texte lu").assertExists()
        compose.onNodeWithContentDescription("facture_fictive.png").assertExists()
        compose.onNodeWithText("1 résultat").assertExists()
    }

    @Test
    fun aDescriptionFindsResemblingPhotos() {
        type("une facture")
        waitFor("Ressemblantes")
    }

    @Test
    fun tappingSelectsAndTheBarCancels() {
        type("facture")
        compose.onNodeWithContentDescription("facture_fictive.png").performClick()
        compose.onNodeWithText("1 sélectionnée").assertExists()
        compose.onNodeWithText("Retirer", substring = true).assertExists()
        compose.onNodeWithContentDescription("Annuler la sélection").performClick()
        compose.onNodeWithText("1 sélectionnée").assertDoesNotExist()
        onSearchScreen()
    }

    @Test
    fun removedPhotoLeavesTheSearchAndCanBeRestored() {
        type("facture")
        compose.onNodeWithContentDescription("facture_fictive.png").performClick()
        compose.onNodeWithText("Retirer", substring = true).performClick()
        waitFor("Aucune photo ne correspond")

        compose.onNodeWithContentDescription("Réglages").performClick()
        compose.onNodeWithText("Non classifiées").performClick()
        compose.onNodeWithText("« facture » (1)").assertExists()
        compose.onNodeWithText("Rétablir").performClick()
        compose.onNodeWithText("Aucune photo retirée de vos recherches.").assertExists()
        back()
        back()
        compose.onNodeWithContentDescription("facture_fictive.png").assertExists()
    }

    @Test
    fun aFilterShowsATagThatRemovesIt() {
        type("facture")
        compose.onNodeWithContentDescription("Filtres").performClick()
        compose.onNodeWithText("Lieu").assertExists()
        compose.onNodeWithText("Intérieur").performClick()
        back()
        // The test photos have no known place: the Intérieur filter drops them all.
        compose.onNodeWithText("Intérieur ✕").assertExists()
        compose.onNodeWithText("1").assertExists()
        compose.onNodeWithText("Essayez de retirer un filtre.").assertExists()
        compose.onNodeWithText("Intérieur ✕").performClick()
        compose.onNodeWithContentDescription("facture_fictive.png").assertExists()
    }

    @Test
    fun twoFiltersOfferReinitialiser() {
        type("facture")
        compose.onNodeWithContentDescription("Filtres").performClick()
        compose.onNodeWithText("Intérieur").performScrollTo().performClick()
        compose.onNodeWithText("7 derniers jours").performScrollTo().performClick()
        back()
        compose.onNodeWithText("Intérieur ✕").assertExists()
        compose.onNodeWithText("7 derniers jours ✕").assertExists()
        // Two active filters: the Réinitialiser link appears next to the number of results.
        compose.onNode(hasText("Réinitialiser") and hasClickAction()).performClick()
        compose.onNodeWithText("Intérieur ✕").assertDoesNotExist()
        compose.onNodeWithText("7 derniers jours ✕").assertDoesNotExist()
    }

    @Test
    fun filtersPageResetsItself() {
        compose.onNodeWithContentDescription("Filtres").performClick()
        compose.onNodeWithText("Extérieur").performClick()
        compose.onNodeWithText("Réinitialiser").performClick()
        back()
        compose.onNodeWithText("Extérieur ✕").assertDoesNotExist()
    }

    @Test
    fun aSearchBecomesARecentSearch() {
        type("facture")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNodeWithContentDescription("Effacer la recherche").performClick()
        compose.onNodeWithText("Récentes").assertExists()
        compose.onNodeWithText("facture").performClick()
        compose.onNodeWithContentDescription("facture_fictive.png").assertExists()
    }

    @Test
    fun settingsOpenAndCloseWithTheArrow() {
        compose.onNodeWithContentDescription("Réglages").performClick()
        compose.onNodeWithText("Dossiers").assertExists()
        compose.onNodeWithText("Outils de test").assertExists()
        back()
        onSearchScreen()
    }

    @Test
    fun toutEffacerEmptiesTheLibrary() {
        compose.onNodeWithContentDescription("Réglages").performClick()
        compose.onNodeWithText("Tout effacer").performClick()
        waitFor("5 photos analysées")
        compose.onNode(hasText("Tout effacer") and hasClickAction()).performClick()
        waitFor("Rien à effacer")
        back()
        back()
        compose.onNodeWithText("Aucune photo analysée pour l'instant.").assertExists()
    }

    @Test
    fun photosTabAsksForTheFoldersFirst() {
        compose.onNodeWithText("Photos").performClick()
        compose.onNodeWithText("Pour commencer, choisissez le dossier chatlab, puis les dossiers de photos.").assertExists()
        compose.onNodeWithText("Rechercher").performClick()
        compose.onNodeWithText("5 photos, dans le carnet").assertExists()
    }

    @Test
    fun aDatePresetKeepsOrDropsPhotosByTheirShootingTime() {
        type("facture")
        compose.onNodeWithContentDescription("Filtres").performClick()
        // The invoice is three days old: it is not in the last 24 hours.
        compose.onNodeWithText("24 dernières heures").performClick()
        back()
        compose.onNodeWithText("24 dernières heures ✕").assertExists()
        compose.onNodeWithText("Essayez de retirer un filtre.").assertExists()
        compose.onNodeWithText("24 dernières heures ✕").performClick()
        compose.onNodeWithContentDescription("facture_fictive.png").assertExists()
        // ... but it is in the last 7 days.
        compose.onNodeWithContentDescription("Filtres").performClick()
        compose.onNodeWithText("7 derniers jours").performClick()
        back()
        compose.onNodeWithText("7 derniers jours ✕").assertExists()
        compose.onNodeWithContentDescription("facture_fictive.png").assertExists()
    }

    @Test
    fun theFolderChipsCountThePhotosOfEachFolder() {
        compose.onNodeWithContentDescription("Filtres").performClick()
        compose.onNodeWithText("Camera 3").assertExists()
        compose.onNodeWithText("Documents 2").assertExists()
        compose.onNodeWithText("PNG 4").assertExists()
        compose.onNodeWithText("WebP 1").assertExists()
    }

    @Test
    fun aFolderFilterKeepsOnlyItsPhotos() {
        type("facture")
        compose.onNodeWithContentDescription("Filtres").performClick()
        // The folder chip is at the bottom of the page: scroll to it before touching it.
        compose.onNodeWithText("Documents", substring = true).performScrollTo().performClick()
        back()
        compose.onNodeWithText("Documents ✕").assertExists()
        compose.onNodeWithContentDescription("facture_fictive.png").assertExists()
        compose.onNodeWithText("Documents ✕").performClick()
        compose.onNodeWithText("Documents ✕").assertDoesNotExist()
    }

    @Test
    fun theDatesPageOpensAndOffersTheCalendar() {
        compose.onNodeWithContentDescription("Filtres").performClick()
        compose.onNodeWithText("Choisir des dates…").performClick()
        compose.onNodeWithContentDescription("Mois précédent").assertExists()
        compose.onNodeWithContentDescription("Mois suivant").assertExists()
        compose.onNodeWithText("Choisissez des dates").assertExists()
        compose.onNodeWithText("Ce mois").performClick()
        compose.onNode(hasText("Valider", substring = true)).assertExists()
        back()
        compose.onNodeWithText("Filtres").assertExists()
    }

    @Test
    fun anExactDateRangeAppliesFromTheCalendar() {
        type("facture")
        compose.onNodeWithContentDescription("Filtres").performClick()
        compose.onNodeWithText("Choisir des dates…").performClick()
        // Today: the invoice, from three days ago, is not in it.
        compose.onNodeWithText("Aujourd'hui").performClick()
        compose.onNode(hasText("Valider", substring = true) and hasClickAction()).performClick()
        back()
        compose.onNodeWithText("Essayez de retirer un filtre.").assertExists()
    }
}

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
 * Parcours complets sur l'émulateur, comme le ferait une personne : taper, filtrer, choisir,
 * retirer, rétablir, ouvrir les réglages, tout effacer. Chaque test part d'un carnet de 5 photos.
 */
@RunWith(AndroidJUnit4::class)
class AppFlowsTest {
    /** Met le carnet de test en place avant que l'écran ne s'ouvre. */
    private val fixtures = object : ExternalResource() {
        override fun before() {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            target.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
            for (name in listOf("retraits.json", "compte_rendu.txt")) File(target.filesDir, name).delete()
            for (name in listOf("library.json", "query_vectors.json")) {
                assets.open(name).use { input -> File(target.filesDir, name).outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(fixtures).around(compose)

    private fun type(text: String) = compose.onNode(hasSetTextAction()).performTextInput(text)

    private fun waitFor(text: String, timeout: Long = 10_000) = compose.waitUntil(timeout) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun back() = compose.onNodeWithContentDescription("Retour").performClick()

    /** « Rechercher » est à la fois le titre et l'onglet du bas. */
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
        // Les photos de test n'ont pas de lieu connu : le filtre Intérieur les écarte toutes.
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
        compose.onNodeWithText("Intérieur").performClick()
        compose.onNodeWithText("Ce mois").performClick()
        back()
        compose.onNodeWithText("2").assertExists()
        compose.onNodeWithText("Réinitialiser").performClick()
        compose.onNodeWithText("Intérieur ✕").assertDoesNotExist()
        compose.onNodeWithText("Ce mois ✕").assertDoesNotExist()
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
        compose.onNodeWithText("Pour commencer, choisissez deux dossiers.").assertExists()
        compose.onNodeWithText("Rechercher").performClick()
        compose.onNodeWithText("5 photos, dans le carnet").assertExists()
    }
}

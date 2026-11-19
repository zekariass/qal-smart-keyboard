package com.qalsmartkeyboard.app.wordart

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onFirst
import com.qalsmartkeyboard.ime.wordart.WordArtCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** The companion app's Word Art screen on the JVM (Robolectric): it renders the engine's styles and its Copy and Share work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h3000dp")
class WordArtScreenTest {
    @get:Rule val compose = createComposeRule()

    private val copies = ArrayList<String>()
    private val shares = ArrayList<String>()
    private var backs = 0

    private fun show(
        text: String = "Abebe",
        category: WordArtCategory = WordArtCategory.POPULAR,
        language: String = "en",
        greeting: Boolean = false,
    ) = compose.setContent {
        WordArtScreen(
            text = text, onText = {}, category = category, onCategory = {}, language = language, onLanguage = {},
            greeting = greeting, onGreeting = {},
            onCopy = { copies += it }, onShare = { shares += it }, onBack = { backs++ },
        )
    }

    @Test fun rendersTheTitleFieldCategoriesAndPopularStyles() {
        show()
        compose.onNodeWithText("Word Art").assertExists()
        compose.onNodeWithText("Your text").assertExists()
        // The chips are a lazy row: the ones that fit the width are composed.
        for (c in listOf("Popular", "Birthday", "Love")) compose.onNodeWithText(c).assertExists()
        compose.onNodeWithText("✨ Abebe ✨", useUnmergedTree = true).assertExists()
    }

    @Test fun copyAndShareSendTheStyledText() {
        show()
        compose.onNodeWithContentDescription("Copy Sparkles").assertIsEnabled().performClick()
        assertEquals(listOf("✨ Abebe ✨"), copies)
        compose.onNodeWithContentDescription("Share Sparkles").performScrollTo().performClick()
        assertEquals(listOf("✨ Abebe ✨"), shares)
    }

    @Test fun previewsAreReadAsPlainTextAndStyleName() {
        show(category = WordArtCategory.LETTERS)
        compose.onNodeWithContentDescription("Bold: Abebe").assertExists()
        compose.onNodeWithContentDescription("Fraktur: Abebe").assertExists()
    }

    @Test fun latinOnlyStylesAreDisabledForGeezText() {
        show(text = "ሰላም", category = WordArtCategory.LETTERS)
        compose.onNodeWithContentDescription("Bold: ሰላም, Latin letters only, unavailable").assertExists()
        compose.onNodeWithContentDescription("Copy Bold").assertIsNotEnabled()
        // Spacing works for Ge'ez.
        compose.onNodeWithContentDescription("Copy Spaced letters").assertIsEnabled().performClick()
        assertEquals(listOf("ሰ ላ ም"), copies)
    }

    @Test fun emptyTextShowsASampleAndDisablesCopyAndShare() {
        show(text = "")
        compose.onNodeWithText("✨ Your Name ✨", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Copy Sparkles").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Share Sparkles").assertIsNotEnabled()
    }

    @Test fun greetingAddsThePhraseInTheChosenLanguageAndShowsTheDraftNote() {
        show(text = "ሰላም", category = WordArtCategory.BIRTHDAY, language = "am", greeting = true)
        compose.onNodeWithText("🎂 መልካም ልደት 🎂\n🎂 ሰላም 🎂", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Amharic").assertExists()
        compose.onNodeWithText("Greeting wording in Amharic, Tigrinya and Afaan Oromoo is a draft until reviewed by native speakers.").assertExists()
    }

    @Test fun tappingAPreviewCopiesTheCompleteStyledText() {
        show()
        compose.onNodeWithContentDescription("Sparkles: Abebe").assertIsEnabled().performClick()
        assertEquals(listOf("✨ Abebe ✨"), copies)
        compose.onNodeWithText("Tap a style to copy it.").assertExists()
    }

    @Test fun previewsWithOnlyTheSampleAreNotClickable() {
        show(text = "")
        compose.onNodeWithContentDescription("Sparkles: Your Name").assertIsNotEnabled()
    }

    @Test fun theHostCopiesToTheClipboardAndConfirmsWithASnackbar() {
        compose.setContent { WordArtHost(onBack = {}) }
        compose.onNode(hasSetTextAction()).performTextInput("ሰላም")
        compose.onNodeWithContentDescription("Sparkles: ሰላም").performClick()
        val cm = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("✨ ሰላም ✨", cm.primaryClip!!.getItemAt(0).text.toString())
        compose.waitForIdle()
        compose.onNodeWithText("Copied").assertExists()
    }

    @Test fun back() {
        show()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Back"))
        compose.onNodeWithText("Back").performClick()
        assertEquals(1, backs)
    }

    @Test fun clipboardAndShareHelpersDoTheAndroidWork() {
        val app = RuntimeEnvironment.getApplication()
        copyToClipboard(app, "✨ Abebe ✨")
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("✨ Abebe ✨", cm.primaryClip!!.getItemAt(0).text.toString())
        // Not marked sensitive: the user will paste it elsewhere.
        assertTrue(cm.primaryClipDescription?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") != true)
        share(app, "✨ Abebe ✨")
        val started = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        val inner = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertEquals("text/plain", inner.type)
        assertEquals("✨ Abebe ✨", inner.getStringExtra(Intent.EXTRA_TEXT))
    }
}

package com.qalsmartkeyboard.app.themes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.qalsmartkeyboard.ime.theme.PremiumThemeAccess
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The theme picker on the JVM (Robolectric): sections, badges, the applied mark, preview and Apply / Unlock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h3000dp")
class ThemesScreenTest {
    @get:Rule val compose = createComposeRule()

    private val previewed = ArrayList<String>()
    private val applied = ArrayList<String>()
    private var unlocks = 0
    private var closed = 0

    private fun show(
        appliedId: String = "system",
        previewId: String? = null,
        access: PremiumThemeAccess = PremiumThemeAccess.NOT_ON_PLAN,
        systemDark: Boolean = false,
    ) = compose.setContent {
        ThemesScreen(
            appliedId = appliedId, previewId = previewId, access = access, systemDark = systemDark,
            onPreview = { previewed += it }, onClosePreview = { closed++ }, onApply = { applied += it },
            onUnlock = { unlocks++ }, onBack = {},
        )
    }

    @Test fun listShowsFreeAndPremiumSectionsWithEveryTheme() {
        show()
        compose.onNodeWithText("Free themes").assertExists()
        compose.onNodeWithText("Premium themes").assertExists()
        compose.onNodeWithText("System").assertExists()
        for (t in ThemeCatalog.all) compose.onNodeWithText(t.name).assertExists()
    }

    @Test fun premiumCardsCarryABadgeAndFreeCardsDoNot() {
        show()
        compose.onAllNodesWithText("Premium").assertCountEquals(ThemeCatalog.premium.size)
        compose.onNodeWithContentDescription("Aurora, Premium theme, locked").assertExists()
        compose.onNodeWithContentDescription("Midnight").assertExists()
    }

    @Test fun anEntitledAccountSeesNoLock() {
        show(access = PremiumThemeAccess.ALLOWED)
        compose.onNodeWithContentDescription("Aurora, Premium theme").assertExists()
    }

    @Test fun theAppliedThemeIsMarked() {
        show(appliedId = "midnight")
        compose.onAllNodesWithText("Applied").assertCountEquals(1)
        compose.onNodeWithContentDescription("Midnight, Applied").assertExists()
    }

    @Test fun anUnknownStoredIdCountsAsSystem() {
        show(appliedId = "neon")
        compose.onNodeWithContentDescription("System, Applied").assertExists()
    }

    @Test fun tappingACardOpensThePreviewWithoutApplying() {
        show()
        compose.onNodeWithText("Ocean Blue").performScrollTo().performClick()
        compose.onNodeWithText("Aurora").performScrollTo().performClick()
        assertEquals(listOf("ocean_blue", "aurora"), previewed)
        assertTrue(applied.isEmpty())
    }

    @Test fun aFreePreviewOffersApply() {
        show(previewId = "forest_green")
        compose.onNodeWithText("Forest Green").assertExists()
        compose.onNodeWithText("Apply theme").assertIsEnabled().performClick()
        assertEquals(listOf("forest_green"), applied)
        assertEquals(1, closed)
        compose.onNodeWithContentDescription("Preview of the Forest Green keyboard theme").assertExists()
    }

    @Test fun aLockedPremiumPreviewOffersUnlockInsteadOfApply() {
        show(previewId = "aurora", access = PremiumThemeAccess.NOT_ON_PLAN)
        compose.onAllNodesWithText("Apply theme").assertCountEquals(0)
        compose.onNodeWithText("Your plan does not include Premium themes.").assertExists()
        compose.onNodeWithText("Unlock with Premium").performClick()
        assertEquals(1, unlocks)
        assertTrue(applied.isEmpty())
    }

    @Test fun anEntitledPremiumPreviewCanBeApplied() {
        show(previewId = "aurora", access = PremiumThemeAccess.ALLOWED)
        compose.onAllNodesWithText("Unlock with Premium").assertCountEquals(0)
        compose.onNodeWithText("Apply theme").performClick()
        assertEquals(listOf("aurora"), applied)
    }

    @Test fun theAppliedThemeCannotBeAppliedAgain() {
        show(appliedId = "lavender", previewId = "lavender")
        compose.onNodeWithText("Applied").assertIsNotEnabled()
    }

    @Test fun aChosenPremiumThemeThatIsLockedExplainsWhy() {
        show(appliedId = "modern_gradient", access = PremiumThemeAccess.SIGNED_OUT)
        compose.onNodeWithText("Modern Gradient is a Premium theme").assertExists()
        compose.onNodeWithText("Your keyboard shows Classic Light for now. Sign in to use Premium themes that belong to your account.").assertExists()
        compose.onNodeWithText("Unlock with Premium").performClick()
        assertEquals(1, unlocks)
    }

    @Test fun noBannerWhenEntitledOrWhenTheAppliedThemeIsFree() {
        show(appliedId = "modern_gradient", access = PremiumThemeAccess.ALLOWED)
        compose.onAllNodesWithText("Modern Gradient is a Premium theme").assertCountEquals(0)
    }

    @Test fun systemCardPreviewsTheDeviceMode() {
        show(previewId = "system", systemDark = true)
        compose.onNodeWithText("Classic Light or Classic Dark, following your phone.").assertExists()
        compose.onNodeWithContentDescription("Preview of the System keyboard theme").assertExists()
    }
}

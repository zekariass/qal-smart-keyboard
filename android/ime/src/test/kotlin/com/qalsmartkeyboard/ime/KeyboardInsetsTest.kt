package com.qalsmartkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Inset and row-height rules of the input view (bottom row vs. navigation bar, landscape, densities). */
class KeyboardInsetsTest {
    private fun ltrb(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = intArrayOf(l, t, r, b)

    /** The fixed gap under the bottom row at density 1 (8 px) and 2.625 (21 px). */
    private val gap1 = KeyboardInsets.bottomGapPx(1f)
    private val gapPixel = KeyboardInsets.bottomGapPx(2.625f)

    @Test fun bottomGapIsSmallFixedAndScalesWithDensity() {
        assertEquals(8, gap1)
        assertEquals(21, gapPixel)
        assertEquals(24, KeyboardInsets.bottomGapPx(3f))
        assertEquals(16, KeyboardInsets.bottomGapPx(2f))
        assertTrue("never zero", KeyboardInsets.bottomGapPx(0f) >= 1)
    }

    @Test fun gestureNavigationPadsTheBottomByTheBarHeightPlusTheGap() {
        // Pixel-class phone, gesture navigation, density 2.625: a 24dp handle area = 63px.
        assertEquals(InsetPadding(0, 0, 63 + gapPixel), KeyboardInsets.padding(nav = ltrb(b = 63), cutout = ltrb(), density = 2.625f))
    }

    @Test fun threeButtonNavigationPadsTheBottomInPortrait() {
        // 48dp bar at density 3.0 = 144px.
        assertEquals(InsetPadding(0, 0, 144 + 24), KeyboardInsets.padding(nav = ltrb(b = 144), cutout = ltrb(), density = 3f))
    }

    @Test fun threeButtonNavigationOnTheSideInLandscapePadsThatSide() {
        assertEquals(InsetPadding(0, 126, gap1), KeyboardInsets.padding(nav = ltrb(r = 126), cutout = ltrb()))
        assertEquals(InsetPadding(126, 0, gap1), KeyboardInsets.padding(nav = ltrb(l = 126), cutout = ltrb()))
    }

    @Test fun landscapeCutoutSidesArePaddedAndCombineWithTheBar() {
        // Notch on the left, gesture handle at the bottom.
        assertEquals(InsetPadding(90, 0, 50 + gap1), KeyboardInsets.padding(nav = ltrb(b = 50), cutout = ltrb(l = 90)))
        // Bar and cutout on the same side: the larger one wins (they overlap, they do not add up).
        assertEquals(InsetPadding(0, 130, gap1), KeyboardInsets.padding(nav = ltrb(r = 126), cutout = ltrb(r = 130)))
    }

    @Test fun theImesOwnNavigationStripIsClearedToo() {
        // Landscape phone, 3-button navigation: the system bar is on the right (the window already
        // ends before it, inset 0) but the IME back/switcher strip (48dp = 126px) is at the bottom.
        assertEquals(InsetPadding(0, 0, 126 + gapPixel), KeyboardInsets.padding(nav = ltrb(), cutout = ltrb(), imeNavBarPx = 126, density = 2.625f))
        // Portrait gesture navigation: the strip and the bar inset are the same area, not added up.
        assertEquals(InsetPadding(0, 0, 63 + gapPixel), KeyboardInsets.padding(nav = ltrb(b = 63), cutout = ltrb(), imeNavBarPx = 63, density = 2.625f))
    }

    @Test fun theGapIsTheSameWithGestureThreeButtonAndFittedNavigation() {
        // Whatever is under the keyboard, the bottom row keeps the same distance above it.
        val d = 2.625f
        for ((nav, strip) in listOf(63 to 0, 126 to 126, 0 to 126, 0 to 0)) {
            val p = KeyboardInsets.padding(nav = ltrb(b = nav), cutout = ltrb(), imeNavBarPx = strip, density = d)
            assertEquals("nav=$nav strip=$strip", maxOf(nav, strip) + gapPixel, p.bottom)
        }
    }

    @Test fun topInsetsNeverPadTheKeyboard() {
        // Portrait notch and status bar are at the top of the screen, far from the keyboard: only the gap.
        assertEquals(InsetPadding(0, 0, gap1), KeyboardInsets.padding(nav = ltrb(t = 80), cutout = ltrb(t = 120)))
    }

    @Test fun consumedInsetsOnOlderReleasesPadOnlyTheGap() {
        // API < 35 (or a decor that already fits the window): the dispatched insets are zero.
        assertEquals(InsetPadding(0, 0, gap1), KeyboardInsets.padding(nav = ltrb(), cutout = ltrb()))
    }

    @Test fun negativeValuesAreIgnored() {
        assertEquals(InsetPadding(0, 0, gap1), KeyboardInsets.padding(nav = ltrb(-5, -5, -5, -5), cutout = ltrb()))
    }

    @Test fun contentTopIsTheBottomOfTheHeadroomAndUnknownBeforeLayout() {
        assertEquals(1700, KeyboardInsets.contentTop(rootHeight = 1300, headroomTopInWindow = 1200, headroomHeight = 500))
        assertNull(KeyboardInsets.contentTop(rootHeight = 0, headroomTopInWindow = 0, headroomHeight = 500))
    }

    @Test fun headroomShrinksWhenTheWindowIsTooShortForItAndTheKeyboard() {
        // Portrait: room for the full 190dp headroom (density 2.625 = 499px).
        assertEquals(499, KeyboardInsets.headroomPx(499, availablePx = 2282, keyboardPx = 1000))
        // Landscape phone: 1080px window, 760px keyboard: only 320px left for popups.
        assertEquals(320, KeyboardInsets.headroomPx(499, availablePx = 1080, keyboardPx = 760))
        // Keyboard taller than the window: no headroom, never negative.
        assertEquals(0, KeyboardInsets.headroomPx(499, availablePx = 700, keyboardPx = 760))
    }

    // ---------------------------------------------------------------- row heights

    @Test fun portraitPhoneKeepsTheLayoutRowHeight() {
        assertEquals(50f, KeyboardMetrics.rowHeightDp(50f, 1f, 5, screenWidthDp = 411, screenHeightDp = 914), 0.01f)
        assertEquals(42f, KeyboardMetrics.rowHeightDp(42f, 1f, 7, 411, 914), 0.01f)
    }

    @Test fun landscapeRowsAreShorterAndFitTheScreen() {
        val h = KeyboardMetrics.rowHeightDp(50f, 1f, 5, screenWidthDp = 914, screenHeightDp = 411)
        assertTrue("landscape row $h", h < 50f * KeyboardMetrics.LANDSCAPE_FACTOR + 0.01f)
        assertTrue("keys use at most their share of the height", h * 5 <= 411 * KeyboardMetrics.LANDSCAPE_SHARE + 0.01f)
        assertTrue(h >= KeyboardMetrics.MIN_ROW_DP)
    }

    @Test fun keyHeightSettingIsHonouredInBothOrientations() {
        for ((w, hgt) in listOf(411 to 914, 914 to 411)) {
            val normal = KeyboardMetrics.rowHeightDp(50f, 1f, 5, w, hgt)
            val tall = KeyboardMetrics.rowHeightDp(50f * 1.3f, 1.3f, 5, w, hgt)
            val short = KeyboardMetrics.rowHeightDp(50f * 0.8f, 0.8f, 5, w, hgt)
            assertEquals("$w x $hgt", normal * 1.3f, tall, 0.01f)
            assertTrue("$w x $hgt", short < normal)
        }
    }

    @Test fun smallScreensClampToTheMinimumTouchTarget() {
        assertEquals(KeyboardMetrics.MIN_ROW_DP, KeyboardMetrics.rowHeightDp(42f, 1f, 7, screenWidthDp = 640, screenHeightDp = 320), 0.01f)
    }

    @Test fun tallLayoutsOnShortPortraitScreensAreCapped() {
        // A 7-row layout at 50dp on a 560dp-high screen would take 63% of it.
        val h = KeyboardMetrics.rowHeightDp(50f, 1f, 7, screenWidthDp = 360, screenHeightDp = 560)
        assertTrue(h * 7 <= 560 * KeyboardMetrics.PORTRAIT_SHARE + 0.01f)
    }
}

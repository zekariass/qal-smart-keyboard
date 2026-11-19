package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.qalsmartkeyboard.ime.CandidateStripView
import com.qalsmartkeyboard.ime.CandidateStripView.Target
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import kotlin.math.abs

/**
 * ADR 0041: the AI entry is an "AI" wordmark in the icon family, not the sparkle: drawn at the 22 dp icon size whatever the
 * font scale, in the strip's colour; active on the accent circle like every tool; the lock badge when locked; named
 * "AI tools" (localised) for TalkBack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiBarLabelTest {
    private val app: Context = RuntimeEnvironment.getApplication()
    private val d = app.resources.displayMetrics.density
    private val widthPx = (360 * d).toInt()
    private val iconPx = CandidateStripView.ICON_DP * d

    private fun context(lang: String = "en", fontScale: Float = 1f): Context =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale(lang)); this.fontScale = fontScale })

    private fun strip(ui: Context = app, theme: KeyboardTheme = ThemeCatalog.classicLight, locked: Boolean = false, active: Boolean = false): CandidateStripView =
        CandidateStripView(ui).also {
            it.theme = theme
            it.setShortcuts(listOf(ToolEntry(Tool.TRANSLATE), ToolEntry(Tool.AI, locked), ToolEntry(Tool.CLIPBOARD)))
            if (active) it.setActiveTools(setOf(Tool.AI))
            it.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            it.layout(0, 0, it.measuredWidth, it.measuredHeight)
        }

    private fun draw(s: CandidateStripView): Bitmap =
        Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888).also { s.draw(Canvas(it)) }

    private fun aiSlot(s: CandidateStripView) = s.slots().first { it.target == Target.Shortcut(Tool.AI) }

    private fun close(a: Int, b: Int, tol: Int = 40) =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b)) <= tol

    /** The bounds of what is drawn (differs from [bg]) within the slot's icon box (plus a margin). */
    private fun ink(bmp: Bitmap, cx: Float, cy: Float, bg: Int): Rect {
        val half = (iconPx / 2 + 3 * d).toInt()
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var b = Int.MIN_VALUE
        for (y in (cy - half).toInt()..(cy + half).toInt()) for (x in (cx - half).toInt()..(cx + half).toInt()) {
            if (!close(bmp.getPixel(x, y), bg, 60)) {
                l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x + 1); b = maxOf(b, y + 1)
            }
        }
        return Rect(l, t, r, b)
    }

    /** A point of the 24-unit icon grid in px, for the icon centred on ([cx], [cy]). */
    private fun at(cx: Float, cy: Float, ux: Float, uy: Float) = Pair((cx + (ux - 12f) / 24f * iconPx).toInt(), (cy + (uy - 12f) / 24f * iconPx).toInt())

    @Test fun theEntryIsAnAiWordmarkAtTheIconSize() {
        val theme = ThemeCatalog.classicLight
        val s = strip()
        val bmp = draw(s)
        val slot = aiSlot(s)
        val cx = slot.hit.centerX()
        val cy = slot.hit.centerY()
        val b = ink(bmp, cx, cy, theme.stripBg)
        // A wordmark box: about as wide as the other icons, wider than tall (the sparkle was taller than wide).
        assertTrue("width ${b.width() / d} dp", b.width() >= iconPx * 0.8f && b.width() <= iconPx + 2 * d)
        assertTrue("height ${b.height() / d} < width ${b.width() / d}", b.height() < b.width())
        assertEquals("centred", cx, b.exactCenterX(), 1.5f * d)
        assertEquals("centred", cy, b.exactCenterY(), 1.5f * d)
        // The outline's left side, the "I" stem and the gap between the letters, in the strip's text colour.
        val (ox, oy) = at(cx, cy, 2.5f, 12f)
        assertTrue("outline", close(bmp.getPixel(ox, oy), theme.stripText, 120))
        val (ix, iy) = at(cx, cy, 16.8f, 12f)
        assertTrue("I stem", close(bmp.getPixel(ix, iy), theme.stripText))
        val (gx, gy) = at(cx, cy, 13.5f, 12f)
        assertTrue("gap between A and I", close(bmp.getPixel(gx, gy), theme.stripBg))
        // The A's crossbar.
        val (ax, ay) = at(cx, cy, 8.5f, 13.2f)
        assertTrue("A crossbar", close(bmp.getPixel(ax, ay), theme.stripText))
    }

    @Test fun theMarkDoesNotGrowWithTheFontScale() {
        val normal = strip(context(fontScale = 1f))
        val large = strip(context(fontScale = 2f))
        val a = ink(draw(normal), aiSlot(normal).hit.centerX(), aiSlot(normal).hit.centerY(), ThemeCatalog.classicLight.stripBg)
        val b = ink(draw(large), aiSlot(large).hit.centerX(), aiSlot(large).hit.centerY(), ThemeCatalog.classicLight.stripBg)
        assertEquals(a.width().toFloat(), b.width().toFloat(), 1f)
        assertEquals(a.height().toFloat(), b.height().toFloat(), 1f)
        assertEquals(aiSlot(normal).hit.width(), aiSlot(large).hit.width(), 0.5f)
    }

    @Test fun activeIsTheAccentCircleWithTheMarkInOnAccent() {
        for (theme in listOf(ThemeCatalog.classicLight, ThemeCatalog.classicDark)) {
            val idle = strip(theme = theme)
            val on = strip(theme = theme, active = true)
            val slot = aiSlot(on)
            val cx = slot.hit.centerX()
            val cy = slot.hit.centerY()
            val ring = Pair((cx - 15 * d).toInt(), cy.toInt()) // inside the 36 dp circle, outside the 22 dp mark
            assertTrue(close(draw(on).getPixel(ring.first, ring.second), theme.accent))
            assertTrue(close(draw(idle).getPixel(ring.first, ring.second), theme.stripBg))
            val (ix, iy) = at(cx, cy, 16.8f, 12f)
            assertTrue("${theme.id}: the mark in on-accent", close(draw(on).getPixel(ix, iy), theme.onAccent))
            // TalkBack hears the active state.
            assertTrue(on.accessibilityNodeProvider!!.createAccessibilityNodeInfo(on.idOf(Target.Shortcut(Tool.AI)))!!.isSelected)
        }
    }

    @Test fun lockedCarriesTheLockBadgeLikeTheOtherTools() {
        val theme = ThemeCatalog.classicLight
        val open = strip(theme = theme)
        val locked = strip(theme = theme, locked = true)
        val slot = aiSlot(locked)
        val bx = slot.hit.centerX() + iconPx / 2 - 10f * d / 4
        val by = slot.hit.centerY() + iconPx / 2 - 10f * d / 4
        // The lock's body (a filled rectangle) sits on a disc of the bar colour at the mark's lower right.
        assertTrue(close(draw(locked).getPixel(bx.toInt(), (by + 1.5f * d).toInt()), theme.hintText, 60))
        var differing = 0
        val a = draw(open)
        val b = draw(locked)
        for (y in (by - 6 * d).toInt()..(by + 6 * d).toInt()) for (x in (bx - 6 * d).toInt()..(bx + 6 * d).toInt()) {
            if (a.getPixel(x, y) != b.getPixel(x, y)) differing++
        }
        assertTrue("the badge is drawn ($differing px)", differing > (20 * d * d).toInt())
        // Active hides the badge (the accent circle says it is open).
        val activeLocked = strip(theme = theme, locked = true, active = true)
        assertFalse(close(draw(activeLocked).getPixel(bx.toInt(), (by + 1.5f * d).toInt()), theme.hintText, 30))
    }

    @Test fun talkBackHearsAiToolsInTheUiLanguage() {
        for ((lang, name) in listOf("en" to "AI tools", "am" to "የAI መሣሪያዎች", "ti" to "መሳርሒታት AI", "om" to "Meeshaalee AI")) {
            val s = strip(context(lang))
            val n = s.accessibilityNodeProvider!!.createAccessibilityNodeInfo(s.idOf(Target.Shortcut(Tool.AI)))!!
            assertEquals(lang, name, n.contentDescription.toString())
            assertEquals("android.widget.Button", n.className)
            assertTrue(n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        }
        val locked = strip(locked = true)
        assertEquals(
            "AI tools, locked",
            locked.accessibilityNodeProvider!!.createAccessibilityNodeInfo(locked.idOf(Target.Shortcut(Tool.AI)))!!.contentDescription.toString(),
        )
    }

    @Test fun theToolsPanelTileUsesTheSameMark() {
        assertEquals(ToolIcon.AI, Tool.AI.icon)
        assertTrue("the sparkle is no AI identifier any more", Tool.entries.none { it.icon == ToolIcon.ASSIST })
    }
}

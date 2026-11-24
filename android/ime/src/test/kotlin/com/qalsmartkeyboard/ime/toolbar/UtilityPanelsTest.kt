package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import android.text.InputType as T
import android.view.View
import android.widget.TextView
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.KeyLayoutChoice
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.ai.AiTool
import com.qalsmartkeyboard.ime.utilities.CalcKey
import com.qalsmartkeyboard.ime.utilities.CalculatorController
import com.qalsmartkeyboard.ime.utilities.CalendarController
import com.qalsmartkeyboard.ime.utilities.ConverterController
import com.qalsmartkeyboard.ime.utilities.ConverterMode
import com.qalsmartkeyboard.ime.utilities.FxSnapshot
import com.qalsmartkeyboard.ime.utilities.FxSnapshotCache
import com.qalsmartkeyboard.ime.utilities.QrController
import com.qalsmartkeyboard.ime.utilities.QrKind
import com.qalsmartkeyboard.ime.utilities.QrState
import com.qalsmartkeyboard.ime.utilities.YMD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T8.3: the productivity tiles and panels: the tiles are offered (free, never locked) in every field that takes text
 * except passwords, each tile opens its panel through the tools controller, every panel builds and its controls are real
 * 48dp buttons with a spoken name, and the Insert / Copy callbacks carry the result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UtilityPanelsTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val widthPx = (411 * d).toInt()

    /** The panel height on the reference phone (1080x2340 at 2.625): the 328dp keyboard area less the 44dp bar. */
    private val PANEL_DP = 284

    private val utilityTools = listOf(Tool.CALCULATOR, Tool.UNIT_CONVERTER, Tool.CURRENCY, Tool.CALENDAR, Tool.QR, Tool.DRAW)

    private fun profile(type: Int) = InputProfile.from(type, 0, false, 0)

    private fun View.layoutAt(widthPx: Int, heightPx: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun <V : UtilityPanelView> V.opened(): V {
        heightProvider = { (PANEL_DP * d).toInt() }
        render()
        layoutAt(widthPx, (PANEL_DP * d).toInt())
        return this
    }

    // ---- tiles and the tools controller ---------------------------------------------------------------------------

    private class Host : ToolHost {
        override fun openTranslate() {}
        override fun runAiTool(tool: AiTool) {}
        override fun openEmoji() {}
        override fun startVoice() {}
        override fun openWordArt() {}
        override fun cycleWritingMode() {}
        override fun openLanguages() {}
        override fun openTheme() {}
        override fun openSettings() {}
        override fun applyLayout(choice: KeyLayoutChoice) {}
        override fun closeOtherPanels() {}
    }

    @Test fun theTilesAreOfferedUnlockedInTextNumberAndPhoneFieldsButNotPasswords() {
        for (type in listOf(T.TYPE_CLASS_TEXT, T.TYPE_CLASS_NUMBER, T.TYPE_CLASS_PHONE, T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)) {
            val entries = ToolSet.tiles(ToolContext(profile(type)))
            val got = entries.filter { it.tool in utilityTools }
            assertEquals("$type", utilityTools, got.map { it.tool })
            assertTrue(got.none { it.locked })
        }
        for (type in listOf(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD, T.TYPE_CLASS_NUMBER or T.TYPE_NUMBER_VARIATION_PASSWORD, T.TYPE_NULL)) {
            assertTrue("$type", ToolSet.tiles(ToolContext(profile(type))).none { it.tool in utilityTools })
        }
    }

    @Test fun theTilesStayLockFreeWhenAiIsBlockedOrLocked() {
        val c = ToolContext(profile(T.TYPE_CLASS_TEXT), aiReady = true, aiBlocked = true, translateLocked = true, aiLocked = true)
        val got = ToolSet.tiles(c).filter { it.tool in utilityTools }
        assertEquals(utilityTools, got.map { it.tool })
        assertTrue(got.none { it.locked })
    }

    @Test fun theTilesAreNotPinnedToTheBar() {
        val c = ToolContext(profile(T.TYPE_CLASS_TEXT), aiReady = true, voiceOffered = true)
        assertTrue(ToolSet.shortcutCandidates(c).none { it.tool in utilityTools })
    }

    @Test fun eachTileOpensItsPanelAndBackClosesIt() {
        var closes = 0
        val host = object : ToolHost by Host() {
            override fun closeOtherPanels() { closes++ }
        }
        val t = ToolsController(host, { ToolContext(profile(T.TYPE_CLASS_TEXT)) })
        val expect = mapOf(
            Tool.CALCULATOR to ToolPanel.CALCULATOR,
            Tool.UNIT_CONVERTER to ToolPanel.CONVERTER,
            Tool.CURRENCY to ToolPanel.CONVERTER,
            Tool.CALENDAR to ToolPanel.CALENDAR,
            Tool.QR to ToolPanel.QR,
            Tool.DRAW to ToolPanel.DRAW,
        )
        for ((tool, panel) in expect) {
            t.toggleTools()
            assertEquals(ToolPanel.TOOLS, t.panel)
            t.activate(tool)
            assertEquals("$tool", panel, t.panel)
            if (panel == ToolPanel.CONVERTER) assertEquals(tool == Tool.CURRENCY, t.converterOnCurrency)
            assertTrue(t.back())
            assertEquals(ToolPanel.NONE, t.panel)
        }
        assertTrue(closes > 0)
    }

    @Test fun aStaleTapInAPasswordFieldDoesNothing() {
        val t = ToolsController(Host(), { ToolContext(profile(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD)) })
        t.activate(Tool.CALCULATOR)
        assertEquals(ToolPanel.NONE, t.panel)
    }

    @Test fun theToolsGridDrawsTheNewTilesWithLabelsAndIcons() {
        val grid = ToolsPanelView(ctx).apply {
            heightProvider = { (260 * d).toInt() }
            tiles = { ToolSet.tiles(ToolContext(profile(T.TYPE_CLASS_TEXT))) }
            render()
        }
        grid.layoutAt(widthPx, (260 * d).toInt())
        for (tool in utilityTools) {
            val tile = grid.tileFor(tool)
            assertNotNull("$tool", tile)
            assertEquals(toolLabel(ctx, tool), tile!!.contentDescription.toString())
            assertTrue(toolLabel(ctx, tool).isNotBlank())
        }
        assertFalse(grid.shown.any { it.tool in utilityTools && it.locked })
    }

    // ---- accessibility: 48dp buttons with names -------------------------------------------------------------------

    private fun assertControlsAreAccessible(panel: UtilityPanelView, what: String) {
        assertTrue("$what has controls", panel.controls.isNotEmpty())
        for (v in panel.controls) {
            val name = v.contentDescription?.toString().orEmpty().ifBlank { (v as? TextView)?.text?.toString().orEmpty() }
            assertTrue("$what: a control has no name", name.isNotBlank())
            assertTrue("$what/$name: not clickable", v.isClickable && v.isFocusable)
            assertTrue("$what/$name: ${v.measuredHeight / d}dp tall", v.measuredHeight >= (48 * d).toInt() - 1)
            assertTrue("$what/$name: ${v.measuredWidth / d}dp wide", v.measuredWidth >= (48 * d).toInt() - 1)
        }
    }

    @Test fun calculatorPanelControlsAre48dpWithNames() {
        val p = CalculatorPanelView(ctx).apply { controller = CalculatorController() }.opened()
        assertControlsAreAccessible(p, "calculator")
        assertEquals(24, p.controls.size)
    }

    @Test fun converterPanelControlsAre48dpWithNamesInEveryMode() {
        val cache = FxSnapshotCache().apply { put(FxSnapshot("USD", System.currentTimeMillis(), mapOf("ETB" to 130.0))) }
        val c = ConverterController(fx = { cache.current() })
        val p = ConverterPanelView(ctx).apply { controller = c; this.cache = cache }.opened()
        for (m in ConverterMode.entries) {
            c.selectMode(m)
            p.render()
            p.layoutAt(widthPx, (PANEL_DP * d).toInt())
            assertControlsAreAccessible(p, "converter $m")
        }
    }

    @Test fun calendarAndQrPanelControlsAre48dpWithNames() {
        val cal = CalendarPanelView(ctx).apply { controller = CalendarController(today = { YMD(2024, 9, 11) }) }.opened()
        assertControlsAreAccessible(cal, "calendar")
        val qrC = QrController()
        val qr = QrPanelView(ctx).apply { controller = qrC }.opened()
        assertControlsAreAccessible(qr, "qr text")
        qrC.selectKind(QrKind.WIFI)
        qr.render()
        qr.layoutAt(widthPx, (PANEL_DP * d).toInt())
        assertControlsAreAccessible(qr, "qr wifi")
    }

    // ---- behaviour through the views ------------------------------------------------------------------------------

    private fun View.tap(label: String) {
        val v = (this as UtilityPanelView).controls.first { (it.contentDescription ?: (it as TextView).text).toString() == label }
        assertTrue(v.performClick())
    }

    @Test fun calculatorTypesInsertsAndCopies() {
        var inserted: String? = null
        var copied: String? = null
        lateinit var p: CalculatorPanelView
        val calc = CalculatorController(onChanged = { p.refresh() }) // as the service wires it
        p = CalculatorPanelView(ctx).apply {
            controller = calc
            onInsert = { inserted = it }
            onCopy = { copied = it }
        }.opened()
        for (k in listOf(CalcKey.Digit(2), CalcKey.Op('+'), CalcKey.Digit(2))) calc.key(k)
        assertEquals("2+2", p.expressionText.toString())
        assertEquals("= 4", p.resultLine.toString())
        p.tap(ctx.getString(R.string.util_insert))
        assertEquals("4", inserted)
        p.tap(ctx.getString(R.string.util_copy))
        assertEquals("4", copied)
        p.tap(ctx.getString(R.string.calc_key_equals))
        assertEquals("4", p.expressionText.toString())
        p.tap(ctx.getString(R.string.calc_key_clear))
        assertEquals("0", p.expressionText.toString())
        p.tap(ctx.getString(R.string.calc_key_equals))
        assertEquals("", p.resultLine.toString())
        calc.key(CalcKey.Digit(1)); calc.key(CalcKey.Op('÷')); calc.key(CalcKey.Digit(0)); calc.key(CalcKey.Equals)
        assertEquals(ctx.getString(R.string.calc_error_div0), p.resultLine.toString())
    }

    @Test fun converterShowsTheResultAndTheOfflineStates() {
        var copied: String? = null
        val c = ConverterController()
        val p = ConverterPanelView(ctx).apply { controller = c; onCopy = { copied = it } }.opened()
        p.tap("1"); p.tap("0")
        assertEquals("= 6.213711922 mi", p.resultLine.toString())
        p.tap(ctx.getString(R.string.util_copy))
        assertEquals("10 km = 6.213711922 mi", copied)

        // Currency without a snapshot.
        val offline = ConverterController(fx = { null }).also { it.selectMode(ConverterMode.CURRENCY) }
        p.controller = offline
        p.render()
        p.tap("5")
        assertEquals(ctx.getString(R.string.conv_rates_unavailable), p.resultLine.toString())
        copied = null
        p.tap(ctx.getString(R.string.util_copy))
        assertEquals(null, copied)

        // A stale snapshot keeps converting and says how old it is.
        val cache = FxSnapshotCache(clock = { 1_700_000_000_000L + 3 * FxSnapshotCache.DAY_MILLIS })
        cache.put(FxSnapshot("USD", 1_700_000_000_000L, mapOf("ETB" to 130.0)))
        val online = ConverterController(fx = { cache.current() }).also { it.selectMode(ConverterMode.CURRENCY) }
        p.cache = cache
        p.controller = online
        p.render()
        p.tap("1")
        assertEquals("= 130.00 ETB", p.resultLine.toString())
        assertEquals(ctx.getString(R.string.conv_rates_stale, "2023-11-14"), p.noteText.toString())
    }

    @Test fun calendarPanelShowsTheConvertedDateWithMonthNames() {
        var inserted: String? = null
        val cal = CalendarController(today = { YMD(2024, 9, 11) })
        val p = CalendarPanelView(ctx).apply { controller = cal; onInsert = { inserted = it } }.opened()
        assertEquals("1 Meskerem 2017 E.C.", p.resultLine.toString())
        p.tap(ctx.getString(R.string.cal_insert))
        assertEquals("1 Meskerem 2017 E.C.", inserted)
        p.tap(ctx.getString(R.string.cal_swap))
        assertEquals("11 September 2024 G.C.", p.resultLine.toString())
        p.tap(ctx.getString(R.string.cal_numerals))
        assertEquals("፲፩ September ፳፻፳፬ G.C.", p.resultLine.toString())
    }

    @Test fun qrPanelReadsTextOnlyOnTapAndEncodesIt() {
        var fieldReads = 0
        var clipReads = 0
        var copied: String? = null
        lateinit var p: QrPanelView
        val qrC = QrController(onChanged = { p.refresh() }) // as the service wires it
        p = QrPanelView(ctx).apply {
            controller = qrC
            fieldText = { fieldReads++; "example.com" }
            clipboardText = { clipReads++; "from clipboard" }
            onCopy = { copied = it }
        }.opened()
        assertEquals(0, fieldReads + clipReads)
        assertEquals(QrState.Empty, p.shownState)
        p.tap(ctx.getString(R.string.qr_kind_url))
        p.tap(ctx.getString(R.string.qr_use_field))
        assertEquals(1, fieldReads)
        assertEquals(0, clipReads)
        assertTrue(p.shownState is QrState.Ready)
        p.tap(ctx.getString(R.string.qr_copy_text))
        assertEquals("https://example.com", copied)
        p.tap(ctx.getString(R.string.qr_use_clipboard))
        assertEquals(1, clipReads)
        assertEquals("from clipboard", p.sourceText.toString())
        // A Wi-Fi password is masked in the preview but encoded in the code.
        p.tap(ctx.getString(R.string.qr_kind_wifi))
        qrC.setSource("Home\nsecret")
        assertFalse(p.sourceText.toString().contains("secret"))
        assertEquals("WIFI:T:WPA;S:Home;P:secret;;", (qrC.state() as QrState.Ready).payload)
        qrC.clear()
        assertEquals(QrState.Empty, p.shownState)
    }

    @Test fun everyPanelDrawsInEveryThemeWithoutErrors() {
        val widthPx = this.widthPx
        val panels: List<UtilityPanelView> = listOf(
            CalculatorPanelView(ctx), ConverterPanelView(ctx), CalendarPanelView(ctx), QrPanelView(ctx),
        )
        for (p in panels) {
            for (theme in com.qalsmartkeyboard.ime.theme.ThemeCatalog.all) {
                p.theme = theme
                p.opened()
                p.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(widthPx, (260 * d).toInt(), android.graphics.Bitmap.Config.ARGB_8888)))
            }
        }
    }

    // ---- fit: everything a task needs is on screen at the default keyboard height ---------------------------------

    private fun View.offsetIn(root: View): Pair<Int, Int> {
        var x = 0
        var y = 0
        var v: View = this
        while (v !== root) {
            x += v.left
            y += v.top
            v = v.parent as View
        }
        return x to y
    }

    private fun assertFitsWithoutScrolling(p: UtilityPanelView, what: String, primary: List<String> = emptyList()) {
        assertFalse("$what scrolls at ${PANEL_DP}dp", p.scrolls)
        for (v in p.controls) {
            val (x, y) = v.offsetIn(p)
            val name = v.contentDescription?.toString() ?: (v as? TextView)?.text?.toString().orEmpty()
            assertTrue("$what/$name clipped at the top ($y)", y >= 0)
            assertTrue("$what/$name clipped at the bottom: ${(y + v.height) / d}dp of ${p.height / d}dp", y + v.height <= p.height)
            // Tabs and chips sit in a sideways strip on purpose; everything else must be inside the width.
            if (generateSequence(v.parent) { it.parent }.none { it is android.widget.HorizontalScrollView }) {
                assertTrue("$what/$name clipped at the side", x >= 0 && x + v.width <= p.width)
            }
            assertTrue("$what/$name under 48dp: ${v.height / d}x${v.width / d}", v.height >= (48 * d).toInt() - 1 && v.width >= (48 * d).toInt() - 1)
        }
        for (label in primary) {
            val v = p.controls.firstOrNull { (it.contentDescription ?: (it as TextView).text).toString() == label }
            assertNotNull("$what has $label", v)
        }
    }

    @Test fun calculatorPadFitsWithItsDisplayInTheBar() {
        val shown = ArrayList<String>()
        val p = CalculatorPanelView(ctx).apply {
            displayInBar = true
            onDisplay = { text, _, _ -> shown += text }
            controller = CalculatorController(onChanged = { refresh() })
        }.opened()
        assertFitsWithoutScrolling(p, "calculator", listOf(ctx.getString(R.string.util_insert), ctx.getString(R.string.util_copy), ctx.getString(R.string.calc_key_equals)))
        assertEquals("0", shown.last())
        p.tap("7"); p.tap(ctx.getString(R.string.calc_key_plus)); p.tap("1")
        assertEquals("7+1  = 8", shown.last())
    }

    @Test fun calculatorWithoutABarKeepsItsDisplayAboveThePad() {
        val p = CalculatorPanelView(ctx).apply { controller = CalculatorController(onChanged = { refresh() }) }.opened()
        p.tap("7")
        assertEquals("7", p.expressionText.toString())
        assertControlsAreAccessible(p, "calculator (own display)")
    }

    @Test fun converterFitsInEveryMode() {
        val cache = FxSnapshotCache().apply { put(FxSnapshot("USD", System.currentTimeMillis(), mapOf("ETB" to 130.0), stale = true)) }
        val c = ConverterController(fx = { cache.current() })
        val p = ConverterPanelView(ctx).apply { controller = c; this.cache = cache }.opened()
        for (m in ConverterMode.entries) {
            c.selectMode(m)
            p.render()
            p.layoutAt(widthPx, (PANEL_DP * d).toInt())
            p.tap("1")
            assertFitsWithoutScrolling(p, "converter $m", listOf(ctx.getString(R.string.util_insert), ctx.getString(R.string.util_copy)))
        }
        // The note under the result (rates as of / stale) is part of the layout, not below it.
        assertTrue(p.noteText.isNotEmpty())
    }

    @Test fun calendarFits() {
        val p = CalendarPanelView(ctx).apply { controller = CalendarController(today = { YMD(2024, 9, 11) }) }.opened()
        assertFitsWithoutScrolling(p, "calendar", listOf(ctx.getString(R.string.cal_insert), ctx.getString(R.string.util_copy), ctx.getString(R.string.cal_today)))
    }

    @Test fun qrFitsForEveryKindWithTheImageButtons() {
        val qrC = QrController()
        val qr = QrPanelView(ctx).apply { controller = qrC; imageShare = com.qalsmartkeyboard.ime.share.FakeImageShare() }.opened()
        for (k in QrKind.entries) {
            qrC.selectKind(k)
            qr.render()
            qr.layoutAt(widthPx, (PANEL_DP * d).toInt())
            assertFitsWithoutScrolling(qr, "qr $k", listOf(ctx.getString(R.string.qr_copy_text), ctx.getString(R.string.qr_share_image)))
        }
    }

    @Test fun theToolsGridScrollsToItsLastRowAboveTheBottomEdge() {
        val grid = ToolsPanelView(ctx).apply {
            heightProvider = { (PANEL_DP * d).toInt() }
            tiles = { ToolSet.tiles(ToolContext(profile(T.TYPE_CLASS_TEXT))) }
            render()
        }
        grid.layoutAt(widthPx, (PANEL_DP * d).toInt())
        val scroll = grid.getChildAt(0) as android.widget.ScrollView
        scroll.scrollTo(0, 100_000)
        val tile = grid.tileFor(grid.shown.last().tool)!!
        val y = tile.offsetIn(scroll.getChildAt(0)).second - scroll.scrollY
        assertTrue("the last tile ends ${(y + tile.height) / d}dp in a ${grid.height / d}dp panel", y >= 0 && y + tile.height <= grid.height)
    }

    @Test fun theBarDrawsTheCalculatorLineShrinkingAndCutting() {
        val bar = com.qalsmartkeyboard.ime.CandidateStripView(ctx)
        bar.panelTitle = "Calculator"
        bar.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((44 * d).toInt(), View.MeasureSpec.EXACTLY))
        bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)
        val canvas = android.graphics.Canvas(android.graphics.Bitmap.createBitmap(widthPx, (44 * d).toInt(), android.graphics.Bitmap.Config.ARGB_8888))
        bar.panelDetail = "12×3  = 36"
        bar.panelDetailDescription = "12 times 3. Result 36"
        bar.draw(canvas)
        bar.panelDetail = "a very long expression ".repeat(12)
        bar.panelDetailWarn = true
        bar.draw(canvas)
    }

    @Test fun currencyShowsTheAsOfDateAndTheStaleNoteFromAFakeSource() {
        val now = 1_700_000_000_000L
        var stale = false
        val cache = FxSnapshotCache(source = { FxSnapshot("USD", now - 2 * 60 * 60 * 1000, mapOf("ETB" to 130.0), stale = stale) }, clock = { now })
        assertTrue(cache.refreshIfDue())
        var opened = 0
        val c = ConverterController(fx = { cache.current() }).also { it.selectMode(ConverterMode.CURRENCY) }
        val p = ConverterPanelView(ctx).apply { controller = c; this.cache = cache; onCurrencyOpened = { opened++ } }.opened()
        assertEquals(1, opened)
        assertEquals(ctx.getString(R.string.conv_rates_as_of, "2023-11-14"), p.noteText.toString())
        stale = true
        assertTrue(cache.refresh())
        p.refresh()
        assertEquals(ctx.getString(R.string.conv_rates_stale, "2023-11-14"), p.noteText.toString())
        // Another tab does not ask for rates.
        c.selectMode(ConverterMode.LENGTH)
        p.render()
        assertEquals(1, opened)
    }
}

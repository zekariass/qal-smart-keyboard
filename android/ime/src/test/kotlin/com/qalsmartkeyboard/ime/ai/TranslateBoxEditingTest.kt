package com.qalsmartkeyboard.ime.ai

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.qalsmartkeyboard.ime.CoreTestEnv
import com.qalsmartkeyboard.ime.DefaultEnabledLanguages
import com.qalsmartkeyboard.ime.FakeEditor
import com.qalsmartkeyboard.ime.InMemoryModePreferences
import com.qalsmartkeyboard.ime.InputConnectionAdapter
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.KeyKind
import com.qalsmartkeyboard.ime.KeyboardController
import com.qalsmartkeyboard.ime.LayoutKind
import com.qalsmartkeyboard.ime.TypingMode
import com.qalsmartkeyboard.ime.editor.EditorView
import com.qalsmartkeyboard.ime.userdict.WordLearningSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * ADR 0038, the translate box as a real editable field: a tap anywhere puts the cursor there (right to left too), the keys
 * (through the real keyboard controller and its redirect) insert and delete at the cursor and replace a selection, a long
 * press selects a word with the action row, phonetic composition and candidates work mid-text, and the cursor survives
 * language changes, the swap and the language lists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslateBoxEditingTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val widthPx = (360 * d).toInt()

    /** The keyboard (passthrough composer, or the real core), the host field, the translate controller and its panel. */
    private inner class Rig(real: Boolean = false, mode: TypingMode? = null) {
        val session = if (real) CoreTestEnv.Session() else null
        val ed: FakeEditor = session?.ed ?: FakeEditor("Field text")
        val adapter: InputConnectionAdapter = session?.adapter ?: InputConnectionAdapter { ed }.also { it.startSession(false, 10, 10) }
        val actions = FakeAiActions()
        lateinit var kc: KeyboardController
        val tc = TranslateController(actions, EditorAiAccess(adapter) { kc.commitComposition() }, { it() }, {}, { kc.language }, onChanged = { view.render() }, onInputChanged = { view.renderInput() })
        val view = TranslatePanelView(ctx)

        init {
            kc = session?.c ?: KeyboardController(adapter, DefaultEnabledLanguages, InMemoryModePreferences(), WordLearningSink.None) {}
            if (session == null) {
                adapter.onCompositionAbandoned = { kc.onCompositionAbandoned() }
                kc.startField(InputProfile.DEFAULT)
            }
            if (mode != null) session!!.mode(mode)
            kc.fieldTyping = { tc.fieldTyping() }
            view.controller = tc
            view.haptics = { false }
            view.beforeEdit = { kc.commitComposition() }
            tc.onFieldStarted(InputProfile.DEFAULT, "com.example.chat")
            tc.open()
            kc.refreshAutoCapital() // the box takes no auto-capitals
            view.visibility = View.VISIBLE
            layout()
        }

        val box: EditorView get() = view.inputEditor
        val buf get() = tc.input.buffer

        fun layout() {
            view.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }

        fun key(kind: KeyKind) = kc.layout.rows.flatten().first { it.kind == kind }

        fun press(kind: KeyKind) {
            kc.onKey(key(kind))
            ed.deliver(adapter)
        }

        fun type(s: String) {
            if (session != null) {
                session.type(s)
                return
            }
            for (ch in s) {
                val k = kc.layout.rows.flatten().first { !it.isSpacer && if (ch == ' ') it.kind == KeyKind.SPACE else it.kind == KeyKind.CHAR && it.text.equals(ch.toString(), ignoreCase = true) }
                kc.onKey(k)
            }
            ed.deliver(adapter)
        }

        fun setText(t: String) {
            buf.setText(t)
            view.renderInput()
            layout()
        }

        private fun event(action: Int, x: Float, y: Float): MotionEvent {
            val t = SystemClock.uptimeMillis()
            return MotionEvent.obtain(t, t, action, x, y, 0)
        }

        private val lineY get() = box.paddingTop + 10 * d

        fun tap(x: Float) {
            box.onTouchEvent(event(MotionEvent.ACTION_DOWN, x, lineY))
            box.onTouchEvent(event(MotionEvent.ACTION_UP, x, lineY))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        }

        fun longPress(x: Float) {
            box.onTouchEvent(event(MotionEvent.ACTION_DOWN, x, lineY))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 50L))
            box.onTouchEvent(event(MotionEvent.ACTION_UP, x, lineY))
        }

        /** The x (in the box) of the caret position [offset] on the first line. */
        fun xOf(offset: Int): Float {
            var x = 1f
            while (x < box.width) {
                if (box.offsetAt(x, lineY) == offset) return x
                x += 1f
            }
            throw AssertionError("no x for $offset")
        }
    }

    @Test fun theWholeBoxWidthIsTheField() {
        val r = Rig()
        r.setText("Hello")
        val boxWidth = r.view.inputField0.width
        assertTrue("the editor spans the box (${r.box.width} of $boxWidth)", r.box.width >= boxWidth - 1)
    }

    @Test fun aTapPutsTheCursorThereAndTheKeysTypeThere() {
        val r = Rig()
        r.setText("Helo world")
        r.tap(r.xOf(3))
        assertEquals(3, r.buf.cursor)
        r.type("l")
        assertEquals("Hello world", r.tc.input.text)
        assertEquals(4, r.buf.cursor)
        assertEquals("the host field is untouched", "Field text", r.ed.text.toString())
        r.tap(1f)
        r.type("oh ")
        assertEquals("oh Hello world", r.tc.input.text)
    }

    @Test fun backspaceDeletesAtTheCursor() {
        val r = Rig()
        r.setText("Hello world")
        r.tap(r.xOf(5))
        r.press(KeyKind.BACKSPACE)
        assertEquals("Hell world", r.tc.input.text)
        assertEquals(4, r.buf.cursor)
    }

    @Test fun theKeysReplaceASelection() {
        val r = Rig()
        r.setText("I like tea")
        r.buf.setSelection(7, 10)
        r.type("cola")
        assertEquals("I like cola", r.tc.input.text)
    }

    @Test fun aTapInArabicFollowsTheRightToLeftOrder() {
        val r = Rig()
        val text = "مرحبا بالعالم"
        r.setText(text)
        r.tap(r.box.width - 2f) // the right edge: the start of an Arabic text
        assertEquals(0, r.buf.cursor)
        r.tap(2f)
        assertEquals(text.length, r.buf.cursor)
    }

    @Test fun backspaceKeepsArabicAndEthiopicClustersWhole() {
        val r = Rig()
        r.setText("بِسْ ሰ፟ላ")
        r.buf.setSelection(4) // after "سْ"
        r.press(KeyKind.BACKSPACE)
        assertEquals("بِ ሰ፟ላ", r.tc.input.text)
        r.buf.setSelection(5) // after "ሰ፟"
        r.press(KeyKind.BACKSPACE)
        assertEquals("بِ ላ", r.tc.input.text)
    }

    @Test fun aLongPressSelectsAWordAndOffersTheActionRow() {
        val r = Rig()
        r.setText("Good morning friend")
        r.longPress(r.xOf(7))
        assertEquals("morning", r.buf.selectedText)
        assertTrue(r.box.actionsShown)
        val labels = r.view.inputField0.actionViews.map { it.contentDescription.toString() } // named in every style (labels or icons)
        assertTrue(labels.toString(), labels.containsAll(listOf("Cut", "Copy", "Select all")))
        assertFalse("no clipboard: no Paste offered", "Paste" in labels)
        r.type("evening")
        assertEquals("Good evening friend", r.tc.input.text)
        assertFalse(r.box.actionsShown)
    }

    @Test fun theCursorSurvivesLanguagesTheSwapAndTheLists() {
        val r = Rig()
        r.setText("Selam world")
        r.tap(r.xOf(5))
        assertEquals(5, r.buf.cursor)
        r.tc.swap()
        r.tc.chooseDestination(AiDestination("lang:am", "Amharic", "am"))
        r.tc.openPicker(TranslatePicker.SOURCE)
        r.tc.closePicker()
        r.tc.openPicker(TranslatePicker.DESTINATION)
        r.tc.chooseSource("en")
        r.layout()
        assertEquals("Selam world", r.tc.input.text)
        assertEquals(5, r.buf.cursor)
        r.buf.setSelection(0, 5)
        r.tc.swap()
        r.view.render()
        assertEquals("Selam", r.buf.selectedText)
    }

    @Test fun aTapFinishesTheCompositionWhereItWas() {
        val r = Rig(real = true, mode = TypingMode("am", LayoutKind.PHONETIC))
        r.setText("ab cd")
        r.buf.setSelection(3)
        r.type("sela")
        assertTrue(r.buf.isComposing)
        assertEquals("ab ሰላcd", r.tc.input.text)
        r.tap(1f) // the composition is committed at its place first, then the cursor moves
        assertFalse(r.buf.isComposing)
        assertEquals(0, r.buf.cursor)
        assertTrue(r.tc.input.text, r.tc.input.text.startsWith("ab ሰላ"))
        assertTrue(r.tc.input.text, r.tc.input.text.endsWith("cd"))
    }

    @Test fun phoneticCompositionAndCandidatesWorkMidText() {
        val r = Rig(real = true, mode = TypingMode("am", LayoutKind.PHONETIC))
        r.setText("ab cd")
        r.buf.setSelection(3)
        r.type("sela")
        assertEquals(3, r.buf.composingStart)
        assertTrue(r.kc.candidates.any { it.text == "ሰላም" })
        r.kc.selectCandidate(r.kc.candidates.indexOfFirst { it.text == "ሰላም" })
        assertTrue(r.tc.input.text, r.tc.input.text.startsWith("ab ሰላም"))
        assertTrue(r.tc.input.text, r.tc.input.text.endsWith("cd"))
        assertFalse(r.buf.isComposing)
        assertEquals("the host field never sees it", "", r.ed.text.toString())
    }
}

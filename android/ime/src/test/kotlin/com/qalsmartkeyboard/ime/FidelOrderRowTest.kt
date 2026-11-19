package com.qalsmartkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Fidel vowel-order row follows the pending consonant family (real core data, DRAFT seed layouts). */
class FidelOrderRowTest {
    private fun session() = CoreTestEnv.Session(prefs = CoreTestEnv.FidelFirst())
    private fun row(s: CoreTestEnv.Session) = s.c.layout.rows.flatten().filter { it.kind == KeyKind.FIDEL_VOWEL }
    private fun labels(s: CoreTestEnv.Session) = row(s).joinToString("") { s.c.orderFace(it).label }
    private fun consonantKey(s: CoreTestEnv.Session, id: String) =
        s.c.layout.rows.flatten().first { it.kind == KeyKind.FIDEL_CONSONANT && it.text == id }

    @Test fun neutralRowIsTheCoresVowelKeysAndInactive() {
        val s = session()
        assertNull(s.c.pendingSeries)
        assertEquals("ሀሁሂሃሄህሆዋ", labels(s)) // the core's vowelKeys, nothing hardcoded here
        assertTrue(row(s).none { s.c.orderFace(it).active })
    }

    @Test fun rowFollowsTheSelectedFamily() {
        val s = session()
        s.consonant("L")
        assertEquals("L", s.c.pendingSeries)
        assertEquals("ለሉሊላሌልሎሏ", labels(s))
        assertTrue(row(s).all { s.c.orderFace(it).active })
        s.consonant("M")
        assertEquals("M", s.c.pendingSeries)
        assertEquals("መሙሚማሜምሞሟ", labels(s))
        s.consonant("S")
        assertEquals("ሰሱሲሳሴስሶሷ", labels(s))
    }

    @Test fun selectedConsonantIsTheOnlyHighlightedKey() {
        val s = session()
        s.consonant("M")
        val selected = s.c.layout.rows.flatten().filter { s.c.isSelectedConsonant(it) }
        assertEquals(listOf("M"), selected.map { it.text })
        s.consonant("L")
        assertEquals(listOf("L"), s.c.layout.rows.flatten().filter { s.c.isSelectedConsonant(it) }.map { it.text })
    }

    @Test fun labialisedFamiliesShowTheWaaFormOnTheEighthKey() {
        val s = session()
        s.consonant("G")
        assertEquals("ገጉጊጋጌግጎጓ", labels(s)) // order 8 resolves to ጓ through the labiovelar series
        s.consonant("Q")
        assertEquals("ቀቁቂቃቄቅቆቋ", labels(s))
        s.vowel(8)
        assertEquals("ግቋ", s.ed.text.toString()) // (ግ: the previously selected ገ committed) the order-8 key types what it shows
    }

    @Test fun irregularFamiliesAreDerivedFromTheirForms() {
        val s = session()
        // Series without some orders show blank inactive keys rather than a wrong syllable.
        val grid = s.c.composer.fidelGrid("am")!!
        for (k in grid.rows.flatten()) {
            s.consonant(k.seriesId)
            for (v in row(s)) {
                val face = s.c.orderFace(v)
                val form = k.forms.firstOrNull { it.order == v.order } ?: if (v.order == 8) k.forms.firstOrNull { it.order == 14 } else null
                assertEquals("${k.seriesId} order ${v.order}", form?.label ?: "", face.label)
                assertEquals(form != null, face.active)
            }
        }
    }

    @Test fun tigrinyaOnlySeriesWork() {
        val s = session()
        s.mode(TypingMode("ti", LayoutKind.FIDEL))
        s.consonant("QH")
        assertEquals("QH", s.c.pendingSeries)
        assertTrue(labels(s).startsWith("ቐቑቒቓቔቕቖ"))
        s.vowel(3)
        assertEquals("ቒ", s.ed.text.toString())
        assertNull(s.c.pendingSeries)
    }

    @Test fun orderKeyInsertsTheFamilyCharacterReplacingTheConsonant() {
        val s = session()
        s.consonant("L")
        assertEquals("ል", s.ed.composing)
        s.vowel(4)
        assertEquals("ላ", s.ed.text.toString()) // replaced, not appended to the default form
        assertNull(s.c.pendingSeries)
        assertEquals("ሀሁሂሃሄህሆዋ", labels(s))
        s.consonant("M")
        s.vowel(2)
        s.consonant("S")
        s.vowel(7)
        assertEquals("ላሙሶ", s.ed.text.toString())
    }

    @Test fun orderKeyDoesNothingWithoutAConsonantOrForAMissingOrder() {
        val s = session()
        s.vowel(4)
        assertEquals("", s.ed.text.toString())
        val grid = s.c.composer.fidelGrid("am")!!
        val k = grid.rows.flatten().first { f -> grid.vowelKeys.any { v -> f.forms.none { it.order == v.order } && !(v.order == 8 && f.forms.any { it.order == 14 }) } }
        s.consonant(k.seriesId)
        val missing = row(s).first { !s.c.orderFace(it).active }
        s.tap(missing)
        assertEquals(k.seriesId, s.c.pendingSeries) // the composition stays so the user can retry
        assertEquals(1, s.ed.text.length)
    }

    @Test fun rowResetsWhenTheConsonantIsCommittedOrCancelled() {
        val s = session()
        s.consonant("M")
        s.tap(s.key(KeyKind.SPACE))
        assertNull(s.c.pendingSeries)
        assertEquals("ሀሁሂሃሄህሆዋ", labels(s))
        assertEquals("ም ", s.ed.text.toString())

        s.consonant("M")
        s.tap(s.key(KeyKind.BACKSPACE)) // cancels the composition
        assertNull(s.c.pendingSeries)
        assertFalse(s.c.composing)

        s.consonant("M")
        s.consonant("L") // commits the first, selects the second
        assertEquals("L", s.c.pendingSeries)
        s.c.onCompositionAbandoned() // cursor moved / field change
        assertNull(s.c.pendingSeries)

        s.consonant("L")
        s.c.startField(InputProfile.DEFAULT) // a new field
        assertNull(s.c.pendingSeries)

        s.consonant("L")
        s.c.commitComposition()
        assertNull(s.c.pendingSeries)
    }

    @Test fun layoutKeysStayTheSameWhileTheFamilyChanges() {
        val s = session()
        val before = s.c.layout
        s.consonant("L")
        assertTrue("only the faces change, not the layout model", before === s.c.layout)
    }
}

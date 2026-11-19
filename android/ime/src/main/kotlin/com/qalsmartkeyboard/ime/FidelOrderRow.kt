package com.qalsmartkeyboard.ime

/**
 * What one key of the vowel-order row shows: [label] is the syllable of the selected consonant family
 * in that order (or, with no consonant selected, the neutral reference label from the core's vowel
 * keys); [active] is false while no family is selected or when the family has no such order.
 */
data class OrderFace(val order: Int, val label: String, val active: Boolean)

/**
 * The vowel-order row of the two-key Fidel layout as a function of the selected consonant family,
 * derived only from the core's [FidelGrid] (`FidelKey.forms` per consonant, `vowelKeys` for the order
 * keys). The layout model (`Layouts`) stays static: it holds one [KeyKind.FIDEL_VOWEL] key per order,
 * and the controller asks this class for the face of each key in the current state.
 *
 * The family's forms are the core's popup forms: order 1..8 are the series' own; for a series with
 * labialised variant (ቀ ገ ኀ ከ) the core resolves order 8 to the `ዋ`-type form `ቋ`, listed as form 14
 * ([LABIALIZED_WAA]), which is what the order-8 key shows and sends. All faces are precomputed once per
 * grid, so asking for one never allocates.
 */
class FidelOrderRow(private val grid: FidelGrid) {
    private val neutral: Map<Int, OrderFace> =
        grid.vowelKeys.associate { it.order to OrderFace(it.order, it.label, active = false) }

    private val bySeries: Map<String, Map<Int, OrderFace>> = grid.rows.flatten().associate { k ->
        k.seriesId to grid.vowelKeys.associate { v ->
            val form = k.forms.firstOrNull { it.order == v.order }
                ?: if (v.order == ORDER_LABIALIZED) k.forms.firstOrNull { it.order == LABIALIZED_WAA } else null
            v.order to if (form != null) OrderFace(v.order, form.label, active = true) else OrderFace(v.order, "", active = false)
        }
    }

    /** True when [seriesId] is a consonant family of this layout. */
    fun knows(seriesId: String): Boolean = seriesId in bySeries

    /**
     * The face of the key for [order] when [seriesId] is the pending consonant (null: none, the
     * neutral reference row). An order the family does not have is inactive and blank.
     */
    fun face(seriesId: String?, order: Int): OrderFace =
        (seriesId?.let { bySeries[it] } ?: neutral)[order] ?: OrderFace(order, "", active = false)

    companion object {
        /** Core `ORDER_LABIALIZED`: the order-8 ("wa") key. */
        const val ORDER_LABIALIZED = 8

        /** Core: labialised-series forms are order + 10; 14 is the "waa" form the order-8 key resolves to. */
        const val LABIALIZED_WAA = 14
    }
}

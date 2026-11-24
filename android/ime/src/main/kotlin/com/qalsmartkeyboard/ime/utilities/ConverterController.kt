package com.qalsmartkeyboard.ime.utilities

/** A key of the converter's number pad. */
sealed interface NumKey {
    data class Digit(val d: Int) : NumKey
    data object Dot : NumKey
    data object Negate : NumKey
    data object Backspace : NumKey
    data object Clear : NumKey
}

/** The converter's tabs: the unit categories, then currency. */
enum class ConverterMode {
    LENGTH, MASS, TEMPERATURE, VOLUME, AREA, SPEED, DATA, CURRENCY;

    val category: UnitCategory? get() = UnitCategory.entries.firstOrNull { it.name == name }
}

/** What the converter shows as its result. */
sealed interface ConverterResult {
    /** Nothing typed yet. */
    data object Empty : ConverterResult

    /** Currency with no usable rates ("rates unavailable offline"). */
    data object RatesUnavailable : ConverterResult

    /** [text] is the converted number; [phrase] the whole line to insert or copy ("5 kg = 11.0231 lb"). */
    data class Value(val text: String, val phrase: String) : ConverterResult
}

/**
 * The unit and currency converter's state (no Views, JVM-tested): a mode, the typed number, and the from and to unit.
 * Every call is local arithmetic; currency reads only [fx]'s cached snapshot.
 */
class ConverterController(
    private val fx: () -> FxSnapshot? = { null },
    private val onChanged: () -> Unit = {},
) {
    var mode: ConverterMode = ConverterMode.LENGTH
        private set

    /** The typed number as text: "", "-", "12", "-0.5", "3.". */
    var input: String = ""
        private set

    var fromId: String = UnitConverter.defaultPair(UnitCategory.LENGTH).first.id
        private set
    var toId: String = UnitConverter.defaultPair(UnitCategory.LENGTH).second.id
        private set

    /** The unit ids (or currency codes) of the current mode, in menu order. */
    fun unitIds(): List<String> = when (val c = mode.category) {
        null -> fx()?.currencies.orEmpty()
        else -> UnitConverter.units(c).map { it.id }
    }

    /** The short text of a unit id: its symbol, or the currency code. */
    fun symbol(id: String): String = if (mode == ConverterMode.CURRENCY) id else UnitConverter.unit(id)?.symbol ?: id

    fun selectMode(m: ConverterMode) {
        if (m == mode) return
        mode = m
        val cat = m.category
        if (cat != null) {
            val (a, b) = UnitConverter.defaultPair(cat)
            fromId = a.id
            toId = b.id
        } else {
            pickCurrencies(unitIds())
        }
        onChanged()
    }

    /** USD to ETB when the snapshot lists them, otherwise its first two currencies. */
    private fun pickCurrencies(ids: List<String>) {
        fromId = if ("USD" in ids) "USD" else ids.firstOrNull().orEmpty()
        toId = if ("ETB" in ids) "ETB" else ids.firstOrNull { it != fromId }.orEmpty()
    }

    fun key(k: NumKey) {
        input = when (k) {
            is NumKey.Digit -> when {
                input.replace("-", "").replace(".", "").length >= MAX_DIGITS -> input
                input == "0" -> k.d.toString()
                input == "-0" -> "-${k.d}"
                else -> input + k.d
            }
            NumKey.Dot -> when {
                '.' in input -> input
                input.isEmpty() -> "0."
                input == "-" -> "-0."
                else -> "$input."
            }
            NumKey.Negate -> if (input.startsWith("-")) input.drop(1) else "-$input"
            NumKey.Backspace -> input.dropLast(1)
            NumKey.Clear -> ""
        }
        onChanged()
    }

    /** Moves the from unit by [delta] places through the mode's units (wrapping); skips the to unit. */
    fun cycleFrom(delta: Int) {
        fromId = step(fromId, delta, avoid = toId)
        onChanged()
    }

    fun cycleTo(delta: Int) {
        toId = step(toId, delta, avoid = fromId)
        onChanged()
    }

    fun swap() {
        val t = fromId
        fromId = toId
        toId = t
        onChanged()
    }

    private fun step(id: String, delta: Int, avoid: String): String {
        val ids = unitIds()
        if (ids.size < 2) return id
        var i = ids.indexOf(id).takeIf { it >= 0 } ?: 0
        repeat(ids.size) {
            i = Math.floorMod(i + delta, ids.size)
            if (ids[i] != avoid) return ids[i]
        }
        return id
    }

    /** The typed number, or null when empty or just "-" . */
    fun amount(): Double? = input.trimEnd('.').takeIf { it.isNotEmpty() && it != "-" }?.toDoubleOrNull()

    /** Currency rates note for the panel: null in the unit modes. */
    fun ratesNote(cache: FxSnapshotCache?): RatesNote? = if (mode == ConverterMode.CURRENCY) cache?.note() ?: RatesNote.Unavailable else null

    fun result(): ConverterResult {
        val cat = mode.category
        if (cat == null) {
            val snap = fx() ?: return ConverterResult.RatesUnavailable
            if (fromId !in snap.currencies || toId !in snap.currencies) pickCurrencies(snap.currencies)
            val a = amount() ?: return ConverterResult.Empty
            val v = CurrencyConverter.convert(a, fromId, toId, snap) ?: return ConverterResult.RatesUnavailable
            val text = CurrencyConverter.format(v, toId)
            return ConverterResult.Value(text, "${formatDecimal(a)} $fromId = $text $toId")
        }
        val a = amount() ?: return ConverterResult.Empty
        val from = UnitConverter.unit(fromId) ?: return ConverterResult.Empty
        val to = UnitConverter.unit(toId) ?: return ConverterResult.Empty
        val text = formatDecimal(UnitConverter.convert(a, from, to))
        return ConverterResult.Value(text, "${formatDecimal(a)} ${from.symbol} = $text ${to.symbol}")
    }

    private companion object {
        const val MAX_DIGITS = 15
    }
}

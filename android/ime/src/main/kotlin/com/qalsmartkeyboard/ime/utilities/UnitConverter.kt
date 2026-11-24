package com.qalsmartkeyboard.ime.utilities

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Formats a number for display and insertion: at most [digits] significant digits, no trailing zeros, plain notation
 * (scientific only beyond 1e15 or below 1e-7). Locale independent: the decimal separator is always ".".
 */
fun formatDecimal(x: Double, digits: Int = 10): String {
    if (x.isNaN() || x.isInfinite()) return ""
    if (x == 0.0) return "0"
    val r = BigDecimal(x).round(MathContext(digits, RoundingMode.HALF_EVEN)).stripTrailingZeros()
    val magnitude = r.precision() - r.scale() - 1
    return if (magnitude in -7..14) r.toPlainString() else r.toString()
}

/** The categories of the unit converter, in tab order. */
enum class UnitCategory { LENGTH, MASS, TEMPERATURE, VOLUME, AREA, SPEED, DATA }

/**
 * One unit: `base = value * factor + offset`, where the base unit of a category is metre, kilogram, degree Celsius,
 * litre, square metre, metre per second and byte. Only temperatures have an offset (affine); the factors are exact
 * definitions (international inch, foot, yard, mile, avoirdupois pound and ounce, US liquid gallon, ...) except where
 * noted. [symbol] is the same in every UI language (units are written the same way in am, ti, om and en).
 */
data class UnitDef(
    val id: String,
    val symbol: String,
    val category: UnitCategory,
    val factor: Double,
    val offset: Double = 0.0,
)

object UnitConverter {
    private fun u(id: String, symbol: String, c: UnitCategory, factor: Double, offset: Double = 0.0) = UnitDef(id, symbol, c, factor, offset)

    private val all: List<UnitDef> = listOf(
        // Length (base: metre). 1 in = 0.0254 m exactly (1959 agreement); ft, yd, mi follow from it.
        u("mm", "mm", UnitCategory.LENGTH, 0.001),
        u("cm", "cm", UnitCategory.LENGTH, 0.01),
        u("m", "m", UnitCategory.LENGTH, 1.0),
        u("km", "km", UnitCategory.LENGTH, 1000.0),
        u("in", "in", UnitCategory.LENGTH, 0.0254),
        u("ft", "ft", UnitCategory.LENGTH, 0.3048),
        u("yd", "yd", UnitCategory.LENGTH, 0.9144),
        u("mi", "mi", UnitCategory.LENGTH, 1609.344),
        // Mass (base: kilogram). 1 lb = 0.45359237 kg exactly; 1 oz = 1/16 lb.
        u("mg", "mg", UnitCategory.MASS, 0.000001),
        u("g", "g", UnitCategory.MASS, 0.001),
        u("kg", "kg", UnitCategory.MASS, 1.0),
        u("t", "t", UnitCategory.MASS, 1000.0),
        u("oz", "oz", UnitCategory.MASS, 0.028349523125),
        u("lb", "lb", UnitCategory.MASS, 0.45359237),
        // Temperature (base: degree Celsius): C = F * 5/9 - 160/9 ; C = K - 273.15.
        u("c", "°C", UnitCategory.TEMPERATURE, 1.0),
        u("f", "°F", UnitCategory.TEMPERATURE, 5.0 / 9.0, -160.0 / 9.0),
        u("k", "K", UnitCategory.TEMPERATURE, 1.0, -273.15),
        // Volume (base: litre). US liquid measures: 1 gal = 3.785411784 L exactly, cup = gal/16, fl oz = gal/128.
        u("ml", "mL", UnitCategory.VOLUME, 0.001),
        u("l", "L", UnitCategory.VOLUME, 1.0),
        u("tsp", "tsp", UnitCategory.VOLUME, 0.00492892159375),
        u("tbsp", "tbsp", UnitCategory.VOLUME, 0.01478676478125),
        u("floz", "fl oz", UnitCategory.VOLUME, 0.0295735295625),
        u("cup", "cup", UnitCategory.VOLUME, 0.2365882365),
        u("gal", "gal", UnitCategory.VOLUME, 3.785411784),
        // Area (base: square metre). ft2 = 0.3048^2, acre = 4046.8564224 m2 exactly (US survey acre differs by 2 ppm).
        u("m2", "m²", UnitCategory.AREA, 1.0),
        u("km2", "km²", UnitCategory.AREA, 1_000_000.0),
        u("ha", "ha", UnitCategory.AREA, 10_000.0),
        u("ft2", "ft²", UnitCategory.AREA, 0.09290304),
        u("ac", "ac", UnitCategory.AREA, 4046.8564224),
        // Speed (base: metre per second). mph = 1609.344/3600, knot = 1852/3600, km/h = 1000/3600.
        u("ms", "m/s", UnitCategory.SPEED, 1.0),
        u("kmh", "km/h", UnitCategory.SPEED, 1000.0 / 3600.0),
        u("mph", "mph", UnitCategory.SPEED, 0.44704),
        u("kn", "kn", UnitCategory.SPEED, 1852.0 / 3600.0),
        // Data size (base: byte). KB, MB, GB, TB are decimal (1000); KiB, MiB, GiB binary (1024).
        u("b", "B", UnitCategory.DATA, 1.0),
        u("kb", "KB", UnitCategory.DATA, 1e3),
        u("mb", "MB", UnitCategory.DATA, 1e6),
        u("gb", "GB", UnitCategory.DATA, 1e9),
        u("tb", "TB", UnitCategory.DATA, 1e12),
        u("kib", "KiB", UnitCategory.DATA, 1024.0),
        u("mib", "MiB", UnitCategory.DATA, 1_048_576.0),
        u("gib", "GiB", UnitCategory.DATA, 1_073_741_824.0),
    )

    private val byId: Map<String, UnitDef> = all.associateBy { it.id }

    /** The units of [category] in menu order. */
    fun units(category: UnitCategory): List<UnitDef> = all.filter { it.category == category }

    fun unit(id: String): UnitDef? = byId[id]

    /** The unit pair a category opens with (what people convert most). */
    fun defaultPair(category: UnitCategory): Pair<UnitDef, UnitDef> {
        val ids = when (category) {
            UnitCategory.LENGTH -> "km" to "mi"
            UnitCategory.MASS -> "kg" to "lb"
            UnitCategory.TEMPERATURE -> "c" to "f"
            UnitCategory.VOLUME -> "l" to "gal"
            UnitCategory.AREA -> "m2" to "ft2"
            UnitCategory.SPEED -> "kmh" to "mph"
            UnitCategory.DATA -> "mb" to "kb"
        }
        return byId.getValue(ids.first) to byId.getValue(ids.second)
    }

    /** [value] in [from] expressed in [to]; both must be in the same category. */
    fun convert(value: Double, from: UnitDef, to: UnitDef): Double {
        require(from.category == to.category) { "units of different categories" }
        val base = value * from.factor + from.offset
        return (base - to.offset) / to.factor
    }
}

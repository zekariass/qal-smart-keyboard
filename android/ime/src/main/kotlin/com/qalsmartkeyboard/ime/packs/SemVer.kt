package com.qalsmartkeyboard.ime.packs

/**
 * SemVer 2.0.0 precedence for pack versions (`docs/specs/pack-format-v1.md` §5.2: no build
 * metadata, no leading zeros, pre-release allowed). The core already verified the string is a
 * valid version; an unparsable one compares as the lowest possible, so it can never win.
 */
class SemVer private constructor(
    private val core: List<Long>,
    private val pre: List<String>,
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        for (i in 0 until 3) {
            val c = core[i].compareTo(other.core[i])
            if (c != 0) return c
        }
        if (pre.isEmpty() || other.pre.isEmpty()) {
            // A version without pre-release is higher than one with.
            return other.pre.size.coerceAtMost(1) - pre.size.coerceAtMost(1)
        }
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val a = pre[i]
            val b = other.pre[i]
            val an = a.toLongOrNull()
            val bn = b.toLongOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1 // numeric identifiers are lower than alphanumeric ones
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return pre.size.compareTo(other.pre.size)
    }

    override fun equals(other: Any?) = other is SemVer && compareTo(other) == 0
    override fun hashCode() = core.hashCode() * 31 + pre.hashCode()

    companion object {
        /** Lower than every real version. */
        private val LOWEST = SemVer(listOf(-1, -1, -1), emptyList())

        fun parse(text: String): SemVer? {
            val dash = text.indexOf('-')
            val corePart = if (dash < 0) text else text.substring(0, dash)
            val prePart = if (dash < 0) "" else text.substring(dash + 1)
            val nums = corePart.split('.')
            if (nums.size != 3) return null
            val core = nums.map { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: return null }
            val pre = if (dash < 0) emptyList() else prePart.split('.')
            if (pre.any { it.isEmpty() }) return null
            return SemVer(core, pre)
        }

        fun parseOrLowest(text: String): SemVer = parse(text) ?: LOWEST
    }
}

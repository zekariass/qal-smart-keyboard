package com.qalsmartkeyboard.packsync

import com.qalsmartkeyboard.ime.packs.SemVer

/**
 * App version for `minAppVersion` comparisons. `versionName` need not be three-part SemVer
 * ("1.0", "1.2.3-debug"): the leading numeric groups are taken and padded with zeros.
 */
object AppVersion {
    fun parse(text: String): SemVer? {
        val groups = text.trim().split('.', '-', '+').takeWhile { it.isNotEmpty() && it.all(Char::isDigit) }.take(3)
        if (groups.isEmpty()) return null
        return SemVer.parse((groups + List(3 - groups.size) { "0" }).joinToString("."))
    }
}

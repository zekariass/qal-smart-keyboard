package com.qalsmartkeyboard.ime.utilities

/** The Wi-Fi security types of the `WIFI:` payload. */
enum class WifiSecurity(val code: String) { WPA("WPA"), WEP("WEP"), NONE("nopass") }

/**
 * Builders of the strings a QR code carries. Pure functions; nothing here reads the field or the network.
 */
object QrPayload {
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val HEX_ONLY = Regex("^[0-9A-Fa-f]+$")

    /** Plain text, as typed. */
    fun text(s: String): String = s

    /** A web address: scanners open it, so a bare "example.com" gets "https://" in front. Trimmed; null when empty. */
    fun url(s: String): String? {
        val t = s.trim()
        if (t.isEmpty()) return null
        return if (SCHEME.containsMatchIn(t) && !t.matches(Regex("^[^/\\s]+:\\d+(/.*)?$"))) t else "https://$t"
    }

    /**
     * `WIFI:T:WPA;S:name;P:password;H:true;;` (the de-facto ZXing format). In the name and password `\ ; , : "` are
     * escaped with a backslash; a value of hex digits only is wrapped in double quotes so scanners do not read it as hex.
     * An open network ([WifiSecurity.NONE]) carries no password.
     */
    fun wifi(ssid: String, password: String, security: WifiSecurity = WifiSecurity.WPA, hidden: Boolean = false): String = buildString {
        append("WIFI:T:").append(security.code).append(";S:").append(wifiValue(ssid)).append(';')
        if (security != WifiSecurity.NONE && password.isNotEmpty()) append("P:").append(wifiValue(password)).append(';')
        if (hidden) append("H:true;")
        append(';')
    }

    private fun wifiValue(v: String): String {
        val escaped = buildString { for (c in v) { if (c in "\\;,:\"") append('\\'); append(c) } }
        return if (HEX_ONLY.matches(v)) "\"$escaped\"" else escaped
    }

    /** MECARD (compact, smaller code): `MECARD:N:last,first;TEL:..;EMAIL:..;;`. `\ ; , :` are escaped. */
    fun mecard(first: String, last: String = "", phone: String = "", email: String = ""): String = buildString {
        fun esc(v: String) = buildString { for (c in v) { if (c in "\\;,:\"") append('\\'); append(c) } }
        append("MECARD:N:").append(esc(last)).append(',').append(esc(first)).append(';')
        if (phone.isNotBlank()) append("TEL:").append(esc(phone.trim())).append(';')
        if (email.isNotBlank()) append("EMAIL:").append(esc(email.trim())).append(';')
        append(';')
    }

    /** A minimal vCard 3.0 (CRLF lines). `\ ; ,` and line breaks are escaped in values as RFC 2426 asks. */
    fun vcard(first: String, last: String = "", phone: String = "", email: String = ""): String {
        fun esc(v: String) = v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n")
        val full = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
        return buildList {
            add("BEGIN:VCARD")
            add("VERSION:3.0")
            add("N:${esc(last)};${esc(first)};;;")
            add("FN:${esc(full)}")
            if (phone.isNotBlank()) add("TEL:${esc(phone.trim())}")
            if (email.isNotBlank()) add("EMAIL:${esc(email.trim())}")
            add("END:VCARD")
        }.joinToString("\r\n")
    }
}

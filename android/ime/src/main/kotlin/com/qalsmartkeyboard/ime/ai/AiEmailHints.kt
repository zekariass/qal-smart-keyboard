package com.qalsmartkeyboard.ime.ai

import android.text.InputType

/**
 * Whether the focused field looks like the body of an e-mail being written (ADR 0031), so the AI row shows "Email"
 * first. Conservative and content-free: only `EditorInfo` flags, the host package and the field's own hint text are
 * read, never what the user typed. Write Email is always available from the AI row; this only moves it forward.
 */
object AiEmailHints {
    /** Mail apps whose compose screen is a multi-line text field. */
    val MAIL_PACKAGES = setOf(
        "com.google.android.gm",
        "com.google.android.gm.lite",
        "com.microsoft.office.outlook",
        "com.yahoo.mobile.client.android.mail",
        "ch.protonmail.android",
        "me.proton.android.mail",
        "com.samsung.android.email.provider",
        "com.android.email",
        "me.bluemail.mail",
        "com.fsck.k9",
        "com.easilydo.mail",
        "com.readdle.spark",
        "ru.mail.mailapp",
        "com.zoho.mail",
        "net.thunderbird.android",
    )

    private val BODY_HINTS = listOf("compose email", "compose e-mail", "write email", "email body", "message body")

    /**
     * [inputType] and [hintText] are `EditorInfo.inputType` / `hintText`. True for a multi-line text field in a known
     * mail app that is not its subject or address field, or for any field whose type is `TYPE_TEXT_VARIATION_LONG_MESSAGE`
     * with an e-mail-like hint.
     */
    fun looksLikeEmailBody(packageName: String?, inputType: Int, hintText: CharSequence?): Boolean {
        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        ) {
            return false
        }
        val multiline = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val hint = hintText?.toString()?.lowercase().orEmpty()
        if (packageName in MAIL_PACKAGES) return multiline
        return multiline && BODY_HINTS.any { hint.contains(it) }
    }
}

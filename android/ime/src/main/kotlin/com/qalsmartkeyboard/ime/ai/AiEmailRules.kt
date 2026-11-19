package com.qalsmartkeyboard.ime.ai

/** Why an e-mail request cannot be sent yet (checked on the device before any call). */
enum class AiEmailProblem {
    /** NEW and REPLY need the user's description of what to say. */
    MISSING_INSTRUCTIONS,

    /** REPLY needs the e-mail being answered. */
    MISSING_ORIGINAL,

    /** IMPROVE needs the draft. */
    MISSING_DRAFT,
    INSTRUCTIONS_TOO_LONG,
    ORIGINAL_TOO_LONG,
    DRAFT_TOO_LONG,
    NAME_TOO_LONG,
}

/**
 * The e-mail writer's rules, shared by the keyboard's Email panel and the companion app's screen (ADR 0027, 0031): the
 * contract's per-mode requirements and limits, placeholder extraction for highlighting, and the text Insert puts in the
 * host field. Pure Kotlin.
 */
object AiEmailRules {
    const val MAX_INSTRUCTIONS = 2000
    const val MAX_EMAIL = 8000
    const val MAX_NAME = 120
    const val MAX_VARIATION = 20

    /** The first problem of [spec], or null when it can be sent. Lengths are counted in code points, like the server. */
    fun validate(spec: AiEmailSpec): AiEmailProblem? {
        fun len(s: String) = s.codePointCount(0, s.length)
        when (spec.mode) {
            AiEmailMode.NEW -> if (spec.instructions.isBlank()) return AiEmailProblem.MISSING_INSTRUCTIONS
            AiEmailMode.REPLY -> {
                if (spec.originalEmail.isBlank()) return AiEmailProblem.MISSING_ORIGINAL
                if (spec.instructions.isBlank()) return AiEmailProblem.MISSING_INSTRUCTIONS
            }
            AiEmailMode.IMPROVE -> if (spec.draft.isBlank()) return AiEmailProblem.MISSING_DRAFT
        }
        return when {
            len(spec.instructions) > MAX_INSTRUCTIONS -> AiEmailProblem.INSTRUCTIONS_TOO_LONG
            spec.mode == AiEmailMode.REPLY && len(spec.originalEmail) > MAX_EMAIL -> AiEmailProblem.ORIGINAL_TOO_LONG
            spec.mode == AiEmailMode.IMPROVE && len(spec.draft) > MAX_EMAIL -> AiEmailProblem.DRAFT_TOO_LONG
            len(spec.recipientName.trim()) > MAX_NAME || len(spec.senderName.trim()) > MAX_NAME -> AiEmailProblem.NAME_TOO_LONG
            else -> null
        }
    }

    /**
     * `[...]` items of 1-60 characters that are not Markdown links (`[x](...)`), as the server finds them (ADR 0027 §4).
     * Nested or empty brackets do not count.
     */
    private val PLACEHOLDER = Regex("\\[([^\\[\\]\\n]{1,60})\\](?!\\()")

    /** Where the placeholders of [text] are (for highlighting), in order. */
    fun placeholderRanges(text: String): List<IntRange> =
        PLACEHOLDER.findAll(text).filter { it.groupValues[1].isNotBlank() }.map { it.range }.toList()

    /**
     * The placeholders the user still has to fill in: those the server listed that are still in the text, plus any the
     * text has now (the user may have edited it), distinct, in order of appearance.
     */
    fun placeholders(subject: String?, body: String, fromServer: List<String> = emptyList()): List<String> {
        val all = (subject.orEmpty() + "\n" + body)
        val found = placeholderRanges(all).map { all.substring(it) }
        val listed = fromServer.filter { all.contains(it) }
        return (found + listed).distinct().sortedBy { all.indexOf(it) }
    }

    /**
     * What Insert commits to the host field. The keyboard cannot reach a separate subject field, so by default only the
     * body is inserted (the subject is copied separately); with [includeSubject] the subject comes first as a
     * "Subject: ..." line, for hosts with one text box.
     */
    fun insertText(subject: String?, body: String, includeSubject: Boolean): String =
        if (includeSubject && !subject.isNullOrBlank()) "Subject: ${subject.trim()}\n\n$body" else body

    /** Subject and body as one text (Copy all, Share). */
    fun fullText(subject: String?, body: String): String =
        if (subject.isNullOrBlank()) body else "${subject.trim()}\n\n$body"

    fun nextVariation(v: Int): Int = (v + 1).coerceAtMost(MAX_VARIATION)
}

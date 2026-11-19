package com.qalsmartkeyboard.ime.ai

import android.content.Context
import com.qalsmartkeyboard.ime.R
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** The user-facing wording of the AI tools, the result panel and the AI panels (UI language resources), in one place. */
object AiMessages {
    fun failure(c: Context, f: AiFailure): String = when (f) {
        AiFailure.Offline -> c.getString(R.string.ai_err_offline)
        AiFailure.Timeout -> c.getString(R.string.ai_err_timeout)
        AiFailure.Unreachable -> c.getString(R.string.ai_err_unreachable)
        AiFailure.Busy -> c.getString(R.string.ai_err_busy)
        is AiFailure.QuotaExceeded -> {
            val reset = f.resetAtMs?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
            val base = if (reset != null) c.getString(R.string.ai_err_quota_reset, reset) else c.getString(R.string.ai_err_quota)
            if (f.canUpgrade) base + " " + c.getString(R.string.ai_err_quota_upgrade) else base
        }
        is AiFailure.RateLimited -> c.getString(R.string.ai_err_rate_limited)
        AiFailure.NotEntitled -> c.getString(R.string.ai_err_not_entitled)
        AiFailure.OnDeviceOnly -> c.getString(R.string.ai_err_on_device_only)
        is AiFailure.ConsentRequired -> c.getString(R.string.ai_err_consent)
        AiFailure.GuardrailFailed -> c.getString(R.string.ai_err_guardrail)
        AiFailure.ContentRejected -> c.getString(R.string.ai_err_content_rejected)
        AiFailure.LanguageUnsupported -> c.getString(R.string.ai_err_language)
        AiFailure.SignInRequired -> c.getString(R.string.ai_err_sign_in)
        AiFailure.AccountBlocked -> c.getString(R.string.ai_err_account)
        AiFailure.UpdateRequired -> c.getString(R.string.ai_err_update)
        AiFailure.ServiceUnavailable -> c.getString(R.string.ai_err_unavailable)
        AiFailure.Other -> c.getString(R.string.ai_err_other)
        AiFailure.NothingToProcess -> c.getString(R.string.ai_err_nothing)
        AiFailure.TooLong -> c.getString(R.string.ai_err_too_long)
        is AiFailure.InputTooLong -> c.getString(R.string.ai_err_input_too_long, fieldName(c, f.field))
        is AiFailure.Invalid -> if (f.field == "outputLanguage") c.getString(R.string.ai_err_invalid_output) else c.getString(R.string.ai_err_invalid)
    }

    /** The name of a request field the server named in an error ("The e-mail you received"). */
    fun fieldName(c: Context, field: String?): String = c.getString(
        when (field) {
            "instructions" -> R.string.ai_field_name_instructions
            "originalEmail" -> R.string.ai_field_name_original
            "draft" -> R.string.ai_field_name_draft
            "senderName", "recipient.name", "recipient" -> R.string.ai_field_name_name
            else -> R.string.ai_field_name_text
        },
    )

    fun link(c: Context, l: AiLink): String = c.getString(
        when (l) {
            AiLink.SIGN_IN -> R.string.ai_link_sign_in
            AiLink.AI_SETTINGS, AiLink.SETTINGS, AiLink.LANGUAGES, AiLink.THEMES, AiLink.TRANSLATION -> R.string.ai_link_settings
            AiLink.UPGRADE -> R.string.ai_link_upgrade
            AiLink.UPDATE_APP -> R.string.ai_link_update
            AiLink.EMAIL_WRITER -> R.string.ai_email_open_full
        },
    )

    /** A language's name, or "Same as text" for null. */
    fun language(c: Context, code: String?): String = if (code == null) c.getString(R.string.ai_same_as_text) else AiLanguages.name(code)

    fun action(c: Context, a: AiAssistAction): String = c.getString(
        when (a) {
            AiAssistAction.TRANSLATE -> R.string.ai_action_translate
            AiAssistAction.EXPLAIN -> R.string.ai_action_explain
            AiAssistAction.GRAMMAR -> R.string.ai_action_grammar
            AiAssistAction.REWRITE -> R.string.ai_action_rewrite
            AiAssistAction.PROFESSIONAL -> R.string.ai_action_professional
            AiAssistAction.SIMPLIFY -> R.string.ai_action_simplify
            AiAssistAction.REPLY -> R.string.ai_action_reply
            AiAssistAction.SUMMARIZE -> R.string.ai_action_summarize
        },
    )

    /** The Run button: the action and where its result goes ("Translate to English", "Explain in Amharic"). */
    fun run(c: Context, a: AiAssistAction, output: String?): String {
        val lang = output?.let(AiLanguages::name)
        return when (a) {
            AiAssistAction.TRANSLATE -> c.getString(R.string.ai_run_translate, lang ?: "English")
            AiAssistAction.EXPLAIN -> c.getString(R.string.ai_run_explain, lang ?: "English")
            AiAssistAction.GRAMMAR -> c.getString(R.string.ai_run_grammar)
            AiAssistAction.REWRITE -> if (lang == null) c.getString(R.string.ai_run_rewrite) else c.getString(R.string.ai_run_rewrite_in, lang)
            AiAssistAction.PROFESSIONAL -> if (lang == null) c.getString(R.string.ai_run_professional) else c.getString(R.string.ai_run_professional_in, lang)
            AiAssistAction.SIMPLIFY -> if (lang == null) c.getString(R.string.ai_run_simplify) else c.getString(R.string.ai_run_simplify_in, lang)
            AiAssistAction.REPLY -> c.getString(R.string.ai_run_reply, lang ?: "English")
            AiAssistAction.SUMMARIZE -> c.getString(R.string.ai_run_summarize, lang ?: "English")
        }
    }

    /** The working line ("Translating to English…"). */
    fun working(c: Context, a: AiAssistAction, output: String?): String {
        val lang = output?.let(AiLanguages::name)
        return when (a) {
            AiAssistAction.TRANSLATE -> c.getString(R.string.ai_working_translate, lang ?: "English")
            AiAssistAction.EXPLAIN -> c.getString(R.string.ai_working_explain)
            AiAssistAction.GRAMMAR -> c.getString(R.string.ai_working_grammar)
            AiAssistAction.REWRITE, AiAssistAction.PROFESSIONAL, AiAssistAction.SIMPLIFY -> c.getString(R.string.ai_working_rewrite)
            AiAssistAction.REPLY -> c.getString(R.string.ai_working_reply)
            AiAssistAction.SUMMARIZE -> c.getString(R.string.ai_working_summarize)
        }
    }

    /** Long-form actions get longer server budgets (ADR 0027 §6): say so while waiting. */
    fun waitHint(c: Context, kind: AiTaskKind): String? = when (kind) {
        AiTaskKind.EXPLAIN, AiTaskKind.SUMMARIZE, AiTaskKind.REPLY -> c.getString(R.string.ai_wait_long, 20)
        AiTaskKind.EMAIL -> c.getString(R.string.ai_wait_long, 30)
        else -> null
    }

    fun tone(c: Context, t: AiEmailTone): String = c.getString(
        when (t) {
            AiEmailTone.PROFESSIONAL -> R.string.ai_email_tone_professional
            AiEmailTone.FORMAL -> R.string.ai_email_tone_formal
            AiEmailTone.FRIENDLY -> R.string.ai_email_tone_friendly
            AiEmailTone.CASUAL -> R.string.ai_email_tone_casual
            AiEmailTone.POLITE -> R.string.ai_email_tone_polite
            AiEmailTone.PERSUASIVE -> R.string.ai_email_tone_persuasive
            AiEmailTone.APOLOGETIC -> R.string.ai_email_tone_apologetic
            AiEmailTone.CONCISE -> R.string.ai_email_tone_concise
        },
    )

    /** The short explanation of a tone (the tooltip under the tone chips). */
    fun toneHint(c: Context, t: AiEmailTone): String = c.getString(
        when (t) {
            AiEmailTone.PROFESSIONAL -> R.string.ai_email_tone_professional_hint
            AiEmailTone.FORMAL -> R.string.ai_email_tone_formal_hint
            AiEmailTone.FRIENDLY -> R.string.ai_email_tone_friendly_hint
            AiEmailTone.CASUAL -> R.string.ai_email_tone_casual_hint
            AiEmailTone.POLITE -> R.string.ai_email_tone_polite_hint
            AiEmailTone.PERSUASIVE -> R.string.ai_email_tone_persuasive_hint
            AiEmailTone.APOLOGETIC -> R.string.ai_email_tone_apologetic_hint
            AiEmailTone.CONCISE -> R.string.ai_email_tone_concise_hint
        },
    )

    fun length(c: Context, l: AiLength): String = c.getString(
        when (l) {
            AiLength.SHORT -> R.string.ai_length_short
            AiLength.MEDIUM -> R.string.ai_length_medium
            AiLength.LONG -> R.string.ai_length_long
        },
    )

    fun mode(c: Context, m: AiEmailMode): String = c.getString(
        when (m) {
            AiEmailMode.NEW -> R.string.ai_email_mode_new
            AiEmailMode.REPLY -> R.string.ai_email_mode_reply
            AiEmailMode.IMPROVE -> R.string.ai_email_mode_improve
        },
    )

    fun problem(c: Context, p: AiEmailProblem): String = c.getString(
        when (p) {
            AiEmailProblem.MISSING_INSTRUCTIONS -> R.string.ai_email_problem_instructions
            AiEmailProblem.MISSING_ORIGINAL -> R.string.ai_email_problem_original
            AiEmailProblem.MISSING_DRAFT -> R.string.ai_email_problem_draft
            AiEmailProblem.INSTRUCTIONS_TOO_LONG -> R.string.ai_email_problem_instructions_long
            AiEmailProblem.ORIGINAL_TOO_LONG -> R.string.ai_email_problem_original_long
            AiEmailProblem.DRAFT_TOO_LONG -> R.string.ai_email_problem_draft_long
            AiEmailProblem.NAME_TOO_LONG -> R.string.ai_email_problem_name_long
        },
    )

    fun notice(c: Context, n: AiNotice): String? = when (n) {
        AiNotice.NONE -> null
        AiNotice.COPIED -> c.getString(R.string.ai_copied)
        AiNotice.COPY_FAILED -> c.getString(R.string.ai_copy_failed)
        AiNotice.CLIPBOARD_EMPTY -> c.getString(R.string.ai_clipboard_empty)
        AiNotice.INSERT_FAILED -> c.getString(R.string.ai_apply_failed)
    }

    /** A tile's short label in the AI tools grid (ADR 0041): at most two lines in every UI language. */
    fun tool(c: Context, t: AiTool): String = when (t) {
        AiTool.TRANSLATE -> c.getString(R.string.ai_translate)
        AiTool.GRAMMAR -> action(c, AiAssistAction.GRAMMAR)
        AiTool.REWRITE -> action(c, AiAssistAction.REWRITE)
        AiTool.SIMPLIFY -> action(c, AiAssistAction.SIMPLIFY)
        AiTool.REPLY -> action(c, AiAssistAction.REPLY)
        AiTool.EXPLAIN -> action(c, AiAssistAction.EXPLAIN)
        AiTool.SUMMARIZE -> action(c, AiAssistAction.SUMMARIZE)
        AiTool.EMAIL -> c.getString(R.string.tool_email)
        AiTool.ASSIST -> c.getString(R.string.tool_ai_assist)
        else -> tone(c, t.tone!!)
    }

    /** A tile's TalkBack name: the label; a tone says it rewrites ("Rewrite, tone: Friendly"); Assist says what it does. */
    fun toolDescription(c: Context, t: AiTool): String = when {
        t.tone != null -> c.getString(R.string.ai_tile_tone_description, tool(c, t))
        t == AiTool.ASSIST -> c.getString(R.string.ai_assist_chip_description)
        else -> tool(c, t)
    }

    /** The name of a rewrite tone ("Friendly"). */
    fun tone(c: Context, t: AiTone): String = c.getString(
        when (t) {
            AiTone.FRIENDLY -> R.string.ai_tone_friendly
            AiTone.POLITE -> R.string.ai_tone_polite
            AiTone.PROFESSIONAL -> R.string.ai_tone_professional
            AiTone.CONCISE -> R.string.ai_tone_concise
            AiTone.APOLOGETIC -> R.string.ai_tone_apologetic
            AiTone.HONORIFIC -> R.string.ai_tone_honorific
        },
    )

    /** The remaining allowance ("3 of 10 left this month"), or "" when the plan state does not say. */
    fun quota(c: Context, q: AiQuotaHint?): String {
        val left = q?.remaining ?: return ""
        val limit = q.limit
        val period = when (q.period) {
            AiPeriod.DAILY -> R.string.ai_period_daily
            AiPeriod.WEEKLY -> R.string.ai_period_weekly
            AiPeriod.MONTHLY -> R.string.ai_period_monthly
            AiPeriod.YEARLY -> R.string.ai_period_yearly
            AiPeriod.NONE -> null
        }?.let { c.getString(it) }
        val base = if (limit != null) c.getString(R.string.ai_quota_of, left, limit) else c.getString(R.string.ai_quota_left, left)
        return if (period != null) "$base $period" else base
    }

    /** "1,234 / 5,000". */
    fun count(length: Int, limit: Int): String {
        val f = NumberFormat.getIntegerInstance()
        return "${f.format(length)} / ${f.format(limit)}"
    }
}

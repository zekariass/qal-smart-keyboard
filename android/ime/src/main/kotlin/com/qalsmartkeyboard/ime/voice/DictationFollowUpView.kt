package com.qalsmartkeyboard.ime.voice

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.ai.AiMessages
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog

/** What the strip's buttons do: the controller's four follow-up actions (a test supplies its own). */
interface FollowUpActions {
    fun fixGrammar()
    fun translate()
    fun undo()
    fun dismiss()
}

/**
 * The one-row strip offered after a dictation into the app's field (T8.12, ADR 0049 D2/D3). It sits above the keys and
 * is GONE while [VoiceController.followUp] is null:
 *  - Offer: [Fix grammar] [Translate to (label)] and the close button;
 *  - Working: the action's working line (the keys stay usable) and the close button;
 *  - Done: "Fixed" or "Translated", Undo and the close button;
 *  - Failed: the short failure text (the existing [AiMessages] wording, or "The text changed" when the field moved), a
 *    retry chip for the same action and the close button.
 *
 * Every control is a Button-role view whose touch area is the whole 48dp row height (the pill is drawn smaller inside
 * it) and at least 48dp wide, with a description for TalkBack; the message line is a polite live region, so "Fixed"
 * and failures are announced. Colours: theme tokens in pairs the contrast test covers (special label on the special
 * fill, on-accent on the accent, strip text and warn on the strip background). The dictated text is never shown,
 * described or logged here (N2): only action names, the destination's label and fixed messages.
 */
class DictationFollowUpView(context: Context) : LinearLayout(context) {
    var controller: VoiceController? = null
        set(value) {
            field = value
            render()
        }

    var theme: KeyboardTheme = ThemeCatalog.classic(
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
        set(value) {
            if (field === value) return
            field = value
            restyle()
        }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()

    private var actions: FollowUpActions? = null

    val fix: TextView = chip().apply { setOnClickListener { actions?.fixGrammar() } }
    val translate: TextView = chip().apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setOnClickListener { actions?.translate() }
    }
    val message: TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6f), 0, dp(6f), 0)
        ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
    }

    /** Undo (after a result) or the retry (after a failure): one chip, relabelled. */
    val action: TextView = chip(accent = true)
    val dismiss: TextView = chip().apply {
        text = "✕"
        setPadding(dp(8f), 0, dp(8f), 0)
        contentDescription = context.getString(R.string.voice_followup_dismiss)
        setOnClickListener { actions?.dismiss() }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6f), 0, dp(6f), 0)
        contentDescription = context.getString(R.string.voice_followup_strip)
        addView(fix, rowParams())
        addView(translate, rowParams())
        addView(message, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(action, rowParams())
        addView(dismiss, rowParams())
        restyle()
        visibility = GONE
    }

    private fun rowParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply { marginEnd = dp(4f) }

    /** Re-reads the controller's follow-up. Cheap; the host calls it from the controller's change callback. */
    fun render() {
        val c = controller
        bind(c?.followUp, c?.let(::ControllerActions))
    }

    /** Draws [followUp] (GONE when null) and routes the buttons to [actions]. */
    internal fun bind(followUp: DictationFollowUp?, actions: FollowUpActions?) {
        this.actions = actions
        if (followUp == null) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        val phase = followUp.phase
        val grammarLabel = context.getString(R.string.ai_action_grammar)
        val translateLabel = context.getString(R.string.ai_translate_to, followUp.destination.label)
        fix.text = grammarLabel
        fix.contentDescription = context.getString(R.string.voice_followup_fix_desc)
        translate.text = translateLabel
        translate.contentDescription = context.getString(R.string.voice_followup_translate_desc, followUp.destination.label)

        val offer = phase is FollowUpPhase.Offer
        fix.visibility = if (offer) VISIBLE else GONE
        translate.visibility = if (offer) VISIBLE else GONE
        message.visibility = if (offer) INVISIBLE else VISIBLE
        message.setTextColor(if (phase is FollowUpPhase.Failed) theme.warn else theme.stripText)
        message.text = when (phase) {
            FollowUpPhase.Offer -> ""
            is FollowUpPhase.Working -> when (phase.action) {
                FollowUpAction.GRAMMAR -> context.getString(R.string.ai_working_grammar)
                FollowUpAction.TRANSLATE -> context.getString(R.string.ai_working_translate, followUp.destination.label)
            }
            is FollowUpPhase.Done -> context.getString(
                if (phase.action == FollowUpAction.GRAMMAR) R.string.voice_followup_fixed else R.string.voice_followup_translated,
            )
            is FollowUpPhase.Failed -> when {
                phase.moved -> context.getString(R.string.voice_followup_moved)
                phase.failure != null -> AiMessages.failure(context, phase.failure)
                else -> context.getString(R.string.ai_err_other)
            }
        }
        when (phase) {
            is FollowUpPhase.Done -> {
                action.visibility = VISIBLE
                action.text = context.getString(R.string.voice_followup_undo)
                action.contentDescription = context.getString(R.string.voice_followup_undo_desc)
                action.setOnClickListener { this.actions?.undo() }
            }
            is FollowUpPhase.Failed -> {
                action.visibility = VISIBLE
                val name = when (phase.action) {
                    FollowUpAction.GRAMMAR -> grammarLabel
                    FollowUpAction.TRANSLATE -> translateLabel
                }
                action.text = context.getString(R.string.voice_retry)
                action.contentDescription = context.getString(R.string.voice_followup_retry_desc, name)
                action.setOnClickListener {
                    when (phase.action) {
                        FollowUpAction.GRAMMAR -> this.actions?.fixGrammar()
                        FollowUpAction.TRANSLATE -> this.actions?.translate()
                    }
                }
            }
            else -> {
                action.visibility = GONE
                action.setOnClickListener(null)
                action.isClickable = false
            }
        }
        dismiss.visibility = VISIBLE
    }

    private class ControllerActions(private val c: VoiceController) : FollowUpActions {
        override fun fixGrammar() = c.fixGrammar()
        override fun translate() = c.translateFollowUp()
        override fun undo() = c.undoFollowUp()
        override fun dismiss() = c.dismissFollowUp()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP), MeasureSpec.EXACTLY))
    }

    private fun chip(accent: Boolean = false): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = dp(MIN_TOUCH_DP)
        minWidth = dp(MIN_TOUCH_DP)
        setPadding(dp(14f), 0, dp(14f), 0)
        isClickable = true
        isFocusable = true
        tag = accent
        accessibilityDelegate = ButtonRole
    }

    private fun restyle() {
        val t = theme
        setBackgroundColor(t.stripBg)
        for (v in listOf(fix, translate, action, dismiss)) {
            val accent = v.tag == true
            val pill = GradientDrawable().apply {
                cornerRadius = 18f * d
                setColor(if (accent) t.accent else t.special)
            }
            // The touch area is the full row; the pill is drawn inset so the chips look slim.
            v.background = InsetDrawable(pill, 0, dp(6f), 0, dp(6f))
            v.setTextColor(if (accent) t.onAccent else t.specialText)
        }
        message.setTextColor(t.stripText)
    }

    private object ButtonRole : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    }

    companion object {
        /** The row's fixed height: the touch-target minimum, so the chips never need more than the strip. */
        const val HEIGHT_DP = 48f
        private const val MIN_TOUCH_DP = 48f
    }
}

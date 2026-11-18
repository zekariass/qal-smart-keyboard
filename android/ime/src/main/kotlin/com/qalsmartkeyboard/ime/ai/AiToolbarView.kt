package com.qalsmartkeyboard.ime.ai

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.qalsmartkeyboard.ime.R
import java.text.DateFormat
import java.util.Date

/**
 * The AI toolbar and its review panel (A2.5, SD 6-7, 11): a row above the candidate strip with
 * Translate (+ destination chip), Grammar and Rewrite (+ tone chip) and a plan hint, and below it
 * the picker, the working indicator or the review panel (original and result, alternatives,
 * Replace / Insert / Copy / Retry / Cancel). Plain Views like the rest of the keyboard (ADR 0003).
 *
 * It renders [AiToolbarController] state only when that changes (taps, results), never per key, and
 * holds user text only in its TextViews while the panel is open. Nothing here logs.
 */
class AiToolbarView(context: Context) : LinearLayout(context) {
    var controller: AiToolbarController? = null

    /** Open the companion app at [AiLink] (sign-in, AI settings, upgrade, update). */
    var onLink: (AiLink) -> Unit = {}

    private val d = resources.displayMetrics.density
    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val bg = if (dark) 0xFF1B1B1F.toInt() else 0xFFE4E5EA.toInt()
    private val chipBg = if (dark) 0xFF2B2C31.toInt() else 0xFFF7F7FA.toInt()
    private val chipOn = if (dark) 0xFF3D4F8A.toInt() else 0xFFCBD6FF.toInt()
    private val fg = if (dark) 0xFFF1F1F4.toInt() else 0xFF1B1B1F.toInt()
    private val muted = if (dark) 0xFFB5B7C0.toInt() else 0xFF55575F.toInt()
    private val warn = 0xFFB26A00.toInt()

    private val toolbar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val panelBox = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        setBackgroundColor(bg)
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, (40f * d).toInt()))
        addView(panelBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }

    /** Re-reads the controller and rebuilds the rows. Call on the main thread when the controller reports a change. */
    fun render() {
        val c = controller
        if (c == null || !c.visible) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        renderToolbar(c)
        renderPanel(c)
    }

    private fun renderToolbar(c: AiToolbarController) {
        toolbar.removeAllViews()
        val busy = c.inFlight
        toolbar.addView(chip(R.string.ai_translate, enabled = !busy) { c.onTask(AiTaskKind.TRANSLATE) })
        toolbar.addView(chip(c.destination.label, on = c.picker == AiPicker.DESTINATION, small = true) { c.openPicker(AiPicker.DESTINATION) })
        toolbar.addView(chip(R.string.ai_grammar, enabled = !busy) { c.onTask(AiTaskKind.GRAMMAR) })
        toolbar.addView(chip(R.string.ai_rewrite, enabled = !busy) { c.onTask(AiTaskKind.REWRITE) })
        toolbar.addView(chip(toneLabel(c.tone), on = c.picker == AiPicker.TONE, small = true) { c.openPicker(AiPicker.TONE) })
        toolbar.addView(View(context), LayoutParams(0, 1, 1f))
        toolbar.addView(status(c.availability))
    }

    private fun status(a: AiAvailability): TextView {
        val (text, color) = when (a.block) {
            AiBlock.OFFLINE -> context.getString(R.string.ai_status_offline) to warn
            AiBlock.SIGNED_OUT -> context.getString(R.string.ai_status_signed_out) to warn
            AiBlock.UPDATE_REQUIRED -> context.getString(R.string.ai_status_update) to warn
            AiBlock.NONE -> quotaText(a.quota[AiTaskKind.TRANSLATE]) to muted
        }
        return TextView(context).apply {
            this.text = text
            setTextColor(color)
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding((6f * d).toInt(), 0, (10f * d).toInt(), 0)
        }
    }

    private fun quotaText(q: AiQuotaHint?): String {
        val left = q?.remaining ?: return ""
        val limit = q.limit
        val period = when (q.period) {
            AiPeriod.DAILY -> R.string.ai_period_daily
            AiPeriod.WEEKLY -> R.string.ai_period_weekly
            AiPeriod.MONTHLY -> R.string.ai_period_monthly
            AiPeriod.YEARLY -> R.string.ai_period_yearly
            AiPeriod.NONE -> null
        }?.let { context.getString(it) }
        val base = if (limit != null) context.getString(R.string.ai_quota_of, left, limit) else context.getString(R.string.ai_quota_left, left)
        return if (period != null) "$base $period" else base
    }

    private fun renderPanel(c: AiToolbarController) {
        panelBox.removeAllViews()
        when (c.picker) {
            AiPicker.DESTINATION -> {
                panelBox.addView(pickerRow(c.destinations().map { d -> d.label to { c.chooseDestination(d) } }))
                return
            }
            AiPicker.TONE -> {
                val items = listOf<Pair<String, () -> Unit>>(toneLabel(null) to { c.chooseTone(null) }) +
                    AiTone.entries.map { t -> toneLabel(t) to { c.chooseTone(t) } }
                panelBox.addView(pickerRow(items))
                return
            }
            AiPicker.NONE -> Unit
        }
        when (val p = c.panel) {
            AiPanel.Closed -> Unit
            is AiPanel.Working -> panelBox.addView(workingPanel(c))
            is AiPanel.Review -> panelBox.addView(reviewPanel(c, p))
            is AiPanel.Failed -> panelBox.addView(failedPanel(c, p))
        }
    }

    private fun pickerRow(items: List<Pair<String, () -> Unit>>): View {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        items.forEach { (label, act) -> row.addView(chip(label) { act() }) }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun workingPanel(c: AiToolbarController): View = column().apply {
        addView(label(context.getString(R.string.ai_working), muted))
        addView(buttons(chip(R.string.ai_cancel) { c.cancel() }))
    }

    private fun failedPanel(c: AiToolbarController, p: AiPanel.Failed): View = column().apply {
        addView(label(failureMessage(p.failure), fg))
        val row = ArrayList<View>()
        val link = p.failure.link
        if (link != null) row += chip(linkLabel(link)) { onLink(link) }
        if (canRetry(p.failure)) row += chip(R.string.ai_retry) { c.retry() }
        row += chip(R.string.ai_close) { c.close() }
        addView(buttons(*row.toTypedArray()))
    }

    private fun canRetry(f: AiFailure) = when (f) {
        is AiFailure.QuotaExceeded, AiFailure.NotEntitled, AiFailure.OnDeviceOnly, is AiFailure.ConsentRequired,
        AiFailure.SignInRequired, AiFailure.AccountBlocked, AiFailure.UpdateRequired, AiFailure.LanguageUnsupported,
        AiFailure.ContentRejected, is AiFailure.RateLimited,
        -> false
        else -> true
    }

    private fun reviewPanel(c: AiToolbarController, p: AiPanel.Review): View = column().apply {
        addView(label(context.getString(R.string.ai_original), muted))
        addView(body(p.original, muted, maxLines = 2))
        addView(label(context.getString(R.string.ai_result), muted))
        addView(
            ScrollBox(context, (96f * d).toInt()).apply {
                addView(body(p.shownText, fg, maxLines = Int.MAX_VALUE))
            },
        )
        if (p.result.alternatives.isNotEmpty()) {
            val alts = LinearLayout(context).apply { orientation = HORIZONTAL }
            alts.addView(chip(R.string.ai_main_result, on = p.selected < 0, small = true) { c.selectAlternative(-1) })
            p.result.alternatives.forEachIndexed { i, a ->
                alts.addView(chip(a.label ?: context.getString(R.string.ai_alternative, i + 1), on = p.selected == i, small = true) { c.selectAlternative(i) })
            }
            addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(alts) })
        }
        p.result.notes.take(2).forEach { addView(label(it, muted)) }
        addView(
            buttons(
                chip(R.string.ai_replace, on = true) { feedback(c.replace()) },
                chip(R.string.ai_insert) { feedback(c.insert()) },
                chip(R.string.ai_copy) {
                    c.copy()
                    Toast.makeText(context, R.string.ai_copied, Toast.LENGTH_SHORT).show()
                },
                chip(R.string.ai_retry) { c.retry() },
                chip(R.string.ai_cancel) { c.close() },
            ),
        )
    }

    private fun feedback(a: AiApplied) {
        val msg = when (a) {
            AiApplied.INSERTED_ORIGINAL_CHANGED -> R.string.ai_inserted_instead
            AiApplied.FAILED -> R.string.ai_apply_failed
            else -> return
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- messages

    private fun failureMessage(f: AiFailure): String = when (f) {
        AiFailure.Offline -> context.getString(R.string.ai_err_offline)
        AiFailure.Timeout -> context.getString(R.string.ai_err_timeout)
        AiFailure.Busy -> context.getString(R.string.ai_err_busy)
        is AiFailure.QuotaExceeded -> {
            val reset = f.resetAtMs?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
            val base = if (reset != null) context.getString(R.string.ai_err_quota_reset, reset) else context.getString(R.string.ai_err_quota)
            if (f.canUpgrade) base + " " + context.getString(R.string.ai_err_quota_upgrade) else base
        }
        is AiFailure.RateLimited -> context.getString(R.string.ai_err_rate_limited)
        AiFailure.NotEntitled -> context.getString(R.string.ai_err_not_entitled)
        AiFailure.OnDeviceOnly -> context.getString(R.string.ai_err_on_device_only)
        is AiFailure.ConsentRequired -> context.getString(R.string.ai_err_consent)
        AiFailure.GuardrailFailed -> context.getString(R.string.ai_err_guardrail)
        AiFailure.ContentRejected -> context.getString(R.string.ai_err_content_rejected)
        AiFailure.LanguageUnsupported -> context.getString(R.string.ai_err_language)
        AiFailure.SignInRequired -> context.getString(R.string.ai_err_sign_in)
        AiFailure.AccountBlocked -> context.getString(R.string.ai_err_account)
        AiFailure.UpdateRequired -> context.getString(R.string.ai_err_update)
        AiFailure.ServiceUnavailable -> context.getString(R.string.ai_err_unavailable)
        AiFailure.Other -> context.getString(R.string.ai_err_other)
        AiFailure.NothingToProcess -> context.getString(R.string.ai_err_nothing)
        AiFailure.TooLong -> context.getString(R.string.ai_err_too_long)
    }

    private fun linkLabel(l: AiLink): String = context.getString(
        when (l) {
            AiLink.SIGN_IN -> R.string.ai_link_sign_in
            AiLink.AI_SETTINGS, AiLink.SETTINGS -> R.string.ai_link_settings
            AiLink.UPGRADE -> R.string.ai_link_upgrade
            AiLink.UPDATE_APP -> R.string.ai_link_update
        },
    )

    private fun toneLabel(t: AiTone?): String = context.getString(
        when (t) {
            null -> R.string.ai_tone_natural
            AiTone.FRIENDLY -> R.string.ai_tone_friendly
            AiTone.POLITE -> R.string.ai_tone_polite
            AiTone.PROFESSIONAL -> R.string.ai_tone_professional
            AiTone.CONCISE -> R.string.ai_tone_concise
            AiTone.APOLOGETIC -> R.string.ai_tone_apologetic
            AiTone.HONORIFIC -> R.string.ai_tone_honorific
        },
    )

    // ---------------------------------------------------------------- view helpers

    private fun column() = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding((10f * d).toInt(), (4f * d).toInt(), (10f * d).toInt(), (6f * d).toInt())
    }

    private fun label(text: String, color: Int) = TextView(context).apply {
        this.text = text
        setTextColor(color)
        textSize = 12f
    }

    private fun body(text: String, color: Int, maxLines: Int) = TextView(context).apply {
        this.text = text
        setTextColor(color)
        textSize = 16f
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun buttons(vararg views: View): View = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply { orientation = HORIZONTAL; views.forEach { addView(it) } })
    }

    private fun chip(res: Int, on: Boolean = false, enabled: Boolean = true, small: Boolean = false, click: () -> Unit) =
        chip(context.getString(res), on, enabled, small, click)

    private fun chip(text: String, on: Boolean = false, enabled: Boolean = true, small: Boolean = false, click: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = if (small) 13f else 14f
            setTextColor(if (enabled) fg else muted)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minHeight = (32f * d).toInt()
            setPadding((12f * d).toInt(), 0, (12f * d).toInt(), 0)
            background = GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(if (on) chipOn else chipBg)
                setStroke((1f * d).toInt(), if (dark) Color.DKGRAY else Color.LTGRAY)
            }
            isEnabled = enabled
            isClickable = enabled
            contentDescription = text
            setOnClickListener { click() }
            layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins((3f * d).toInt(), (2f * d).toInt(), (3f * d).toInt(), (2f * d).toInt())
            }
        }

    /** A ScrollView that never grows past [maxPx]. */
    private class ScrollBox(context: Context, private val maxPx: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
        }
    }
}

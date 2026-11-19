package com.qalsmartkeyboard.ime.ai

import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import com.qalsmartkeyboard.ime.toolbar.ToolIcon
import com.qalsmartkeyboard.ime.toolbar.ToolIconDrawable
import com.qalsmartkeyboard.ime.toolbar.ToolIcons

/**
 * The AI toolbar (A2.5, SD 6-7, 11) in two parts:
 *
 * - [actionsRow]: Translate (+ destination chip), Grammar and Rewrite (+ tone chip) and the plan
 *   state (quota hint, or a "Sign in to use AI" / "Update needed" link, or "Offline"). It is not a
 *   row of its own: [com.qalsmartkeyboard.ime.KeyboardBar] shows it in the middle of the one bar
 *   above the keys when the user opens the AI entry. It scrolls sideways; no chip is ever truncated.
 * - this view: the panel above that bar, shown only while there is something in it: the picker, the
 *   working indicator, an error with its link, or the review panel (original and result,
 *   alternatives, Replace / Insert / Copy / Retry / Cancel). The idle keyboard shows no panel.
 *
 * Plain Views like the rest of the keyboard (ADR 0003); each chip has the Button role for
 * accessibility. It renders [AiToolbarController] state only when that changes (taps, results),
 * never per key, and holds user text only in its TextViews while the panel is open. Nothing here logs.
 */
class AiToolbarView(context: Context) : LinearLayout(context) {
    var controller: AiToolbarController? = null

    /** Open the companion app at [AiLink] (sign-in, AI settings, upgrade, update). */
    var onLink: (AiLink) -> Unit = {}

    /** The "Assist" chip: opens the AI Assist panel in place of the keys (ADR 0031). */
    var onOpenAssist: () -> Unit = {}

    /** The "Email" chip: opens the Write Email panel in place of the keys. */
    var onOpenEmail: () -> Unit = {}

    /** The field looks like an e-mail body (a mail app's compose field): the Email chip comes first and stands out. */
    var emailFirst: Boolean = false

    private val d = resources.displayMetrics.density
    /** The colours to draw with. Set by the service when the chosen theme changes; the rows are rebuilt with them. */
    var theme: KeyboardTheme = ThemeCatalog.classic(
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
        set(value) {
            if (field === value) return
            field = value
            setBackgroundColor(bg)
            render()
        }

    private val dark get() = !theme.isLight
    private val bg get() = theme.stripBg
    private val chipBg get() = theme.key
    private val chipOn get() = theme.pressed
    private val fg get() = theme.keyText
    private val muted get() = theme.hintText
    private val warn get() = theme.warn
    private val chipStroke get() = if (theme.hasBorder) theme.border else theme.special

    private val toolbar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val panelBox = LinearLayout(context).apply { orientation = VERTICAL }

    /** The AI actions, laid out by the bar in its middle (transparent: the bar's background shows). */
    val actionsRow: HorizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        // Chips cut by the row's edge fade out, so they read as "scroll for more", not as truncated.
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength((24f * d).toInt())
        addView(toolbar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** Called after every [render] (the bar re-reads [AiToolbarController.visible]). */
    var onRendered: () -> Unit = {}

    init {
        orientation = VERTICAL
        setBackgroundColor(bg)
        addView(panelBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }

    /** Re-reads the controller and rebuilds the actions and the panel. Call on the main thread when the controller reports a change. */
    fun render() {
        val c = controller
        if (c == null || !c.visible) {
            toolbar.removeAllViews()
            panelBox.removeAllViews()
            visibility = GONE
            onRendered()
            return
        }
        renderToolbar(c)
        renderPanel(c)
        visibility = if (panelBox.childCount > 0) VISIBLE else GONE
        onRendered()
    }

    private fun renderToolbar(c: AiToolbarController) {
        toolbar.removeAllViews()
        val busy = c.inFlight
        val state = status(c.availability)
        // A block (signed out, update, offline) explains why the actions will not run: show it first.
        val blocked = c.availability.block != AiBlock.NONE
        if (blocked && state != null) toolbar.addView(state)
        val email = chip(context.getString(R.string.ai_email_chip), on = emailFirst, icon = ToolIcon.EMAIL) { onOpenEmail() }
        if (emailFirst) toolbar.addView(email)
        // The direction control (ADR 0031): [ Auto · Amharic ⇄ English ] and the primary Translate action next to it.
        toolbar.addView(directionGroup(c))
        val dest = c.destination.label
        val wide = resources.configuration.screenWidthDp >= WIDE_DP
        toolbar.addView(
            chip(
                if (wide) context.getString(R.string.ai_translate_to, dest) else context.getString(R.string.ai_translate),
                enabled = !busy, primary = true, icon = ToolIcon.TRANSLATE, description = context.getString(R.string.ai_translate_to, dest),
            ) { c.onTask(AiTaskKind.TRANSLATE) },
        )
        toolbar.addView(chip(context.getString(R.string.ai_assist_chip), icon = ToolIcon.AI, description = context.getString(R.string.ai_assist_chip_description)) { onOpenAssist() })
        if (!emailFirst) toolbar.addView(email)
        toolbar.addView(chip(context.getString(R.string.ai_grammar), enabled = !busy, icon = ToolIcon.GRAMMAR) { c.onTask(AiTaskKind.GRAMMAR) })
        toolbar.addView(chip(context.getString(R.string.ai_rewrite), enabled = !busy, icon = ToolIcon.REWRITE) { c.onTask(AiTaskKind.REWRITE) })
        toolbar.addView(chip(toneLabel(c.tone), on = c.picker == AiPicker.TONE, small = true) { c.openPicker(AiPicker.TONE) })
        if (!blocked && state != null) toolbar.addView(state)
    }

    /** `[ Auto · Amharic ⇄ English ]`: each side opens its language list above the bar; ⇄ swaps. */
    private fun directionGroup(c: AiToolbarController): View {
        val destLabel = c.destination.label // also brings the Auto source up to date with the keyboard language
        val sourceLabel = c.direction.sourceLabel(context.getString(R.string.ai_auto))
        val group = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(chipBg)
                setStroke((1f * d).toInt(), chipStroke)
            }
            layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins((3f * d).toInt(), (2f * d).toInt(), (3f * d).toInt(), (2f * d).toInt())
            }
        }
        fun side(text: String, on: Boolean, description: String, click: () -> Unit) = TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(fg)
            isSingleLine = true
            gravity = Gravity.CENTER
            minHeight = (32f * d).toInt()
            setPadding((10f * d).toInt(), 0, (10f * d).toInt(), 0)
            if (on) background = GradientDrawable().apply { cornerRadius = 16f * d; setColor(chipOn) }
            isSelected = on
            contentDescription = description
            isClickable = true
            setOnClickListener { click() }
            asButton(this)
        }
        group.addView(side(sourceLabel, c.picker == AiPicker.SOURCE, context.getString(R.string.ai_direction_source, sourceLabel)) { c.openPicker(AiPicker.SOURCE) })
        group.addView(
            TextView(context).apply {
                val size = (32f * d).toInt()
                minWidth = size
                minHeight = size
                gravity = Gravity.CENTER
                setCompoundDrawables(AiIconDrawable(AiIcons.SWAP, fg, (18f * d).toInt()).also { it.setBounds(0, 0, (18f * d).toInt(), (18f * d).toInt()) }, null, null, null)
                setPadding((7f * d).toInt(), 0, (7f * d).toInt(), 0)
                contentDescription = context.getString(R.string.ai_direction_swap)
                isClickable = true
                setOnClickListener { c.swapDirection() }
                asButton(this)
            },
        )
        group.addView(side(destLabel, c.picker == AiPicker.DESTINATION, context.getString(R.string.ai_direction_destination, destLabel)) { c.openPicker(AiPicker.DESTINATION) })
        return group
    }

    /** The plan state: a link (sign in, update) where the user can fix it, else plain text; null when there is nothing to say. */
    private fun status(a: AiAvailability): TextView? {
        val text: String
        val color: Int
        var link: AiLink? = null
        when (a.block) {
            AiBlock.OFFLINE -> { text = context.getString(R.string.ai_status_offline); color = warn }
            AiBlock.SIGNED_OUT -> { text = context.getString(R.string.ai_status_signed_out); color = warn; link = AiLink.SIGN_IN }
            AiBlock.UPDATE_REQUIRED -> { text = context.getString(R.string.ai_status_update); color = warn; link = AiLink.UPDATE_APP }
            AiBlock.NONE -> { text = quotaText(a.quota[AiTaskKind.TRANSLATE]); color = muted }
        }
        if (text.isEmpty()) return null
        val target = link
        return TextView(context).apply {
            this.text = text
            setTextColor(color)
            textSize = 13f
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
            minHeight = (32f * d).toInt()
            setPadding((8f * d).toInt(), 0, (10f * d).toInt(), 0)
            contentDescription = text
            if (target != null) {
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                isClickable = true
                setOnClickListener { onLink(target) }
                asButton(this)
            }
            layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
            AiPicker.SOURCE -> {
                val current = c.direction.manualSource
                val items = listOf<Pair<String, () -> Unit>>(
                    mark(context.getString(R.string.ai_auto_detect), current == null) to { c.chooseSource(null) },
                ) + AiLanguages.ALL.map { code -> mark(AiLanguages.name(code), current == code) to { c.chooseSource(code) } }
                panelBox.addView(pickerRow(items, context.getString(R.string.ai_translate_from)))
                return
            }
            AiPicker.DESTINATION -> {
                val current = c.destination
                // Plain languages first (the source language itself is left out), then the user's destination profiles.
                val plain = AiLanguages.ALL.filter { it != c.direction.source }.map(AiLanguages::plain)
                val profiles = c.destinations().filter { it.isProfile }
                val items = (plain + profiles).map { d -> mark(d.label, d.id == current.id) to { c.chooseDestination(d) } }
                panelBox.addView(pickerRow(items, context.getString(R.string.ai_translate_into)))
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

    /** "✓ English" for the current choice (the check, the selected state and the spoken "selected" mark it). */
    private fun mark(label: String, current: Boolean) = if (current) "✓ $label" else label

    private fun pickerRow(items: List<Pair<String, () -> Unit>>, title: String? = null): View {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (title != null) row.addView(label(title, muted).apply { setPadding((8f * d).toInt(), 0, (4f * d).toInt(), 0) })
        items.forEach { (label, act) -> row.addView(chip(label, on = label.startsWith("✓ ")) { act() }) }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /** "Amharic → English" (the detected source when the server reported one, else the source the control shows). */
    private fun directionText(c: AiToolbarController, task: AiTask.Translate, detected: String?): String {
        val from = detected?.takeIf { it in AiLanguages.ALL }?.let(AiLanguages::name)
            ?: task.sourceLanguage?.let(AiLanguages::name)
            ?: c.direction.sourceLabel(context.getString(R.string.ai_auto))
        return "$from → ${task.destination.label}"
    }

    private fun workingPanel(c: AiToolbarController): View = column().apply {
        val task = (c.panel as? AiPanel.Working)?.task
        if (task is AiTask.Translate) addView(label(context.getString(R.string.ai_working_direction, directionText(c, task, null)), fg))
        addView(label(context.getString(R.string.ai_working), muted).apply { ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE) })
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
        AiFailure.ContentRejected, is AiFailure.RateLimited, is AiFailure.InputTooLong, is AiFailure.Invalid,
        -> false
        else -> true
    }

    private fun reviewPanel(c: AiToolbarController, p: AiPanel.Review): View = column().apply {
        val task = p.task
        if (task is AiTask.Translate) addView(label(directionText(c, task, p.result.sourceLanguage), fg).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
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

    private fun failureMessage(f: AiFailure): String = AiMessages.failure(context, f)

    private fun linkLabel(l: AiLink): String = AiMessages.link(context, l)

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
        chip(context.getString(res), on, enabled, small, click = click)

    /**
     * [primary]: the accent fill (the direction control's Translate). [icon]: a leading icon of the keyboard's icon family
     * ([ToolIcons], ADR 0035). Tonal chips on the key fill; an outline only in themes that draw key borders.
     */
    private fun chip(
        text: String,
        on: Boolean = false,
        enabled: Boolean = true,
        small: Boolean = false,
        primary: Boolean = false,
        icon: ToolIcon? = null,
        description: String? = null,
        click: () -> Unit,
    ): TextView =
        TextView(context).apply {
            this.text = text
            textSize = if (small) 13f else 14f
            val color = when {
                !enabled -> muted
                primary -> theme.onAccent
                else -> fg
            }
            setTextColor(color)
            if (primary) setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            // One line, never ellipsized: every chip row scrolls sideways instead.
            isSingleLine = true
            minHeight = (CHIP_DP * d).toInt()
            setPadding(((if (icon != null) 10f else 14f) * d).toInt(), 0, (14f * d).toInt(), 0)
            if (icon != null) {
                val px = (18f * d).toInt()
                setCompoundDrawables(ToolIconDrawable(icon, color, px).also { it.setBounds(0, 0, px, px) }, null, null, null)
                compoundDrawablePadding = (6f * d).toInt()
            }
            background = GradientDrawable().apply {
                cornerRadius = CHIP_DP / 2 * d
                setColor(if (primary && enabled) theme.accent else if (on) chipOn else chipBg)
                if (!(primary && enabled) && (theme.hasBorder || on)) setStroke((1f * d).toInt(), if (on) theme.accentText else chipStroke)
            }
            isEnabled = enabled
            isClickable = enabled
            isSelected = on
            contentDescription = description ?: text
            setOnClickListener { click() }
            asButton(this)
            layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins((4f * d).toInt(), (2f * d).toInt(), (4f * d).toInt(), (2f * d).toInt())
            }
        }

    /** Gives a clickable TextView the Button role for accessibility services. */
    private fun asButton(v: View) {
        ViewCompat.setAccessibilityDelegate(
            v,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            },
        )
    }

    /** A ScrollView that never grows past [maxPx]. */
    private class ScrollBox(context: Context, private val maxPx: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
        }
    }

    companion object {
        /** From this screen width (dp) the Translate chip names its destination ("Translate to English"); narrower bars show "Translate". */
        const val WIDE_DP = 600

        /** Chip height (dp) in the 44 dp bar. */
        const val CHIP_DP = 34f
    }
}

package com.qalsmartkeyboard.ime.ai

import android.content.Context
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
import android.widget.Toast
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog

/**
 * The quick actions' panel above the bar (A2.5, SD 6-7, 11; ADR 0041): shown only while there is something in it: the
 * working indicator, an error with its link, or the review panel (original and result, alternatives, Replace / Insert /
 * Copy / Retry / Cancel). The idle keyboard shows no panel. The actions themselves are tiles of the AI tools grid
 * ([AiToolsPanelView]); the narrow AI row that used to sit in the bar's middle is retired (ADR 0041).
 *
 * Plain Views like the rest of the keyboard (ADR 0003); each chip has the Button role for
 * accessibility. It renders [AiToolbarController] state only when that changes (taps, results),
 * never per key, and holds user text only in its TextViews while the panel is open. Nothing here logs.
 */
class AiToolbarView(context: Context) : LinearLayout(context) {
    var controller: AiToolbarController? = null

    /** Open the companion app at [AiLink] (sign-in, AI settings, upgrade, update). */
    var onLink: (AiLink) -> Unit = {}

    private val d = resources.displayMetrics.density
    /** The colours to draw with. Set by the service when the chosen theme changes; the panel is rebuilt with them. */
    var theme: KeyboardTheme = ThemeCatalog.classic(
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
        set(value) {
            if (field === value) return
            field = value
            setBackgroundColor(bg)
            render()
        }

    private val bg get() = theme.stripBg
    private val chipBg get() = theme.key
    private val chipOn get() = theme.pressed
    private val fg get() = theme.keyText
    private val muted get() = theme.hintText
    private val chipStroke get() = if (theme.hasBorder) theme.border else theme.special

    private val panelBox = LinearLayout(context).apply { orientation = VERTICAL }

    /** Called after every [render] (the bar re-reads [AiToolbarController.visible] and the plan state). */
    var onRendered: () -> Unit = {}

    init {
        orientation = VERTICAL
        setBackgroundColor(bg)
        addView(panelBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }

    /** Re-reads the controller and rebuilds the panel. Call on the main thread when the controller reports a change. */
    fun render() {
        val c = controller
        if (c == null || !c.visible) {
            panelBox.removeAllViews()
            visibility = GONE
            onRendered()
            return
        }
        renderPanel(c)
        visibility = if (panelBox.childCount > 0) VISIBLE else GONE
        onRendered()
    }

    private fun renderPanel(c: AiToolbarController) {
        panelBox.removeAllViews()
        when (val p = c.panel) {
            AiPanel.Closed -> Unit
            is AiPanel.Working -> panelBox.addView(workingPanel(c))
            is AiPanel.Review -> panelBox.addView(reviewPanel(c, p))
            is AiPanel.Failed -> panelBox.addView(failedPanel(c, p))
        }
    }

    private fun workingPanel(c: AiToolbarController): View = column().apply {
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

    /**
     * A panel's buttons. They wrap onto a second line instead of scrolling sideways (ADR 0044: at 360 dp the review's last
     * button, Cancel, was cut off at the right edge with nothing saying the row scrolls).
     */
    private fun buttons(vararg views: View): View = WrapRow(context).apply {
        tag = BUTTONS_TAG
        views.forEach { addView(it) }
    }

    private fun chip(res: Int, on: Boolean = false, enabled: Boolean = true, small: Boolean = false, click: () -> Unit) =
        chip(context.getString(res), on, enabled, small, click)

    /** Tonal chips on the key fill; an outline only in themes that draw key borders (and on the chosen one). */
    private fun chip(text: String, on: Boolean = false, enabled: Boolean = true, small: Boolean = false, click: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = if (small) 13f else 14f
            setTextColor(if (enabled) fg else muted)
            gravity = Gravity.CENTER
            // One line; button rows wrap (a chip wider than the whole row would ellipsize), the alternatives row scrolls.
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            minHeight = (CHIP_DP * d).toInt()
            setPadding((14f * d).toInt(), 0, (14f * d).toInt(), 0)
            background = GradientDrawable().apply {
                cornerRadius = CHIP_DP / 2 * d
                setColor(if (on) chipOn else chipBg)
                if (theme.hasBorder || on) setStroke((1f * d).toInt(), if (on) theme.accentText else chipStroke)
            }
            isEnabled = enabled
            isClickable = enabled
            isSelected = on
            contentDescription = text
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

    /** The button row of the panel shown now (tests: every button fits), or null. */
    internal fun buttonRow(): WrapRow? = panelBox.findViewWithTag(BUTTONS_TAG)

    companion object {
        /** Chip height (dp). */
        const val CHIP_DP = 34f

        private const val BUTTONS_TAG = "ai-quick-buttons"
    }
}

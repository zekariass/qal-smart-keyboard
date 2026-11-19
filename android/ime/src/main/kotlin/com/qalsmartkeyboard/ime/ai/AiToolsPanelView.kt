package com.qalsmartkeyboard.ime.ai

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.toolbar.GridTile
import com.qalsmartkeyboard.ime.toolbar.TileGridView

/**
 * The AI tools grid (ADR 0041): opened by the "AI" entry (the bar's shortcut or the tools panel's tile), it takes the key
 * area at the keyboard's height like the tools and clipboard panels (ADR 0035), the bar showing a back arrow and "AI tools".
 * One tile per AI feature that works today ([AiToolSet]); a tile the cached plan excludes carries the lock and still opens
 * so it can say why (ADR 0031). Signed out, offline or an update needed are one compact line with its action above the
 * tiles ([notice]); otherwise the remaining translation allowance, when the plan state knows it. Opening the grid reads only
 * the cached plan state; nothing here reads or sends text: a tile runs its flow through [onTool].
 */
class AiToolsPanelView(context: Context) : TileGridView<AiTool>(context) {
    /** The tiles to show (refreshed on every open and plan-state change). */
    var tiles: () -> List<AiToolEntry> = { emptyList() }

    /** The cached plan state (the notice line); null before the AI client is attached. */
    var availability: () -> AiAvailability? = { null }

    /** A tile was tapped. */
    var onTool: (AiTool) -> Unit = {}

    /** Open the companion app (sign in, update). */
    var onLink: (AiLink) -> Unit = {}

    /** The tools shown by the last [render], in order (tests). */
    var shown: List<AiToolEntry> = emptyList()
        private set

    /** The notice line of the last [render] (tests), or null. */
    internal var noticeView: View? = null
        private set

    init {
        gridTiles = {
            tiles().also { shown = it }.map {
                GridTile(it.tool, it.tool.icon, AiMessages.tool(context, it.tool), it.locked, AiMessages.toolDescription(context, it.tool))
            }
        }
        onGridTile = { onTool(it) }
    }

    /** The tile view of [tool] in the current grid, or null. */
    fun tileFor(tool: AiTool): View? = tileView(tool)

    override fun notice(): View? {
        val a = availability()
        val (text, link) = when (a?.block) {
            null -> null to null
            AiBlock.SIGNED_OUT -> context.getString(R.string.ai_status_signed_out) to AiLink.SIGN_IN
            AiBlock.UPDATE_REQUIRED -> context.getString(R.string.ai_status_update) to AiLink.UPDATE_APP
            AiBlock.OFFLINE -> context.getString(R.string.ai_grid_offline) to null
            AiBlock.NONE -> AiMessages.quota(context, a.quota[AiTaskKind.TRANSLATE]).takeIf { it.isNotEmpty() }
                ?.let { context.getString(R.string.ai_grid_quota, context.getString(R.string.ai_translate), it) } to null
        }
        if (text == null) return null.also { noticeView = null }
        val blocked = a?.block != AiBlock.NONE
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(40f)
            setPadding(dp(8f), 0, dp(4f), dp(2f))
        }
        row.addView(
            TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (blocked) theme.warn else theme.hintText)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (link != null) row.addView(linkChip(link), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36f)).apply { marginStart = dp(8f) })
        noticeView = row
        return row
    }

    /** The notice's action ("Sign in", "Update"): the accent fill, one line, never shortened. */
    private fun linkChip(link: AiLink) = TextView(context).apply {
        text = AiMessages.link(context, link)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(theme.onAccent)
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(16f), 0, dp(16f), 0)
        minWidth = dp(48f)
        background = GradientDrawable().apply {
            cornerRadius = 18f * d
            setColor(theme.accent)
        }
        isClickable = true
        isFocusable = true
        contentDescription = text
        setOnClickListener { onLink(link) }
        asButton(this)
    }
}

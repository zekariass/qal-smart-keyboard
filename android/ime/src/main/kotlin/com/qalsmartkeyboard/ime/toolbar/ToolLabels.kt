package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import com.qalsmartkeyboard.ime.R

/** The short name of a tool (tile label, shortcut and TalkBack name), in the UI language (ADR 0032). */
fun toolLabel(c: Context, tool: Tool): String = c.getString(
    when (tool) {
        Tool.TRANSLATE -> R.string.ai_translate
        Tool.AI -> R.string.top_bar_ai_open
        Tool.CLIPBOARD -> R.string.tool_clipboard
        Tool.EMOJI -> R.string.key_emoji_description
        Tool.VOICE -> R.string.voice_mic
        Tool.WORD_ART -> R.string.wordart_tab
        Tool.WRITING_MODE -> R.string.tool_writing_mode
        Tool.LAYOUT -> R.string.tool_layout
        Tool.CALCULATOR -> R.string.tool_calculator
        Tool.UNIT_CONVERTER -> R.string.tool_unit_converter
        Tool.CURRENCY -> R.string.tool_currency
        Tool.CALENDAR -> R.string.tool_calendar
        Tool.QR -> R.string.tool_qr
        Tool.DRAW -> R.string.tool_draw
        Tool.LANGUAGES -> R.string.tool_languages
        Tool.THEME -> R.string.tool_theme
        Tool.SETTINGS -> R.string.tool_settings
    },
)

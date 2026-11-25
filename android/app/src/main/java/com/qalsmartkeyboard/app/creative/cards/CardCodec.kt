package com.qalsmartkeyboard.app.creative.cards

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The saved-card JSON (the editable source stored next to the rendered image). Versioned; decoding is forgiving about
 * unknown or missing optional fields (defaults) and strict about the shape, so a damaged file reads as null, never as a
 * half card. Texts are cut to their limits on the way in.
 */
object CardCodec {
    const val VERSION = 1

    fun encode(d: CardDoc): String = JSONObject().apply {
        put("v", VERSION)
        put("template", d.templateId)
        put("headline", d.headline)
        put("body", d.body)
        put("from", d.from)
        put("aspect", d.aspect.id)
        put("format", d.format.id)
        put("frame", d.frame.id)
        put("background", background(d.background))
        put(
            "text",
            JSONObject().apply {
                put("font", d.text.font.id)
                put("scale", d.text.sizeScale.toDouble())
                put("color", d.text.color)
                put("align", d.text.align.id)
                put("bold", d.text.bold)
                put("shadow", d.text.shadow)
            },
        )
    }.toString()

    /** The card in [json], or null when it is not a card this version can read. */
    fun decode(json: String): CardDoc? = try {
        val o = JSONObject(json)
        if (o.optInt("v", 0) !in 1..VERSION) {
            null
        } else {
            val t = o.optJSONObject("text") ?: JSONObject()
            CardDoc(
                templateId = o.getString("template"),
                headline = o.optString("headline").take(CardDoc.MAX_HEADLINE),
                body = o.optString("body").take(CardDoc.MAX_BODY),
                from = o.optString("from").take(CardDoc.MAX_FROM),
                background = decodeBackground(o.getJSONObject("background")),
                text = CardTextStyle(
                    font = CardFont.of(t.optString("font")),
                    sizeScale = t.optDouble("scale", 1.0).toFloat().coerceIn(CardTextStyle.MIN_SCALE, CardTextStyle.MAX_SCALE),
                    color = t.optInt("color", CardTextStyle().color),
                    align = CardTextAlign.of(t.optString("align")),
                    bold = t.optBoolean("bold", false),
                    shadow = t.optBoolean("shadow", false),
                ),
                frame = CardFrame.of(o.optString("frame")),
                aspect = CardAspect.of(o.optString("aspect")),
                format = ExportFormat.of(o.optString("format")),
            )
        }
    } catch (e: JSONException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun background(b: CardBackground): JSONObject = JSONObject().apply {
        when (b) {
            is CardBackground.Solid -> { put("type", "solid"); put("color", b.color) }
            is CardBackground.Gradient -> {
                put("type", "gradient")
                put("preset", b.presetId)
                put("colors", JSONArray(b.colors))
                put("angle", b.angleDeg)
            }
            is CardBackground.Pattern -> {
                put("type", "pattern")
                put("kind", b.kind.id)
                put("base", b.base)
                put("accent", b.accent)
            }
            is CardBackground.Photo -> {
                put("type", "photo")
                if (b.file != null) put("file", b.file)
                put("dim", b.dimPercent)
            }
        }
    }

    private fun decodeBackground(o: JSONObject): CardBackground = when (o.getString("type")) {
        "solid" -> CardBackground.Solid(o.getInt("color"))
        "gradient" -> {
            val a = o.getJSONArray("colors")
            CardBackground.Gradient(o.optString("preset"), List(a.length()) { a.getInt(it) }, o.optInt("angle", 135))
        }
        "pattern" -> CardBackground.Pattern(PatternKind.of(o.optString("kind")), o.getInt("base"), o.getInt("accent"))
        "photo" -> CardBackground.Photo(
            file = o.optString("file").takeIf { it.isNotEmpty() },
            dimPercent = o.optInt("dim", 30).coerceIn(0, CardBackground.MAX_DIM),
        )
        else -> throw JSONException("unknown background")
    }
}

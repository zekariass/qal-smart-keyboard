package com.qalsmartkeyboard.app.creative.annotate

import com.qalsmartkeyboard.app.creative.cards.ExportFormat
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The editable draft of an annotation: which picture it is over ([source], a content URI the user picked, read again when
 * the draft is continued: the picture's pixels are never copied anywhere), the objects and the export format. Lives
 * only in the app's no-backup storage ([AnnotationDraftStore]); a saved or shared picture is always a flattened image.
 */
data class AnnotDraft(val source: String, val doc: AnnotDoc, val format: ExportFormat = ExportFormat.PNG)

/**
 * The draft JSON. Versioned; strict about the shape (a damaged file reads as null, never as half a draft), bounded
 * (objects, points, text length) and it refuses coordinates that are not finite numbers.
 */
object AnnotationCodec {
    const val VERSION = 1

    fun encode(d: AnnotDraft): String = JSONObject().apply {
        put("v", VERSION)
        put("src", d.source)
        put("w", d.doc.width)
        put("h", d.doc.height)
        put("next", d.doc.nextId)
        put("format", d.format.id)
        d.doc.crop?.let { put("crop", JSONArray(listOf(it.left, it.top, it.right, it.bottom))) }
        put("items", JSONArray().apply { d.doc.items.forEach { put(item(it)) } })
    }.toString()

    fun decode(json: String): AnnotDraft? = try {
        val o = JSONObject(json)
        if (o.optInt("v", 0) !in 1..VERSION) {
            null
        } else {
            val arr = o.getJSONArray("items")
            if (arr.length() > AnnotDoc.MAX_ITEMS) throw JSONException("too many items")
            val items = List(arr.length()) { decodeItem(arr.getJSONObject(it)) }
            if (items.map { it.id }.toSet().size != items.size) throw JSONException("duplicate ids")
            val crop = o.optJSONArray("crop")?.let { c ->
                if (c.length() != 4) throw JSONException("crop")
                PixelRect(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3))
            }
            val next = maxOf(o.optInt("next", 1), (items.maxOfOrNull { it.id } ?: 0) + 1)
            AnnotDraft(
                source = o.getString("src"),
                doc = AnnotDoc(o.getInt("w"), o.getInt("h"), items, crop, next),
                format = ExportFormat.of(o.optString("format")),
            )
        }
    } catch (e: JSONException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun item(a: Annotation): JSONObject = JSONObject().apply {
        put("id", a.id)
        when (a) {
            is Annotation.Ink -> {
                put("t", "ink"); put("c", a.color); put("w", a.width.toDouble()); put("hl", a.highlighter); put("pts", points(a.points))
            }
            is Annotation.Arrow -> {
                put("t", "arrow"); put("c", a.color); put("w", a.width.toDouble()); put("pts", points(listOf(a.from, a.to)))
            }
            is Annotation.Shape -> {
                put("t", "shape"); put("kind", a.kind.id); put("c", a.color); put("w", a.width.toDouble()); put("box", box(a.box))
            }
            is Annotation.Label -> {
                put("t", "label"); put("c", a.color); put("size", a.size.toDouble()); put("text", a.text); put("plate", a.backdrop); put("pts", points(listOf(a.at)))
            }
            is Annotation.Step -> {
                put("t", "step"); put("c", a.color); put("n", a.number); put("r", a.radius.toDouble()); put("pts", points(listOf(a.at)))
            }
            is Annotation.Spotlight -> {
                put("t", "spot"); put("box", box(a.box))
            }
            is Annotation.Redaction -> {
                put("t", "redact"); put("mode", a.mode.id); put("c", a.color)
                when (val area = a.area) {
                    is RedactArea.Rect -> put("box", box(area.box))
                    is RedactArea.Brush -> { put("pts", points(area.points)); put("r", area.radius.toDouble()) }
                }
            }
        }
    }

    private fun points(ps: List<Pt>) = JSONArray().apply { ps.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } }

    private fun box(b: Box) = JSONArray(listOf(b.left.toDouble(), b.top.toDouble(), b.right.toDouble(), b.bottom.toDouble()))

    private fun num(v: Double): Float {
        val f = v.toFloat()
        if (!f.isFinite()) throw JSONException("not a finite number")
        return f
    }

    private fun decodePoints(a: JSONArray): List<Pt> {
        if (a.length() % 2 != 0 || a.length() == 0 || a.length() / 2 > AnnotDoc.MAX_POINTS) throw JSONException("points")
        return List(a.length() / 2) { Pt(num(a.getDouble(2 * it)), num(a.getDouble(2 * it + 1))) }
    }

    private fun decodeBox(a: JSONArray): Box {
        if (a.length() != 4) throw JSONException("box")
        return Box(num(a.getDouble(0)), num(a.getDouble(1)), num(a.getDouble(2)), num(a.getDouble(3)))
    }

    private fun positive(v: Double): Float = num(v).also { if (it <= 0f) throw JSONException("size") }

    private fun decodeItem(o: JSONObject): Annotation {
        val id = o.getInt("id")
        return when (o.getString("t")) {
            "ink" -> Annotation.Ink(id, decodePoints(o.getJSONArray("pts")), o.getInt("c"), positive(o.getDouble("w")), o.optBoolean("hl", false))
            "arrow" -> decodePoints(o.getJSONArray("pts")).let {
                if (it.size != 2) throw JSONException("arrow")
                Annotation.Arrow(id, it[0], it[1], o.getInt("c"), positive(o.getDouble("w")))
            }
            "shape" -> Annotation.Shape(
                id, ShapeKind.of(o.getString("kind")) ?: throw JSONException("kind"), decodeBox(o.getJSONArray("box")), o.getInt("c"), positive(o.getDouble("w")),
            )
            "label" -> Annotation.Label(
                id, decodePoints(o.getJSONArray("pts")).first(), o.getString("text").take(Annotation.MAX_TEXT), o.getInt("c"),
                positive(o.getDouble("size")), o.optBoolean("plate", true),
            )
            "step" -> Annotation.Step(id, decodePoints(o.getJSONArray("pts")).first(), o.getInt("n"), o.getInt("c"), positive(o.getDouble("r")))
            "spot" -> Annotation.Spotlight(id, decodeBox(o.getJSONArray("box")))
            "redact" -> {
                val mode = RedactMode.of(o.getString("mode")) ?: throw JSONException("mode")
                val area = if (o.has("box")) RedactArea.Rect(decodeBox(o.getJSONArray("box")))
                else RedactArea.Brush(decodePoints(o.getJSONArray("pts")), positive(o.getDouble("r")))
                Annotation.Redaction(id, mode, area, o.optInt("c", Annotation.SOLID_DEFAULT))
            }
            else -> throw JSONException("unknown item")
        }
    }
}

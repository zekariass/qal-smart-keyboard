package com.qalsmartkeyboard.app.creative.annotate

import com.qalsmartkeyboard.aiclient.FeatureAccess

/**
 * The premium gate of the image annotator (T8.7), by the boolean platform feature `IMAGE_ANNOTATOR` ("advanced
 * tools"). The platform's gate is [com.qalsmartkeyboard.aiclient.ServerFeatureAccess]; this object only decides which
 * tools it covers, in **one table** ([isPremium]): moving a tool between free and premium is a one-line change here.
 *
 * Product rule (owner to confirm): basic annotation is free and offline; the advanced tools are the paid layer. Hiding
 * information must never depend on a plan, so the solid redaction box and the crop are free whatever the table says
 * about blur and pixelate, and a picture whose blur or pixelate is locked can always be exported after
 * [solidifyLockedRedactions] (the locked look becomes a solid box: still redacted, never less).
 */
object AnnotatorGate {
    /** THE table. `true`: needs `IMAGE_ANNOTATOR`. */
    fun isPremium(tool: AnnotTool): Boolean = when (tool) {
        AnnotTool.SELECT -> false
        AnnotTool.PEN -> false
        AnnotTool.HIGHLIGHTER -> false
        AnnotTool.ARROW -> false
        AnnotTool.RECT -> false
        AnnotTool.ELLIPSE -> false
        AnnotTool.TEXT -> false
        AnnotTool.ERASER -> false
        AnnotTool.CROP -> false
        AnnotTool.REDACT_SOLID -> false // never premium: covering information must always be free
        AnnotTool.BLUR -> true
        AnnotTool.PIXELATE -> true
        AnnotTool.STEP -> true
        AnnotTool.SPOTLIGHT -> true
    }

    val freeTools: List<AnnotTool> get() = AnnotTool.entries.filter { !isPremium(it) }
    val premiumTools: List<AnnotTool> get() = AnnotTool.entries.filter { isPremium(it) }

    fun toolAllowed(tool: AnnotTool, access: FeatureAccess) = !isPremium(tool) || access == FeatureAccess.ALLOWED

    /** The tool that makes [a]. */
    fun toolOf(a: Annotation): AnnotTool = when (a) {
        is Annotation.Ink -> if (a.highlighter) AnnotTool.HIGHLIGHTER else AnnotTool.PEN
        is Annotation.Arrow -> AnnotTool.ARROW
        is Annotation.Shape -> if (a.kind == ShapeKind.RECT) AnnotTool.RECT else AnnotTool.ELLIPSE
        is Annotation.Label -> AnnotTool.TEXT
        is Annotation.Step -> AnnotTool.STEP
        is Annotation.Spotlight -> AnnotTool.SPOTLIGHT
        is Annotation.Redaction -> when (a.mode) {
            RedactMode.BLUR -> AnnotTool.BLUR
            RedactMode.PIXELATE -> AnnotTool.PIXELATE
            RedactMode.SOLID -> AnnotTool.REDACT_SOLID
        }
    }

    /** The premium tools [doc] uses, whoever is looking. */
    fun premiumToolsUsed(doc: AnnotDoc): Set<AnnotTool> = buildSet {
        for (a in doc.items) toolOf(a).let { if (isPremium(it)) add(it) }
        if (doc.crop != null && isPremium(AnnotTool.CROP)) add(AnnotTool.CROP)
    }

    /** The tools of [doc] this device may not use now. A picture with any of them can be edited, not exported. */
    fun lockedTools(doc: AnnotDoc, access: FeatureAccess): Set<AnnotTool> =
        if (access == FeatureAccess.ALLOWED) emptySet() else premiumToolsUsed(doc)

    fun canExport(doc: AnnotDoc, access: FeatureAccess) = lockedTools(doc, access).isEmpty()

    /**
     * [doc] with every blur or pixelate this device may not use turned into a solid box over the same area (the free,
     * strongest redaction): the way to export a redacted picture without the plan. Never removes a redaction.
     */
    fun solidifyLockedRedactions(doc: AnnotDoc, access: FeatureAccess): AnnotDoc {
        val locked = lockedTools(doc, access)
        if (locked.none { it.redacts }) return doc
        return doc.copy(
            items = doc.items.map { a ->
                if (a is Annotation.Redaction && toolOf(a) in locked) a.copy(mode = RedactMode.SOLID, color = Annotation.SOLID_DEFAULT) else a
            },
        )
    }
}

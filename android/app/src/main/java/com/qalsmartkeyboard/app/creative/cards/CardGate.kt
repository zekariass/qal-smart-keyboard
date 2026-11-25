package com.qalsmartkeyboard.app.creative.cards

import com.qalsmartkeyboard.aiclient.FeatureAccess

/** A part of a card that needs the premium feature. */
enum class LockedPart { TEMPLATE, BACKGROUND, FRAME }

/**
 * The premium gate of the card maker (T8.7), by the boolean platform feature `CARD_MAKER`: free templates, every solid,
 * pattern and basic gradient, the thin frame, photo backgrounds and all text styling are free and offline; premium
 * templates, the premium gradients and the ornamental frames are the paid extras. The platform's gate is
 * [com.qalsmartkeyboard.aiclient.ServerFeatureAccess]; this object only decides which parts of a card it covers.
 */
object CardGate {
    /** The premium parts of [doc], whoever is looking: empty for a card made entirely of free parts. */
    fun premiumParts(doc: CardDoc): Set<LockedPart> = buildSet {
        if (CardTemplates.find(doc.templateId)?.premium == true) add(LockedPart.TEMPLATE)
        val bg = doc.background
        if (bg is CardBackground.Gradient && CardPalette.gradient(bg.presetId)?.premium == true) add(LockedPart.BACKGROUND)
        if (doc.frame.premium) add(LockedPart.FRAME)
    }

    fun templateAllowed(t: CardTemplate, access: FeatureAccess) = !t.premium || access == FeatureAccess.ALLOWED

    fun backgroundAllowed(bg: CardBackground, access: FeatureAccess) =
        !(bg is CardBackground.Gradient && CardPalette.gradient(bg.presetId)?.premium == true) || access == FeatureAccess.ALLOWED

    fun frameAllowed(f: CardFrame, access: FeatureAccess) = !f.premium || access == FeatureAccess.ALLOWED

    /** The parts of [doc] this device may not use now. A card with any of them can be edited and previewed, not exported. */
    fun lockedParts(doc: CardDoc, access: FeatureAccess): Set<LockedPart> =
        if (access == FeatureAccess.ALLOWED) emptySet() else premiumParts(doc)

    fun canExport(doc: CardDoc, access: FeatureAccess) = lockedParts(doc, access).isEmpty()
}

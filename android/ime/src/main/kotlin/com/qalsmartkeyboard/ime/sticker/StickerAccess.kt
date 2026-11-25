package com.qalsmartkeyboard.ime.sticker

import com.qalsmartkeyboard.ime.ai.AiAvailability
import com.qalsmartkeyboard.ime.ai.AiBlock
import com.qalsmartkeyboard.ime.ai.AiLink
import com.qalsmartkeyboard.ime.ai.PlatformFeature

/**
 * Whether the bundled premium packs are open (T8.7), from the cached plan state the AI features already use
 * ([AiAvailability.platformFeatures], the `STICKER_MAKER` entry of `/me/entitlements`, or a verified token that grants it).
 * Making stickers of your own is never gated. Unlike the AI tools, an on-device premium feature does not trust an unknown
 * state: it stays locked until the plan is known, and says so ([UNKNOWN]).
 */
enum class PackAccess {
    ALLOWED,

    /** The plan state is known and does not include the packs. */
    NOT_ON_PLAN,

    /** Nobody is signed in. */
    SIGN_IN,

    /** The plan state is not known yet (never fetched, offline, an update is required): connecting once settles it. */
    UNKNOWN,
    ;

    val open: Boolean get() = this == ALLOWED

    /** Where "See plans" goes in the companion app. */
    val link: AiLink get() = if (this == SIGN_IN) AiLink.SIGN_IN else AiLink.UPGRADE

    companion object {
        /** [availability] is null when no AI client is attached (the plan state is unknown). */
        fun of(availability: AiAvailability?): PackAccess = when {
            availability == null -> UNKNOWN
            availability.block == AiBlock.SIGNED_OUT -> SIGN_IN
            availability.grants(PlatformFeature.STICKER_MAKER) -> ALLOWED
            availability.platformFeatures == null -> UNKNOWN
            else -> NOT_ON_PLAN
        }
    }
}

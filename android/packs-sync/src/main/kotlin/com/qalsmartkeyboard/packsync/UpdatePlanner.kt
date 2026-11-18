package com.qalsmartkeyboard.packsync

import com.qalsmartkeyboard.ime.packs.SemVer
import uniffi.qal_keyboard_core.ResourceType

/** What the planner needs to know about this device. */
class PlanContext(
    val languages: Set<String>,
    val appVersion: SemVer?,
    /** Version floor per slot (the highest version ever accepted); null: none. */
    val floor: (slot: String) -> SemVer?,
    val isBlacklisted: (id: String, version: String) -> Boolean,
    /** Slots with a swap that the IME has not confirmed yet: not touched until it has. */
    val pendingSlots: Set<String>,
)

/**
 * Decides which discovery entries are worth downloading (pure function, no I/O). Per slot
 * (language, type, platform) the candidates are returned newest first; the updater tries them in
 * order. Dropped: other languages, iOS or unknown platform / type, deltas (the core only verifies
 * them; hosts do not apply them yet), anything not newer than the version floor, anything above the
 * app version (`minAppVersion`), blacklisted ones and slots with an unconfirmed swap.
 * The server already filtered by channel, app version and rollout; none of that is repeated here.
 */
object UpdatePlanner {
    private val platforms = setOf("ANDROID", "SHARED")
    private val types: Set<String> = ResourceType.entries.map { it.name }.toSet()

    fun plan(offers: List<ResourceOffer>, ctx: PlanContext): List<ResourceOffer> {
        val ok = offers.filter { o ->
            val v = SemVer.parse(o.version)
            o.languageCode in ctx.languages &&
                o.platform in platforms &&
                o.resourceType in types &&
                o.baseResourceId == null &&
                v != null &&
                (ctx.floor(o.slot)?.let { v > it } ?: true) &&
                meetsMinApp(o.minAppVersion, ctx.appVersion) &&
                o.slot !in ctx.pendingSlots &&
                !ctx.isBlacklisted(o.id, o.version)
        }
        return ok.distinctBy { it.id to it.version }
            .sortedWith(compareBy<ResourceOffer> { it.slot }.thenByDescending { SemVer.parseOrLowest(it.version) })
    }

    /** True when [min] is absent or not above [app]. An unparsable value or unknown app version fails closed. */
    fun meetsMinApp(min: String?, app: SemVer?): Boolean {
        if (min == null) return true
        val need = AppVersion.parse(min) ?: return false
        return app != null && app >= need
    }
}

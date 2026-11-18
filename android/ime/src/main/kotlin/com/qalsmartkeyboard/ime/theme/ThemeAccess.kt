package com.qalsmartkeyboard.ime.theme

/** Whether this device may use the premium themes right now, and if not, why (the app shows the reason). */
enum class PremiumThemeAccess {
    /** The cached, verified entitlement token grants `PREMIUM_THEMES` (valid, or offline inside its grace period). */
    ALLOWED,

    /** Nobody is signed in. */
    SIGNED_OUT,

    /** The account's plan does not include premium themes. */
    NOT_ON_PLAN,

    /** No usable token: none yet, or its offline grace ended. Connecting once refreshes it. */
    NEEDS_CHECK,
}

/**
 * The premium-theme port of the keyboard. `:ime` is network-free (`NoNetworkTest`), so it only knows this
 * interface; `:app` injects the implementation (over `:ai-client`'s verified entitlement token) through
 * [ThemeAccessHolder], the same way [com.qalsmartkeyboard.ime.ai.AiActionsHolder] injects the AI.
 *
 * [premium] answers from memory (the cached verified token, including its offline grace): it never blocks,
 * touches the disk or the network. The keyboard reads it when it applies a theme, not per key.
 */
interface ThemeAccess {
    val premium: PremiumThemeAccess

    /** [listener] runs on any thread when [premium] may have changed. Close the handle to remove it. */
    fun addListener(listener: () -> Unit): AutoCloseable
}

/**
 * Process-wide injection point. The system instantiates the IME service, so the app registers a factory in
 * `Application.onCreate`; the service calls [create] on a background thread (the app side may open
 * Keystore-backed files). Without a factory premium themes are locked.
 */
object ThemeAccessHolder {
    @Volatile var factory: (() -> ThemeAccess?)? = null

    @Volatile private var instance: ThemeAccess? = null

    /** Builds (once) and returns the port, or null when the app registered none. Call off the main thread. */
    fun create(): ThemeAccess? = instance ?: synchronized(this) {
        instance ?: factory?.invoke()?.also { instance = it }
    }

    internal fun resetForTest() {
        factory = null
        instance = null
    }
}

/** What to draw for a stored choice: the theme, and whether it is not the one the user picked. */
class ResolvedTheme(
    val theme: KeyboardTheme,
    /** The stored id, as chosen. */
    val requestedId: String,
    /** A premium theme is chosen but not available: [theme] is the free fallback. */
    val lockedFallback: Boolean,
    /** Why it is locked, when [lockedFallback]. */
    val access: PremiumThemeAccess,
)

object ThemeResolver {
    /**
     * @param storedId the `theme` setting: [ThemeCatalog.SYSTEM_ID], a catalogue id, or anything else (unknown ids
     *   behave like "system")
     * @param systemDark the device is in dark mode (used by "system")
     * @param access what the port says about premium themes
     */
    fun resolve(storedId: String?, systemDark: Boolean, access: PremiumThemeAccess): ResolvedTheme {
        val chosen = ThemeCatalog.find(storedId)
            ?: return ResolvedTheme(ThemeCatalog.classic(!systemDark), storedId ?: ThemeCatalog.SYSTEM_ID, false, access)
        if (chosen.isPremium && access != PremiumThemeAccess.ALLOWED) {
            return ResolvedTheme(ThemeCatalog.classic(chosen.isLight), chosen.id, true, access)
        }
        return ResolvedTheme(chosen, chosen.id, false, access)
    }
}

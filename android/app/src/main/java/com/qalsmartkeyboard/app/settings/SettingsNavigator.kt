package com.qalsmartkeyboard.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/** The settings pages below the home page (ADR 0036). */
enum class SettingsPage {
    LANGUAGES,

    /** One typing language; [SettingsDestination.arg] is its code. */
    LANGUAGE,
    ALL_LANGUAGES,
    PREFERENCES,
    VOICE,
    TRANSLATION,
    DICTIONARY,
    ACCOUNT,
    APP_LANGUAGE,
    ADVANCED,
    ABOUT,
}

data class SettingsDestination(val page: SettingsPage, val arg: String? = null) {
    internal val key: String get() = if (arg == null) page.name else "${page.name}:$arg"

    companion object {
        internal fun parse(key: String): SettingsDestination? {
            val page = SettingsPage.entries.firstOrNull { it.name == key.substringBefore(':') } ?: return null
            return SettingsDestination(page, key.substringAfter(':', "").ifEmpty { null })
        }
    }
}

/**
 * The settings back stack, without a navigation library: an empty stack is the home page. It survives rotation and
 * process death (the keys are saved), and the host's `BackHandler` pops it.
 */
class SettingsNavigator internal constructor(initial: List<String>) {
    private val stack = mutableStateListOf<String>().apply { addAll(initial.filter { SettingsDestination.parse(it) != null }) }

    /** The page shown, or null for the home page. */
    val current: SettingsDestination? get() = stack.lastOrNull()?.let(SettingsDestination::parse)

    val isHome: Boolean get() = stack.isEmpty()

    fun open(page: SettingsPage, arg: String? = null) {
        stack += SettingsDestination(page, arg).key
    }

    /** Pops one page; false when already on the home page. */
    fun back(): Boolean = stack.removeLastOrNull() != null

    /** Back to the home page (deep links). */
    fun reset() = stack.clear()

    internal fun keys(): List<String> = stack.toList()

    companion object {
        val Saver = listSaver<SettingsNavigator, String>(save = { it.keys() }, restore = { SettingsNavigator(it) })
    }
}

@Composable
fun rememberSettingsNavigator(): SettingsNavigator = rememberSaveable(saver = SettingsNavigator.Saver) { SettingsNavigator(emptyList()) }

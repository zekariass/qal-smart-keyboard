package com.qalsmartkeyboard.ime

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.util.Log
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's UI language (ADR 0032): the companion app and the keyboard's own labels (bar, panels, AI, emoji, Word Art,
 * voice). It never changes the typing languages.
 *
 * - **Android 13+**: the platform's per-app language ([LocaleManager], `res/xml/locales_config.xml`). The system stores
 *   it, shows it in the system settings too, and applies it to the whole process (activities restart by themselves).
 * - **Android 7 to 12**: no platform support and no AppCompat in this app, so the choice is stored here and applied by
 *   [wrap] (activities' `attachBaseContext`) and [uiContext] (the keyboard's views).
 * - "" means the system language: when the phone is in Amharic, Tigrinya or Afaan Oromoo the app follows it.
 *
 * The keyboard always builds its views on [uiContext], which adds the chosen locale only when the service's own
 * configuration does not have it already (the IME service runs in the app process; on Android 13+ the per-app locale
 * normally reaches it, the wrapper covers the case where it does not).
 */
object AppLanguage {
    /** The UI languages offered, in the picker's order ("" = the system language comes first in the UI). */
    val SUPPORTED: List<String> = listOf("en", "am", "ti", "om")

    private const val TAG = "AppLanguage"
    private const val PREFS = "qal_app_language"
    private const val KEY_TAG = "tag"
    private const val KEY_SYNCED_33 = "synced_to_platform"

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Each language's name in itself (the picker shows every option in its own language). */
    fun selfName(tag: String): String = when (tag) {
        "en" -> "English"
        "am" -> "አማርኛ"
        "ti" -> "ትግርኛ"
        "om" -> "Afaan Oromoo"
        else -> tag
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The chosen language ("en", "am", "ti", "om"), or "" for the system language. */
    fun get(c: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val lm = platform(c)
            if (lm != null) {
                val locales = lm.applicationLocales
                return if (locales.isEmpty) "" else normalize(locales[0].language)
            }
        }
        return normalize(prefs(c).getString(KEY_TAG, "").orEmpty())
    }

    /**
     * Chooses the UI language ("" = system). Android 13+: the platform applies it and recreates the activities;
     * below, the caller recreates its activity. Running keyboards are told through [addListener].
     */
    fun set(c: Context, tag: String) {
        val t = normalize(tag)
        prefs(c).edit().putString(KEY_TAG, t).putBoolean(KEY_SYNCED_33, true).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            platform(c)?.applicationLocales = if (t.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(t)
        }
        listeners.forEach { it() }
    }

    /**
     * Android 13+ after an OS upgrade from 12 or lower: hands the language chosen there to the platform, once. Called at
     * app start; cheap.
     */
    fun syncToPlatform(c: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val p = prefs(c)
        if (p.getBoolean(KEY_SYNCED_33, false)) return
        val stored = normalize(p.getString(KEY_TAG, "").orEmpty())
        val lm = platform(c) ?: return
        if (stored.isNotEmpty() && lm.applicationLocales.isEmpty) lm.applicationLocales = LocaleList.forLanguageTags(stored)
        p.edit().putBoolean(KEY_SYNCED_33, true).apply()
    }

    /** For an activity's `attachBaseContext`: the chosen locale below Android 13 (13+ is handled by the platform). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val t = normalize(prefs(base).getString(KEY_TAG, "").orEmpty())
        if (t.isEmpty()) return base
        return base.createConfigurationContext(withLocale(base.resources.configuration, t))
    }

    /**
     * The context the keyboard builds its views (and toasts) on: [service] itself when its configuration already has
     * the UI language, else a wrapper whose resources use the chosen locale. Call again after a configuration change.
     */
    fun uiContext(service: Context): Context {
        val t = get(service)
        if (t.isEmpty()) return service
        val current = service.resources.configuration.locales[0]
        if (current != null && normalize(current.language) == t) return service
        val res = service.createConfigurationContext(withLocale(service.resources.configuration, t)).resources
        return LocalizedContext(service, res)
    }

    /** [listener] runs (on the caller's thread of [set]) when the UI language changes. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun withLocale(base: Configuration, tag: String) = Configuration(base).apply {
        val locale = Locale.forLanguageTag(tag)
        setLocales(LocaleList(locale))
        setLayoutDirection(locale)
    }

    /** "am-ET" -> "am"; anything not offered -> "" (system). */
    internal fun normalize(tag: String): String = tag.substringBefore('-').substringBefore('_').lowercase().takeIf { it in SUPPORTED }.orEmpty()

    private fun platform(c: Context): LocaleManager? = try {
        if (Build.VERSION.SDK_INT >= 33) c.getSystemService(LocaleManager::class.java) else null
    } catch (e: RuntimeException) {
        Log.w(TAG, "locale manager unavailable: ${e.javaClass.simpleName}")
        null
    }

    /** Resources in the UI language; everything else (windows, system services, theme) is the service's. */
    private class LocalizedContext(base: Context, private val res: Resources) : ContextWrapper(base) {
        override fun getResources(): Resources = res
    }
}

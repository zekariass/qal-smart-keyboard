package com.qalsmartkeyboard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "keyboard_settings")

/**
 * Local settings store (A1.5), backed by a Preferences DataStore.
 *
 * Why Preferences and not Proto DataStore: the model is small and flat, Proto needs the protobuf
 * Gradle plugin plus generated code (more build surface in a project whose AGP and Kotlin setup is
 * very new), and a hand-written codec lets us validate and migrate in plain Kotlin. The typed model
 * is [KeyboardSettings]; every read is decoded leniently (unknown values fall back to defaults) and
 * every write is sanitised, so a bad or older/newer file can never produce an invalid state.
 *
 * Schema: key `schema_version` (see [KeyboardSettings.SCHEMA_VERSION]) plus one key per scalar and a
 * line-per-language string. To migrate, bump the version and branch in [decode] on the stored one.
 *
 * Nothing here logs values: settings can identify the user (rule N1/N2).
 */
class SettingsRepository(private val store: DataStore<Preferences>) {

    /** The current settings; emits again after every change. Always valid. */
    val settings: Flow<KeyboardSettings> = store.data
        .catch { if (it is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw it }
        .map { decode(it) }
        .distinctUntilChanged()

    suspend fun current(): KeyboardSettings = settings.first()

    /**
     * Whether the companion onboarding (A1.7) was completed or skipped. Kept outside [KeyboardSettings]
     * on purpose: it is not a user preference, is not synced, and "Reset to defaults" must not
     * bring the welcome flow back. Additive key, so `schema_version` stays 1 and older/newer files
     * decode fine (a missing key means "not completed").
     */
    val onboardingCompleted: Flow<Boolean> = store.data
        .catch { if (it is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw it }
        .map { it[ONBOARDING_COMPLETED] ?: false }
        .distinctUntilChanged()

    suspend fun setOnboardingCompleted(completed: Boolean) {
        store.edit { it[ONBOARDING_COMPLETED] = completed }
    }

    /** Applies [transform] to the current settings atomically; the result is sanitised before it is saved. */
    suspend fun update(transform: (KeyboardSettings) -> KeyboardSettings) {
        store.edit { prefs -> encode(transform(decode(prefs)).sanitized(), prefs) }
    }

    suspend fun setLanguageEnabled(code: String, enabled: Boolean) = update { s ->
        s.copy(languages = s.languages.map { if (it.languageCode == code) it.copy(enabled = enabled) else it })
    }

    /**
     * Moves [code] to [toIndex] in the list. Only the order changes: it does not look at profiles
     * (F14: a retired profile must never block reordering).
     */
    suspend fun moveLanguage(code: String, toIndex: Int) = update { s ->
        val from = s.languages.indexOfFirst { it.languageCode == code }
        if (from < 0) return@update s
        val list = s.languages.toMutableList()
        val item = list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), item)
        s.copy(languages = list)
    }

    suspend fun updateLanguage(code: String, change: (TypingLanguageSetting) -> TypingLanguageSetting) = update { s ->
        s.copy(languages = s.languages.map { if (it.languageCode == code) change(it) else it })
    }

    suspend fun setLastMode(language: String, mode: String) = update { it.copy(lastModes = it.lastModes + (language to mode)) }

    /**
     * Resolves retired/unavailable profiles against [catalog] (F14) and saves the fallbacks, leaving
     * order and enabled flags alone. Returns what changed so the UI can notify the user.
     */
    suspend fun applyProfileFallback(catalog: ProfileCatalog): List<ProfileChange> {
        var changes: List<ProfileChange> = emptyList()
        update { s ->
            val r = ProfileResolver.resolve(s, catalog)
            changes = r.changes
            r.settings
        }
        return changes
    }

    /** Profiles of the loaded packs (A1.6); unknown until [applyInstalledProfiles] runs. */
    val profileCatalog = InstalledProfileCatalog()

    /** Unacknowledged F14 fallbacks, for the settings screen. */
    val profileChanges = ProfileChangeNotice()

    /**
     * Called by the pack loader (off the main thread) once the packs are loaded: publishes their
     * transliteration profiles, replaces retired profile codes in the settings (F14) and raises
     * [profileChanges] if anything changed. Not called when loading failed, so nothing changes.
     */
    suspend fun applyInstalledProfiles(profiles: List<ProfileRef>): List<ProfileChange> {
        profileCatalog.publish(profiles)
        return applyProfileFallback(profileCatalog).also { profileChanges.report(it) }
    }

    companion object {
        private val VERSION = intPreferencesKey("schema_version")
        private val LANGUAGES = stringPreferencesKey("languages")
        private val NUMERALS = stringPreferencesKey("numeral_style")
        private val THEME = stringPreferencesKey("theme")
        private val HAPTICS = booleanPreferencesKey("haptics_enabled")
        private val KEY_HEIGHT = intPreferencesKey("key_height_percent")
        private val LAST_MODES = stringPreferencesKey("last_modes")
        private val WRITING_MODES = stringPreferencesKey("writing_modes")
        private val PACK_MOBILE_DATA = booleanPreferencesKey("pack_updates_mobile_data")
        private val PACK_CHANNEL = stringPreferencesKey("pack_channel")
        private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

        private const val FIELD = "|"
        private const val LINE = "\n"

        internal fun encode(s: KeyboardSettings, p: androidx.datastore.preferences.core.MutablePreferences) {
            p[VERSION] = KeyboardSettings.SCHEMA_VERSION
            p[LANGUAGES] = s.languages.joinToString(LINE) { l ->
                listOf(
                    l.languageCode, if (l.enabled) "1" else "0", l.inputMode.name, l.transliterationProfileCode.orEmpty(),
                    l.conversionTrigger.name, if (l.showTransliterationCandidates) "1" else "0", l.unknownTokenBehavior.name,
                ).joinToString(FIELD)
            }
            p[NUMERALS] = s.numeralStyle.name
            p[THEME] = s.theme
            p[HAPTICS] = s.hapticsEnabled
            p[KEY_HEIGHT] = s.keyHeightPercent
            p[PACK_MOBILE_DATA] = s.packUpdatesOnMobileData
            p[PACK_CHANNEL] = s.packChannel.name
            p[LAST_MODES] = encodeMap(s.lastModes)
            p[WRITING_MODES] = encodeMap(s.writingModes)
        }

        /** Lenient decode: anything missing or unrecognised takes its default; the result is sanitised. */
        internal fun decode(p: Preferences): KeyboardSettings {
            val d = KeyboardSettings.DEFAULT
            val stored = p[LANGUAGES]
            val languages = if (stored == null) d.languages else stored.split(LINE).mapNotNull { line ->
                val f = line.split(FIELD)
                val code = f.getOrNull(0)?.takeIf { LanguageRules.isKnown(it) } ?: return@mapNotNull null
                TypingLanguageSetting(
                    languageCode = code,
                    enabled = f.getOrNull(1) != "0",
                    inputMode = enumOrNull<InputMode>(f.getOrNull(2)) ?: LanguageRules.defaultMode(code),
                    transliterationProfileCode = f.getOrNull(3)?.takeIf { it.isNotEmpty() },
                    conversionTrigger = enumOrNull<ConversionTrigger>(f.getOrNull(4)) ?: ConversionTrigger.LIVE_COMPOSING,
                    showTransliterationCandidates = f.getOrNull(5) != "0",
                    unknownTokenBehavior = enumOrNull<UnknownTokenBehavior>(f.getOrNull(6)) ?: UnknownTokenBehavior.PRESERVE_LATIN,
                )
            }
            val lastModes = decodeMap(p[LAST_MODES])
            return KeyboardSettings(
                languages = languages,
                numeralStyle = enumOrNull<NumeralStyle>(p[NUMERALS]) ?: d.numeralStyle,
                theme = ThemeIds.normalize(p[THEME]),
                hapticsEnabled = p[HAPTICS] ?: d.hapticsEnabled,
                keyHeightPercent = p[KEY_HEIGHT] ?: d.keyHeightPercent,
                lastModes = lastModes,
                // Additive key (schema stays 1): files written before it decode to an empty map.
                writingModes = decodeMap(p[WRITING_MODES]),
                packUpdatesOnMobileData = p[PACK_MOBILE_DATA] ?: d.packUpdatesOnMobileData,
                packChannel = enumOrNull<PackChannel>(p[PACK_CHANNEL]) ?: d.packChannel,
            ).sanitized()
        }

        private fun encodeMap(m: Map<String, String>) = m.entries.joinToString(LINE) { "${it.key}$FIELD${it.value}" }

        private fun decodeMap(stored: String?): Map<String, String> = stored.orEmpty().split(LINE).mapNotNull { line ->
            val f = line.split(FIELD)
            if (f.size == 2) f[0] to f[1] else null
        }.toMap()

        private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

        @Volatile
        private var instance: SettingsRepository? = null

        /** The process-wide repository (one DataStore per file per process; app and IME share it). */
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext.settingsDataStore).also { instance = it }
            }
    }
}

/**
 * A cached, non-blocking view of the settings for the IME: [current] is a plain field read (the
 * defaults until the first load completes), refreshed whenever the store changes. [onChange] runs on
 * the collecting thread; post to the main thread there.
 */
class SettingsSnapshot(
    private val repository: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    @Volatile
    var current: KeyboardSettings = KeyboardSettings.DEFAULT
        private set

    @Volatile
    var onChange: (() -> Unit)? = null

    private var job: kotlinx.coroutines.Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            repository.settings.collect {
                current = it
                onChange?.invoke()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Fire-and-forget write (key path must not wait for disk). */
    fun launchUpdate(transform: (KeyboardSettings) -> KeyboardSettings) {
        scope.launch { repository.update(transform) }
    }
}

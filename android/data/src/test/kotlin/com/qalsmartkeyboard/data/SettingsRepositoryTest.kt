package com.qalsmartkeyboard.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.Preferences
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files

class SettingsRepositoryTest {
    private lateinit var dir: File
    private val scopes = ArrayList<CoroutineScope>()

    @Before fun setUp() { dir = Files.createTempDirectory("qal-settings").toFile() }

    @After fun tearDown() {
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
    }

    /** A new DataStore instance over the same file = a process restart. */
    private fun open(): SettingsRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return SettingsRepository(newStore(scope, "keyboard_settings.preferences_pb"))
    }

    // OkioStorage replaces the file with an atomic move that also works on Windows dev machines
    // (DataStore's File-based storage cannot replace an existing file there). Same serializer and
    // DataStore logic as production, which runs on Android.
    private fun newStore(scope: CoroutineScope, name: String): DataStore<Preferences> = DataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { File(dir, name).absolutePath.toPath() },
        scope = scope,
    )

    private fun closeAll() { scopes.forEach { it.cancel() }; scopes.clear() }

    private val profile = ProfileRef("AM_PHONETIC_V2", "am", 200)
    private val oldProfile = ProfileRef("AM_PHONETIC_V1", "am", 100)

    @Test fun defaultsOnFirstRun() = runBlocking {
        val s = open().current()
        assertEquals(listOf("am", "ti", "om", "en"), s.enabledLanguages)
        assertEquals(InputMode.PHONETIC_LATIN_TO_NATIVE, s.language("am")!!.inputMode)
        assertEquals(InputMode.PHONETIC_LATIN_TO_NATIVE, s.language("ti")!!.inputMode)
        assertEquals("am", s.enabledLanguages.first())
        assertEquals(NumeralStyle.WESTERN, s.numeralStyle)
        assertTrue(s.hapticsEnabled)
        assertEquals(100, s.keyHeightPercent)
    }

    @Test fun persistedAcrossRestart() = runBlocking {
        val first = open()
        first.update {
            it.copy(
                numeralStyle = NumeralStyle.ETHIOPIC,
                theme = ThemeMode.DARK,
                hapticsEnabled = false,
                keyHeightPercent = 115,
            )
        }
        first.setLanguageEnabled("ti", false)
        first.moveLanguage("en", 0)
        first.updateLanguage("am") {
            it.copy(
                inputMode = InputMode.PHONETIC_LATIN_TO_NATIVE, transliterationProfileCode = "AM_PHONETIC_V2",
                conversionTrigger = ConversionTrigger.WORD_BOUNDARY, showTransliterationCandidates = false,
                unknownTokenBehavior = UnknownTokenBehavior.SUGGEST_ONLY,
            )
        }
        first.setLastMode("om", "QUBEE")
        closeAll() // the "restart"

        val s = open().current()
        assertEquals(listOf("en", "am", "om"), s.enabledLanguages)
        assertEquals(listOf("en", "am", "ti", "om"), s.languages.map { it.languageCode })
        assertEquals(NumeralStyle.ETHIOPIC, s.numeralStyle)
        assertEquals(ThemeMode.DARK, s.theme)
        assertFalse(s.hapticsEnabled)
        assertEquals(115, s.keyHeightPercent)
        val am = s.language("am")!!
        assertEquals(InputMode.PHONETIC_LATIN_TO_NATIVE, am.inputMode)
        assertEquals("AM_PHONETIC_V2", am.transliterationProfileCode)
        assertEquals(ConversionTrigger.WORD_BOUNDARY, am.conversionTrigger)
        assertFalse(am.showTransliterationCandidates)
        assertEquals(UnknownTokenBehavior.SUGGEST_ONLY, am.unknownTokenBehavior)
        assertEquals("QUBEE", s.lastModes["om"])
    }

    @Test fun atLeastOneLanguageStaysEnabled() = runBlocking {
        val r = open()
        for (c in listOf("am", "ti", "om", "en")) r.setLanguageEnabled(c, false)
        assertEquals(1, r.current().enabledLanguages.size)
    }

    @Test fun keyHeightIsClamped() = runBlocking {
        val r = open()
        r.update { it.copy(keyHeightPercent = 500) }
        assertEquals(KeyboardSettings.KEY_HEIGHT_MAX, r.current().keyHeightPercent)
        r.update { it.copy(keyHeightPercent = 1) }
        assertEquals(KeyboardSettings.KEY_HEIGHT_MIN, r.current().keyHeightPercent)
    }

    @Test fun unknownLanguagesAndModesAreIgnoredSafely() {
        val dirty = KeyboardSettings(
            languages = listOf(
                TypingLanguageSetting("xx"),
                TypingLanguageSetting("om", inputMode = InputMode.DIRECT_SCRIPT_LAYOUT, transliterationProfileCode = "OM_X"),
                TypingLanguageSetting("om"), // duplicate
                TypingLanguageSetting("am", inputMode = InputMode.PHONETIC_LATIN_TO_NATIVE, transliterationProfileCode = "bad code|\n"),
            ),
            lastModes = mapOf("xx" to "FIDEL", "am" to "bad mode!", "ti" to "PHONETIC"),
        ).sanitized()
        assertEquals(listOf("om", "am", "ti", "en"), s(dirty))
        assertEquals(InputMode.LATIN_PASSTHROUGH, dirty.language("om")!!.inputMode)
        assertNull(dirty.language("om")!!.transliterationProfileCode)
        assertNull(dirty.language("am")!!.transliterationProfileCode)
        assertEquals(mapOf("ti" to "PHONETIC"), dirty.lastModes)
        assertFalse(dirty.language("ti")!!.enabled) // appended, disabled
    }

    private fun s(x: KeyboardSettings) = x.languages.map { it.languageCode }

    @Test fun corruptStoredValuesFallBackToDefaults() = runBlocking {
        // Write garbage straight into the file through a second view of the same store.
        val store = newStore(CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }, "other.preferences_pb")
        store.edit {
            it[stringPreferencesKey("languages")] = "zz|1|NOPE\nam|0|BOGUS|||\n\n|||"
            it[stringPreferencesKey("theme")] = "NEON"
            it[intPreferencesKey("key_height_percent")] = -4
        }
        val s = SettingsRepository(store).current()
        assertEquals(ThemeMode.SYSTEM, s.theme)
        assertEquals(80, s.keyHeightPercent)
        assertEquals(listOf("am", "ti", "om", "en"), s.languages.map { it.languageCode })
        assertEquals(InputMode.PHONETIC_LATIN_TO_NATIVE, s.language("am")!!.inputMode)
        assertTrue(s.enabledLanguages.isNotEmpty())
    }

    // ---------------------------------------------------------------- F14

    private fun catalog(vararg active: ProfileRef) = ProfileCatalog { lang -> active.filter { it.language == lang } }

    private fun phonetic(code: String?) = KeyboardSettings().let { s ->
        s.copy(languages = s.languages.map {
            if (it.languageCode == "am") it.copy(inputMode = InputMode.PHONETIC_LATIN_TO_NATIVE, transliterationProfileCode = code) else it
        })
    }

    @Test fun f14RetiredProfileFallsBackToNewestActiveAndIsFlagged() {
        val r = ProfileResolver.resolve(phonetic("AM_PHONETIC_V0"), catalog(oldProfile, profile))
        assertTrue(r.profileChanged)
        assertEquals(listOf(ProfileChange("am", "AM_PHONETIC_V0", "AM_PHONETIC_V2")), r.changes)
        assertEquals("AM_PHONETIC_V2", r.settings.language("am")!!.transliterationProfileCode)
        assertEquals(listOf("am", "ti", "om", "en"), r.settings.enabledLanguages) // order untouched
    }

    @Test fun f14ActiveProfileUnchangedAndUnknownCatalogLeavesItAlone() {
        val active = ProfileResolver.resolve(phonetic("AM_PHONETIC_V1"), catalog(oldProfile, profile))
        assertFalse(active.profileChanged)
        assertEquals("AM_PHONETIC_V1", active.settings.language("am")!!.transliterationProfileCode)
        val unknown = ProfileResolver.resolve(phonetic("AM_PHONETIC_V1"), ProfileCatalog { null })
        assertFalse(unknown.profileChanged)
    }

    @Test fun f14NoActiveProfileAtAllReportsNull() {
        val r = ProfileResolver.resolve(phonetic("GONE"), catalog())
        assertEquals(ProfileChange("am", "GONE", null), r.changes.single())
        assertNull(r.settings.language("am")!!.transliterationProfileCode)
    }

    @Test fun f14ReorderAndEditAreNeverBlockedByARetiredProfile() = runBlocking {
        val r = open()
        r.update { phonetic("AM_PHONETIC_V0") } // profile not in any catalog
        r.moveLanguage("am", 3)
        r.setLanguageEnabled("ti", false)
        r.moveLanguage("en", 0)
        val s = r.current()
        assertEquals(listOf("en", "ti", "om", "am"), s.languages.map { it.languageCode })
        assertEquals(listOf("en", "om", "am"), s.enabledLanguages)
        assertEquals("AM_PHONETIC_V0", s.language("am")!!.transliterationProfileCode) // kept until resolved

        val changes = r.applyProfileFallback(catalog(profile))
        assertEquals(1, changes.size)
        val after = r.current()
        assertEquals("AM_PHONETIC_V2", after.language("am")!!.transliterationProfileCode)
        assertEquals(s.languages.map { it.languageCode }, after.languages.map { it.languageCode }) // order unchanged
    }

    @Test fun syncItemsMatchContractShape() {
        val items = KeyboardSettings().copy(
            languages = phonetic("AM_PHONETIC_V2").languages.reversed().let { l -> l.map { if (it.languageCode == "ti") it.copy(enabled = false) else it } },
        ).toSyncItems()
        assertEquals(listOf("en", "om", "ti", "am"), items.map { it.languageCode })
        assertEquals(listOf(10, 20, 30, 40), items.map { it.sortOrder })
        assertTrue(items[0].isPrimary)
        assertFalse(items[2].isPrimary)
        assertEquals("AM_PHONETIC_V2", items[3].transliterationProfileCode)
        assertNull(items[0].transliterationProfileCode)
    }
}

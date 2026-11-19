package com.qalsmartkeyboard.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
        runBlocking { closeAll() }
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

    /**
     * The "restart": cancel every DataStore scope **and wait for it to complete**. DataStore keeps a process-wide
     * registry of active files and only releases a file when its scope's job has completed; cancelling without
     * joining let the next `open()` race the release ("multiple DataStores active for the same file"), which
     * failed intermittently on the Linux CI runner.
     */
    private suspend fun closeAll() {
        scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
        scopes.clear()
    }

    private val profile = ProfileRef("AM_PHONETIC_V2", "am", 200)
    private val oldProfile = ProfileRef("AM_PHONETIC_V1", "am", 100)

    @Test fun defaultsOnFirstRun() = runBlocking {
        val s = open().current()
        assertEquals(listOf("am", "ti", "om", "en"), s.enabledLanguages)
        assertEquals(InputMode.PHONETIC_LATIN_TO_NATIVE, s.language("am")!!.inputMode)
        assertEquals(InputMode.PHONETIC_LATIN_TO_NATIVE, s.language("ti")!!.inputMode)
        assertEquals("am", s.enabledLanguages.first())
        assertEquals(NumeralStyle.WESTERN, s.numeralStyle)
        // ADR 0043: nothing picked yet, so am/ti type Ethiopic numerals.
        assertFalse(s.numeralStyleChosen)
        assertEquals(NumeralStyle.ETHIOPIC, s.effectiveNumeralStyle)
        assertTrue(s.hapticsEnabled)
        assertTrue(s.keyPreviewEnabled)
        assertEquals(100, s.keyHeightPercent)
        assertTrue(s.crashReports) // ADR 0024: on by default, reports carry no content
    }

    @Test fun crashReportsSwitchPersistsAcrossRestart() = runBlocking {
        open().update { it.copy(crashReports = false) }
        closeAll()
        assertFalse(open().current().crashReports)
    }

    @Test fun emojiRecentsAndTonesPersistAcrossRestartAndStartEmpty() = runBlocking {
        val first = open()
        assertTrue(first.current().recentEmoji.isEmpty())
        assertTrue(first.current().emojiTones.isEmpty())
        first.update { it.copy(recentEmoji = listOf("😀", "👋"), emojiTones = mapOf("👋" to "👋🏽")) }
        closeAll()
        val s = open().current()
        assertEquals(listOf("😀", "👋"), s.recentEmoji)
        assertEquals(mapOf("👋" to "👋🏽"), s.emojiTones)
    }

    @Test fun filesWrittenBeforeCrashReportsDecodeToOn() = runBlocking {
        // A store written by the previous version: no crash_reports key at all.
        val store = newStore(CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }, "old-crash.preferences_pb")
        store.edit {
            it[intPreferencesKey("schema_version")] = 1
            it[intPreferencesKey("key_height_percent")] = 110
        }
        val s = SettingsRepository(store).current()
        assertTrue(s.crashReports)
        assertEquals(110, s.keyHeightPercent)
    }

    /** ADR 0043: how a file written before `numeral_style_chosen` decodes. */
    @Test fun numeralStyleMigration() = runBlocking {
        fun old(name: String, style: String?) = runBlocking {
            val store = newStore(CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }, "$name.preferences_pb")
            store.edit {
                it[intPreferencesKey("schema_version")] = 1
                if (style != null) it[stringPreferencesKey("numeral_style")] = style
            }
            SettingsRepository(store).current()
        }
        // The old default WESTERN was written on every save: it cannot be told from a choice, so the new default applies.
        old("old-western", "WESTERN").let {
            assertFalse(it.numeralStyleChosen)
            assertEquals(NumeralStyle.ETHIOPIC, it.effectiveNumeralStyle)
        }
        old("old-none", null).let { assertEquals(NumeralStyle.ETHIOPIC, it.effectiveNumeralStyle) }
        // ETHIOPIC was never a default: it was picked, and stays.
        old("old-ethiopic", "ETHIOPIC").let {
            assertTrue(it.numeralStyleChosen)
            assertEquals(NumeralStyle.ETHIOPIC, it.effectiveNumeralStyle)
        }
        // From now on an explicit Western is kept across a restart.
        val repo = open()
        repo.update { it.copy(numeralStyle = NumeralStyle.WESTERN, numeralStyleChosen = true) }
        closeAll()
        val s = open().current()
        assertTrue(s.numeralStyleChosen)
        assertEquals(NumeralStyle.WESTERN, s.effectiveNumeralStyle)
    }

    @Test fun persistedAcrossRestart() = runBlocking {
        val first = open()
        first.update {
            it.copy(
                numeralStyle = NumeralStyle.ETHIOPIC,
                theme = ThemeIds.CLASSIC_DARK,
                hapticsEnabled = false,
                keyPreviewEnabled = false,
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
        assertEquals(listOf("en", "am", "ti", "om", "ar"), s.languages.map { it.languageCode })
        assertEquals(NumeralStyle.ETHIOPIC, s.numeralStyle)
        assertEquals(ThemeIds.CLASSIC_DARK, s.theme)
        assertFalse(s.hapticsEnabled)
        assertFalse(s.keyPreviewEnabled)
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
        assertEquals(listOf("om", "am", "ti", "en", "ar"), s(dirty))
        assertEquals(InputMode.LATIN_PASSTHROUGH, dirty.language("om")!!.inputMode)
        assertNull(dirty.language("om")!!.transliterationProfileCode)
        assertNull(dirty.language("am")!!.transliterationProfileCode)
        assertEquals(mapOf("ti" to "PHONETIC"), dirty.lastModes)
        assertFalse(dirty.language("ti")!!.enabled) // appended, disabled
    }

    private fun s(x: KeyboardSettings) = x.languages.map { it.languageCode }

    @Test fun arabicIsOptInOnFirstRun() = runBlocking {
        val r = open()
        val s = r.current()
        assertEquals(listOf("am", "ti", "om", "en", "ar"), s.languages.map { it.languageCode })
        assertFalse(s.language("ar")!!.enabled)
        assertEquals(listOf("am", "ti", "om", "en"), s.enabledLanguages)
        assertEquals(InputMode.LATIN_PASSTHROUGH, s.language("ar")!!.inputMode)
        assertEquals(listOf(InputMode.LATIN_PASSTHROUGH), LanguageRules.allowedModes("ar"))
        assertTrue(LanguageRules.isKnown("ar") && "ar" in LanguageRules.OPT_IN)
        // Arabic is never phonetic: a stale or tampered phonetic mode falls back to its one mode, with no profile code.
        r.updateLanguage("ar") { it.copy(inputMode = InputMode.PHONETIC_LATIN_TO_NATIVE, transliterationProfileCode = "AR_X") }
        assertEquals(InputMode.LATIN_PASSTHROUGH, r.current().language("ar")!!.inputMode)
        assertNull(r.current().language("ar")!!.transliterationProfileCode)
    }

    @Test fun afaanOromooHasItsOwnConfiguration() {
        // Qubee, a single Latin layout: never phonetic or a Fidel grid, never an Amharic profile; it suggests words.
        assertEquals(listOf(InputMode.LATIN_PASSTHROUGH), LanguageRules.allowedModes("om"))
        assertEquals(InputMode.LATIN_PASSTHROUGH, LanguageRules.defaultMode("om"))
        for (code in listOf("om", "en", "ar")) assertTrue(code, LanguageRules.hasWordSuggestions(code))
        for (code in listOf("am", "ti")) assertFalse(code, LanguageRules.hasWordSuggestions(code))
        // Tigrinya has its own entry with the same writing modes as Amharic (phonetic first, Fidel, Latin).
        assertEquals(LanguageRules.allowedModes("am"), LanguageRules.allowedModes("ti"))
        assertTrue("om" !in LanguageRules.OPT_IN && "ti" !in LanguageRules.OPT_IN)
    }

    @Test fun existingUsersGetArabicAppendedSwitchedOff() = runBlocking {
        // Settings saved by a version that knew four languages: Oromo first, Tigrinya off.
        val store = newStore(CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }, "old.preferences_pb")
        store.edit {
            it[stringPreferencesKey("languages")] = listOf(
                "om|1|LATIN_PASSTHROUGH||LIVE_COMPOSING|1|PRESERVE_LATIN",
                "am|1|PHONETIC_LATIN_TO_NATIVE||LIVE_COMPOSING|1|PRESERVE_LATIN",
                "ti|0|DIRECT_SCRIPT_LAYOUT||LIVE_COMPOSING|1|PRESERVE_LATIN",
                "en|1|LATIN_PASSTHROUGH||LIVE_COMPOSING|1|PRESERVE_LATIN",
            ).joinToString("\n")
        }
        val r = SettingsRepository(store)
        val s = r.current()
        assertEquals(listOf("om", "am", "ti", "en", "ar"), s.languages.map { it.languageCode })
        assertEquals(listOf("om", "am", "en"), s.enabledLanguages) // the user's choice is untouched
        assertFalse(s.language("ar")!!.enabled)
        assertEquals(InputMode.DIRECT_SCRIPT_LAYOUT, s.language("ti")!!.inputMode)
        // Opting in is an ordinary edit, and it is kept.
        r.setLanguageEnabled("ar", true)
        assertEquals(listOf("om", "am", "en", "ar"), r.current().enabledLanguages)
        // Arabic can be the only language.
        for (c in listOf("om", "am", "en")) r.setLanguageEnabled(c, false)
        assertEquals(listOf("ar"), r.current().enabledLanguages)
    }

    @Test fun arabicIsAValidTranslationTargetAndVoiceKey() {
        val s = KeyboardSettings(aiPreferredTarget = "ar", voiceLanguages = mapOf("ar" to "ar-EG"), lastModes = mapOf("ar" to "ARABIC")).sanitized()
        assertEquals("ar", s.aiPreferredTarget)
        assertEquals(mapOf("ar" to "ar-EG"), s.voiceLanguages)
        assertEquals(mapOf("ar" to "ARABIC"), s.lastModes)
    }

    @Test fun corruptStoredValuesFallBackToDefaults() = runBlocking {
        // Write garbage straight into the file through a second view of the same store.
        val store = newStore(CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }, "other.preferences_pb")
        store.edit {
            it[stringPreferencesKey("languages")] = "zz|1|NOPE\nam|0|BOGUS|||\n\n|||"
            it[stringPreferencesKey("theme")] = "NEON"
            it[intPreferencesKey("key_height_percent")] = -4
        }
        val s = SettingsRepository(store).current()
        assertEquals(ThemeIds.SYSTEM, s.theme)
        assertEquals(80, s.keyHeightPercent)
        assertEquals(listOf("am", "ti", "om", "en", "ar"), s.languages.map { it.languageCode })
        assertEquals(InputMode.PHONETIC_LATIN_TO_NATIVE, s.language("am")!!.inputMode)
        assertTrue(s.enabledLanguages.isNotEmpty())
    }

    // ---------------------------------------------------------------- writing modes (am/ti)

    @Test fun writingModesPersistPerLanguageAcrossRestart() = runBlocking {
        val r = open()
        r.update { it.copy(writingModes = it.writingModes + ("am" to "ABC")) }
        r.update { it.copy(writingModes = it.writingModes + ("ti" to "FIDEL"), lastModes = it.lastModes + ("ti" to "FIDEL")) }
        closeAll() // the "restart"
        val s = open().current()
        assertEquals(mapOf("am" to "ABC", "ti" to "FIDEL"), s.writingModes)
        assertEquals(mapOf("ti" to "FIDEL"), s.lastModes)
    }

    @Test fun filesWrittenBeforeWritingModesKeepEverythingElse() = runBlocking {
        // A store written by the previous version: no writing_modes key at all.
        val store = newStore(CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }, "old.preferences_pb")
        store.edit {
            it[intPreferencesKey("schema_version")] = 1
            it[stringPreferencesKey("last_modes")] = "am|FIDEL\nom|QUBEE"
            it[intPreferencesKey("key_height_percent")] = 110
        }
        val r = SettingsRepository(store)
        val s = r.current()
        assertEquals(emptyMap<String, String>(), s.writingModes)
        assertEquals(mapOf("am" to "FIDEL", "om" to "QUBEE"), s.lastModes)
        assertEquals(110, s.keyHeightPercent)
        // the first write adds the new key without losing the old values
        r.update { it.copy(writingModes = mapOf("am" to "PHONETIC")) }
        val after = r.current()
        assertEquals(mapOf("am" to "PHONETIC"), after.writingModes)
        assertEquals(mapOf("am" to "FIDEL", "om" to "QUBEE"), after.lastModes)
    }

    @Test fun badWritingModesAreDropped() {
        val dirty = KeyboardSettings(writingModes = mapOf("xx" to "FIDEL", "am" to "bad|mode", "ti" to "ABC")).sanitized()
        assertEquals(mapOf("ti" to "ABC"), dirty.writingModes)
    }

    // ---------------------------------------------------------------- themes

    @Test fun oldThemeValuesAreMigratedToIds() = runBlocking {
        val cases = mapOf(
            "SYSTEM" to "system", "LIGHT" to "classic_light", "DARK" to "classic_dark",
            "midnight" to "midnight", "premium_purple" to "premium_purple", "NEON" to "system", "" to "system",
            "a".repeat(41) to "system", "Bad Id" to "system",
        )
        for ((stored, expected) in cases) {
            val store = newStore(CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }, "theme-${stored.hashCode()}.preferences_pb")
            store.edit { it[stringPreferencesKey("theme")] = stored }
            assertEquals("stored '$stored'", expected, SettingsRepository(store).current().theme)
        }
        assertEquals("system", ThemeIds.normalize(null))
        assertEquals("system", KeyboardSettings.DEFAULT.theme)
    }

    @Test fun chosenThemePersistsAcrossRestart() = runBlocking {
        open().update { it.copy(theme = "aurora") }
        closeAll()
        assertEquals("aurora", open().current().theme)
        closeAll()
        open().update { it.copy(theme = "Not A Theme!") } // a malformed value is never stored
        closeAll()
        assertEquals(ThemeIds.SYSTEM, open().current().theme)
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
        assertEquals(listOf("en", "ti", "om", "am", "ar"), s.languages.map { it.languageCode })
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
            languages = phonetic("AM_PHONETIC_V2").languages.filter { it.languageCode != "ar" }.reversed().let { l -> l.map { if (it.languageCode == "ti") it.copy(enabled = false) else it } },
        ).toSyncItems()
        assertEquals(listOf("en", "om", "ti", "am"), items.map { it.languageCode })
        assertEquals(listOf(10, 20, 30, 40), items.map { it.sortOrder })
        assertTrue(items[0].isPrimary)
        assertFalse(items[2].isPrimary)
        assertEquals("AM_PHONETIC_V2", items[3].transliterationProfileCode)
        assertNull(items[0].transliterationProfileCode)
    }

    // ---------------------------------------------------------------- A1.6 seam

    @Test fun loadedPacksReplaceARetiredProfileAndRaiseTheFlag() = runBlocking {
        val r = open()
        r.update { phonetic("AM_PHONETIC_V0") }
        val order = r.current().languages.map { it.languageCode }
        assertFalse(r.profileChanges.profileChanged)

        val changes = r.applyInstalledProfiles(listOf(ProfileRef("AM_PHONETIC", "am", 0, "0.1.0", false)))

        assertEquals(listOf(ProfileChange("am", "AM_PHONETIC_V0", "AM_PHONETIC")), changes)
        val after = r.current()
        assertEquals("AM_PHONETIC", after.language("am")!!.transliterationProfileCode)
        assertEquals(order, after.languages.map { it.languageCode })
        assertTrue(r.profileChanges.profileChanged)
        assertEquals(changes, r.profileChanges.changes.value)
        r.profileChanges.acknowledge()
        assertFalse(r.profileChanges.profileChanged)

        // A second run finds nothing to change and does not raise the flag again.
        assertTrue(r.applyInstalledProfiles(listOf(ProfileRef("AM_PHONETIC", "am", 0))).isEmpty())
        assertFalse(r.profileChanges.profileChanged)
    }

    @Test fun nothingChangesWhilePacksAreNotLoaded() = runBlocking {
        val r = open()
        r.update { phonetic("AM_PHONETIC_V0") }
        assertNull(r.profileCatalog.activeProfiles("am"))
        assertTrue(r.applyProfileFallback(r.profileCatalog).isEmpty())
        assertEquals("AM_PHONETIC_V0", r.current().language("am")!!.transliterationProfileCode)
        assertFalse(r.profileChanges.profileChanged)
    }

    @Test fun languageWithoutALoadedProfileIsLeftUnchangedAndNotFlagged() = runBlocking {
        val r = open()
        r.update { phonetic("AM_PHONETIC_V0") }
        // Packs loaded, but none carries an Amharic profile (missing or rejected): keep the user's choice.
        assertTrue(r.applyInstalledProfiles(listOf(ProfileRef("TI_PHONETIC", "ti", 0))).isEmpty())
        assertEquals("AM_PHONETIC_V0", r.current().language("am")!!.transliterationProfileCode)
        assertFalse(r.profileChanges.profileChanged)

        // A later load with the pack present applies the normal fallback.
        val changes = r.applyInstalledProfiles(listOf(ProfileRef("AM_PHONETIC", "am", 0)))
        assertEquals(listOf(ProfileChange("am", "AM_PHONETIC_V0", "AM_PHONETIC")), changes)
        assertEquals("AM_PHONETIC", r.current().language("am")!!.transliterationProfileCode)
        assertTrue(r.profileChanges.profileChanged)
    }

    @Test fun changeNoticeAccumulatesUntilAcknowledged() {
        val n = ProfileChangeNotice()
        val a = ProfileChange("am", "A", "B")
        n.report(listOf(a))
        n.report(emptyList())
        n.report(listOf(a, ProfileChange("ti", "X", null)))
        assertEquals(2, n.changes.value.size)
        n.acknowledge()
        assertTrue(n.changes.value.isEmpty())
    }

    // ------------------------------------------------------------------ voice typing (ADR 0029)

    @Test fun voiceTypingDefaultsOnWithNoChosenLanguagesAndTheNoteNotShown() = runBlocking {
        val s = open().current()
        assertTrue(s.voiceTypingEnabled)
        assertTrue(s.voiceLanguages.isEmpty())
        assertFalse(s.voiceNoticeShown)
    }

    @Test fun voiceSettingsPersistAcrossRestart() = runBlocking {
        open().update { it.copy(voiceTypingEnabled = false, voiceLanguages = mapOf("ti" to "ti-ER", "en" to "en-GB"), voiceNoticeShown = true) }
        closeAll()
        val s = open().current()
        assertFalse(s.voiceTypingEnabled)
        assertEquals(mapOf("ti" to "ti-ER", "en" to "en-GB"), s.voiceLanguages)
        assertTrue(s.voiceNoticeShown)
    }

    @Test fun voiceLanguagesKeepOnlyKnownKeyboardLanguagesAndWellFormedTags() {
        val s = KeyboardSettings(
            voiceLanguages = mapOf("am" to "am-ET", "xx" to "en-US", "en" to "en US|x", "om" to "cmn-Hans-CN", "ti" to ""),
        ).sanitized()
        assertEquals(mapOf("am" to "am-ET", "om" to "cmn-Hans-CN"), s.voiceLanguages)
    }

    // ------------------------------------------------------------------ AI panels (ADR 0031)

    @Test fun aiChoicesDefaultToAutomaticAndEmpty() = runBlocking {
        val s = open().current()
        assertEquals(null, s.aiPreferredTarget)
        assertTrue(s.aiTranslatePairs.isEmpty())
        assertEquals("", s.emailSenderName)
    }

    @Test fun aiChoicesPersistAcrossRestart() = runBlocking {
        open().update { it.copy(aiPreferredTarget = "ti", aiTranslatePairs = mapOf("am" to "lang:en", "en" to "p-1"), emailSenderName = "Abebe Kebede") }
        closeAll()
        val repo = open()
        val s = repo.current()
        assertEquals("ti", s.aiPreferredTarget)
        assertEquals(mapOf("am" to "lang:en", "en" to "p-1"), s.aiTranslatePairs)
        assertEquals("Abebe Kebede", s.emailSenderName)
        repo.update { it.copy(aiPreferredTarget = null) }
        closeAll()
        assertEquals("automatic again", null, open().current().aiPreferredTarget)
    }

    @Test fun aiChoicesAreSanitised() {
        val s = KeyboardSettings(
            aiPreferredTarget = "not a tag|",
            aiTranslatePairs = mapOf("am" to "lang:en", "x|y" to "lang:am", "en" to "bad id|x"),
            emailSenderName = "  Abebe\nKebede|" + "x".repeat(200),
        ).sanitized()
        assertEquals(null, s.aiPreferredTarget)
        assertEquals(mapOf("am" to "lang:en"), s.aiTranslatePairs)
        assertTrue(s.emailSenderName.startsWith("Abebe Kebede "))
        assertEquals(KeyboardSettings.SENDER_NAME_MAX, s.emailSenderName.length)
    }

    /** ADR 0040: translation languages are not typing languages, so a translation-only code is kept for translation. */
    @Test fun translationOnlyLanguagesAreKeptForTranslation() {
        val s = KeyboardSettings(aiPreferredTarget = "zh-CN", aiTranslatePairs = mapOf("fr" to "lang:am", "en" to "lang:mni-Mtei")).sanitized()
        assertEquals("zh-CN", s.aiPreferredTarget)
        assertEquals(mapOf("fr" to "lang:am", "en" to "lang:mni-Mtei"), s.aiTranslatePairs)
        // ... but never as typing languages
        assertFalse(s.languages.any { it.languageCode == "fr" || it.languageCode == "zh-CN" })
    }

    /** ADR 0035: the clipboard-history switch is on by default, persists, and old files decode to on. */
    @Test fun clipboardHistoryDefaultsOnAndPersistsAcrossRestart() = runBlocking {
        val first = open()
        assertTrue(first.current().clipboardHistoryEnabled)
        first.update { it.copy(clipboardHistoryEnabled = false) }
        closeAll()
        assertFalse(open().current().clipboardHistoryEnabled)
        // A store written before the key existed.
        val store = newStore(CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }, "old-clip.preferences_pb")
        store.edit { it[intPreferencesKey("schema_version")] = 1 }
        assertTrue(SettingsRepository(store).current().clipboardHistoryEnabled)
    }

    /** ADR 0039: next word, English autocorrection and learning default on, Oromo autocorrection off; all persist. */
    @Test fun predictionSwitchesHaveTheirDefaultsAndPersist() = runBlocking {
        val first = open()
        val d = first.current()
        assertTrue(d.nextWordPredictions && d.autocorrectEnglish && d.learnWords)
        assertFalse(d.autocorrectOromo)
        first.update { it.copy(nextWordPredictions = false, autocorrectEnglish = false, autocorrectOromo = true, learnWords = false) }
        closeAll()
        val s = open().current()
        assertFalse(s.nextWordPredictions || s.autocorrectEnglish || s.learnWords)
        assertTrue(s.autocorrectOromo)
        // A store written before the keys existed takes the defaults.
        val store = newStore(CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }, "old-predict.preferences_pb")
        store.edit { it[intPreferencesKey("schema_version")] = 1 }
        val old = SettingsRepository(store).current()
        assertTrue(old.nextWordPredictions && old.autocorrectEnglish && old.learnWords && !old.autocorrectOromo)
    }
}

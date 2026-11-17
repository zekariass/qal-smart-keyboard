package com.qalsmartkeyboard.app.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import com.qalsmartkeyboard.app.onboarding.OnboardingStep.*
import com.qalsmartkeyboard.data.InputMode
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OnboardingModelTest {
    private lateinit var dir: File
    private val scopes = ArrayList<CoroutineScope>()

    @Before fun setUp() { dir = Files.createTempDirectory("qal-onboarding").toFile() }

    @After fun tearDown() {
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
    }

    /** A new DataStore over the same file = a process restart. */
    private fun open(): SettingsRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val store: DataStore<Preferences> = DataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { File(dir, "s.preferences_pb").absolutePath.toPath() },
            scope = scope,
        )
        return SettingsRepository(store)
    }

    private fun restart() { scopes.forEach { it.cancel() }; scopes.clear() }

    private val none = ImeStatus(enabled = false, selected = false)
    private val enabledOnly = ImeStatus(enabled = true, selected = false)
    private val both = ImeStatus(enabled = true, selected = true)

    // --- pure rules ---

    @Test fun continueRules() {
        assertTrue(OnboardingFlow.canContinue(PRIVACY, none))
        assertFalse(OnboardingFlow.canContinue(ENABLE, none))
        assertTrue(OnboardingFlow.canContinue(ENABLE, enabledOnly))
        assertFalse(OnboardingFlow.canContinue(SELECT, enabledOnly))
        assertTrue(OnboardingFlow.canContinue(SELECT, both))
        assertTrue(OnboardingFlow.canContinue(LANGUAGES, none))
        assertTrue(OnboardingFlow.canContinue(TUTORIAL, none))
    }

    @Test fun reconcileSkipsSatisfiedStepsAndFallsBack() {
        assertEquals(ENABLE, OnboardingFlow.reconcile(ENABLE, none))
        assertEquals(SELECT, OnboardingFlow.reconcile(ENABLE, enabledOnly))
        assertEquals(LANGUAGES, OnboardingFlow.reconcile(ENABLE, both))
        assertEquals(LANGUAGES, OnboardingFlow.reconcile(SELECT, both))
        assertEquals(ENABLE, OnboardingFlow.reconcile(SELECT, none)) // disabled again
        assertEquals(LANGUAGES, OnboardingFlow.reconcile(LANGUAGES, none)) // later steps are not pulled back
        assertEquals(PRIVACY, OnboardingFlow.reconcile(PRIVACY, both))
    }

    @Test fun imeStatusParsing() {
        val pkg = "com.qalsmartkeyboard.app"
        assertTrue(ImeStatus.isSelected("$pkg/com.qalsmartkeyboard.ime.KeyboardImeService", pkg))
        assertFalse(ImeStatus.isSelected("com.google.android.inputmethod.latin/.LatinIME", pkg))
        assertFalse(ImeStatus.isSelected("${pkg}x/.Ime", pkg))
        assertFalse(ImeStatus.isSelected(null, pkg))
        assertTrue(ImeStatus.isEnabled(listOf("a.b", pkg), pkg))
        assertFalse(ImeStatus.isEnabled(listOf("a.b"), pkg))
    }

    @Test fun draftRules() {
        var d = LanguageDraft.from(KeyboardSettings.DEFAULT)
        assertEquals("am", d.primary)
        d = d.makePrimary("ti")
        assertEquals(listOf("ti", "am", "om", "en"), d.order)
        assertEquals("ti", d.primary)
        d = d.toggle("ti")
        assertEquals("am", d.primary) // first enabled in order
        d = d.toggle("am").toggle("om").toggle("en")
        assertEquals(1, d.enabled.size)
        d = d.copy(enabled = setOf("am")).move("om", -3).move("om", -1)
        assertEquals("om", d.order.first())
        // Latin-only languages cannot be given a Fidel mode.
        assertEquals(d, d.withMode("om", InputMode.DIRECT_SCRIPT_LAYOUT))
    }

    // --- state machine over the real repository ---

    @Test fun newUserFlowPersistsLanguagesAndCompletion() = runBlocking {
        val repo = open()
        assertFalse(repo.onboardingCompleted.first())
        val m = OnboardingModel(repo)
        m.start()
        assertEquals(PRIVACY, m.state.value.step)

        m.onSystemState(none)
        assertEquals(PRIVACY, m.state.value.step) // system facts do not move the privacy step
        m.next()
        assertEquals(ENABLE, m.state.value.step)
        m.next() // blocked: not enabled
        assertEquals(ENABLE, m.state.value.step)

        m.onSystemState(enabledOnly) // user came back from system settings
        assertEquals(SELECT, m.state.value.step)
        m.next() // blocked: not selected
        assertEquals(SELECT, m.state.value.step)
        m.onSystemState(both)
        assertEquals(LANGUAGES, m.state.value.step)

        m.edit { it.makePrimary("ti").withMode("ti", InputMode.DIRECT_SCRIPT_LAYOUT).toggle("om") }
        assertEquals(KeyboardSettings.DEFAULT, repo.current()) // nothing saved before Continue
        m.next()
        assertEquals(TUTORIAL, m.state.value.step)

        val saved = repo.current()
        assertEquals(listOf("ti", "am", "om", "en"), saved.languages.map { it.languageCode })
        assertEquals(listOf("ti", "am", "en"), saved.enabledLanguages)
        assertEquals(InputMode.DIRECT_SCRIPT_LAYOUT, saved.language("ti")!!.inputMode)
        assertEquals(InputMode.PHONETIC_LATIN_TO_NATIVE, saved.language("am")!!.inputMode) // ADR 0007 U3 default kept
        assertFalse(repo.onboardingCompleted.first())

        m.next() // finish
        assertTrue(m.state.value.finished)
        assertTrue(repo.onboardingCompleted.first())

        restart()
        val again = open()
        assertTrue(again.onboardingCompleted.first())
        assertEquals(saved, again.current())
    }

    @Test fun skipMarksCompletedWithoutTouchingSettings() = runBlocking {
        val repo = open()
        val m = OnboardingModel(repo)
        m.start()
        m.edit { it.makePrimary("en") }
        m.finish() // skip
        assertTrue(repo.onboardingCompleted.first())
        assertEquals(KeyboardSettings.DEFAULT, repo.current())
    }

    @Test fun rerunStartsFromSavedChoiceAndResetsFlow() = runBlocking {
        val repo = open()
        repo.update { s -> LanguageDraft.from(s).makePrimary("om").applyTo(s) }
        repo.setOnboardingCompleted(true)
        val m = OnboardingModel(repo)
        m.start()
        m.finish()
        assertTrue(m.state.value.finished)
        m.start() // "Run setup again"
        assertFalse(m.state.value.finished)
        assertEquals(PRIVACY, m.state.value.step)
        assertEquals("om", m.state.value.draft.primary)
        assertTrue(repo.onboardingCompleted.first()) // stays completed
    }

    @Test fun changedModeForgetsRememberedLayoutButUnchangedKeepsIt() = runBlocking {
        val repo = open()
        repo.setLastMode("am", "PHONETIC")
        repo.setLastMode("ti", "FIDEL")
        val m = OnboardingModel(repo)
        m.start()
        m.onSystemState(both)
        m.edit { it.withMode("am", InputMode.DIRECT_SCRIPT_LAYOUT) }
        m.edit { it } // no-op edit
        m.next() // PRIVACY -> ENABLE, then reconcile skips ENABLE and SELECT (system already satisfied)
        assertEquals(LANGUAGES, m.state.value.step)
        m.next()
        val s = repo.current()
        assertEquals(null, s.lastModes["am"])
        assertEquals("FIDEL", s.lastModes["ti"])
    }

    @Test fun backNavigationStaysInRange() = runBlocking {
        val m = OnboardingModel(open())
        m.start()
        m.back()
        assertEquals(PRIVACY, m.state.value.step)
        m.next(); m.back()
        assertEquals(PRIVACY, m.state.value.step)
    }
}

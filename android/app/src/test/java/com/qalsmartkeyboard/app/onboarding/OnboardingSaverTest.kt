package com.qalsmartkeyboard.app.onboarding

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.lifecycle.SavedStateHandle
import com.qalsmartkeyboard.data.InputMode
import com.qalsmartkeyboard.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** ADR 0007 D12: the step and the language draft survive process death through a SavedStateHandle. */
class OnboardingSaverTest {
    private lateinit var dir: File
    private val scopes = ArrayList<CoroutineScope>()

    @Before fun setUp() { dir = Files.createTempDirectory("qal-onboarding-saver").toFile() }

    @After fun tearDown() {
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
    }

    private fun repository(): SettingsRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val store = DataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { File(dir, "s.preferences_pb").absolutePath.toPath() },
            scope = scope,
        )
        return SettingsRepository(store)
    }

    private val enabledOnly = ImeStatus(enabled = true, selected = false)

    @Test fun nothingSavedMeansAFreshStart() {
        assertNull(OnboardingSaver.restore(SavedStateHandle()))
    }

    @Test fun stepAndDraftRoundTrip() {
        val draft = LanguageDraft.from(com.qalsmartkeyboard.data.KeyboardSettings.DEFAULT)
            .makePrimary("om").toggle("ti").withMode("am", InputMode.DIRECT_SCRIPT_LAYOUT)
        val handle = SavedStateHandle()
        OnboardingSaver.save(handle, OnboardingState(step = OnboardingStep.LANGUAGES, draft = draft))

        val restored = OnboardingSaver.restore(handle)
        assertNotNull(restored)
        assertEquals(OnboardingStep.LANGUAGES, restored!!.step)
        assertEquals(draft, restored.draft)
    }

    @Test fun modelBoundToAHandleResumesTheSameStepAfterProcessDeath() = runBlocking {
        val handle = SavedStateHandle()
        val scope = CoroutineScope(Dispatchers.Unconfined).also { scopes += it }
        val first = OnboardingModel(repository())
        OnboardingSaver.bind(handle, scope, first.state)
        first.start()
        first.next() // privacy -> enable
        first.edit { it.makePrimary("ti") }
        assertEquals(OnboardingStep.ENABLE, first.state.value.step)

        // The process dies; the handle's contents are what the new process gets back.
        val survivor = SavedStateHandle(mapOf(
            OnboardingSaver.KEY_STEP to handle.get<String>(OnboardingSaver.KEY_STEP),
            OnboardingSaver.KEY_ORDER to handle.get<ArrayList<String>>(OnboardingSaver.KEY_ORDER),
            OnboardingSaver.KEY_ENABLED to handle.get<ArrayList<String>>(OnboardingSaver.KEY_ENABLED),
            OnboardingSaver.KEY_MODES to handle.get<ArrayList<String>>(OnboardingSaver.KEY_MODES),
        ))
        val second = OnboardingModel(repository(), OnboardingSaver.restore(survivor))
        assertEquals(OnboardingStep.ENABLE, second.state.value.step)
        assertEquals("ti", second.state.value.draft.primary)
        // Back from system settings with the keyboard enabled: the usual reconcile moves on.
        second.onSystemState(enabledOnly)
        assertEquals(OnboardingStep.SELECT, second.state.value.step)
    }

    @Test fun restoreIsLenientWithGarbage() {
        val handle = SavedStateHandle()
        handle[OnboardingSaver.KEY_STEP] = "NOPE"
        handle[OnboardingSaver.KEY_ORDER] = arrayListOf("xx", "om", "om")
        handle[OnboardingSaver.KEY_ENABLED] = arrayListOf("yy")
        handle[OnboardingSaver.KEY_MODES] = arrayListOf("om=PHONETIC_LATIN_TO_NATIVE", "am=garbage", "zz=LATIN_PASSTHROUGH")
        val s = OnboardingSaver.restore(handle)!!
        assertEquals(OnboardingStep.PRIVACY, s.step)
        assertEquals("om", s.draft.order.first())
        assertEquals(setOf("am", "ti", "om", "en"), s.draft.order.toSet())
        assertEquals(setOf("am", "ti", "om", "en"), s.draft.enabled) // nothing valid enabled: the default
        assertEquals(InputMode.LATIN_PASSTHROUGH, s.draft.modes["om"]) // Oromo cannot be phonetic: default kept
    }
}

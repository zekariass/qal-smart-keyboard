package com.qalsmartkeyboard.ime

import android.text.InputType
import java.io.File
import org.junit.Assert.assertEquals
import uniffi.qal_keyboard_core.KeyboardEngine

/**
 * Shared setup for the JVM tests that drive the real Rust core (host build via JNA) with the
 * bundled seed packs (DRAFT data, synthetic text).
 */
object CoreTestEnv {
    private val packDir = File("src/main/assets/packs")
    val log = ArrayList<String>()

    val engine: KeyboardEngine by lazy {
        val json = File(packDir, BundledPacks.INDEX_FILE).readText()
        fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(json)!!.groupValues[1]
        val files = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        val index = BundledPacks.Index(field("signingKeyId"), BundledPacks.hexToBytes(field("signingPublicKeyHex")), files)
        BundledPacks.createEngine(index, { File(packDir, it).readBytes() }, { log += it })
    }

    /**
     * The pre-ADR-0007 starting point (Fidel grid first, Ethiopic numerals offered) that the A1.4
     * layout tests were written against; the first-run defaults are tested in SettingsPreferencesTest.
     */
    class FidelFirst : InMemoryModePreferences() {
        override fun preferredKind(language: String) = if (LanguageCatalog.isEthiopic(language)) LayoutKind.FIDEL else null
        override val ethiopicNumerals get() = true
    }

    /** A keyboard controller wired to a [FakeEditor] through the real adapter and the real core. */
    class Session(
        type: Int = InputType.TYPE_CLASS_TEXT,
        languages: EnabledLanguages = DefaultEnabledLanguages,
        prefs: ModePreferences = InMemoryModePreferences(),
    ) {
        val ed = FakeEditor()
        val adapter = InputConnectionAdapter { ed }
        val c = KeyboardController(adapter, languages, prefs) {}

        init {
            adapter.onCompositionAbandoned = { c.onCompositionAbandoned() }
            adapter.startSession(type == InputType.TYPE_NULL, 0, 0)
            c.composer = CoreComposer(engine)
            c.startField(InputProfile.from(type, 0, false, 0))
        }

        fun key(kind: KeyKind) = c.layout.rows.flatten().first { it.kind == kind }

        fun tap(k: Key) {
            c.onKey(k)
            ed.deliver(adapter)
        }

        fun type(s: String) {
            for (ch in s) {
                tap(
                    c.layout.rows.flatten().first {
                        !it.isSpacer && if (ch == ' ') it.kind == KeyKind.SPACE else it.kind == KeyKind.CHAR && it.text == ch.toString()
                    },
                )
            }
        }

        fun consonant(series: String) = tap(c.layout.rows.flatten().first { it.kind == KeyKind.FIDEL_CONSONANT && it.text == series })

        fun vowel(order: Int) = tap(c.layout.rows.flatten().first { it.kind == KeyKind.FIDEL_VOWEL && it.order == order })

        fun pick(item: PopupItem) {
            c.onPopupSelected(item)
            ed.deliver(adapter)
        }

        /** Switches to [m] with the real controls: the space-bar picker for the language, the globe long-press for the layout. */
        fun mode(m: TypingMode) {
            if (c.language != m.language) pick(key(KeyKind.SPACE).popup.first { it.language == m.language })
            var guard = 0
            while (c.effectiveMode != m && guard++ < 6) {
                c.onLongPress(key(KeyKind.GLOBE))
                ed.deliver(adapter)
            }
            assertEquals(m, c.effectiveMode)
        }
    }
}

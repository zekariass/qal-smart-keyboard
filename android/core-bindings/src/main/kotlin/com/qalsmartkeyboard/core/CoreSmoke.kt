package com.qalsmartkeyboard.core

import uniffi.qal_keyboard_core.EngineConfig
import uniffi.qal_keyboard_core.KeyboardEngine

/** Smallest possible proof that the UniFFI binding and the native core library work (A1.1). */
object CoreSmoke {
    /** Constructs an engine (no packs, no trusted keys) and returns the core crate version. */
    fun coreVersion(): String {
        val engine = KeyboardEngine(EngineConfig(maxCandidates = 5u, trustedKeys = emptyList(), packLimits = null))
        try {
            return engine.version()
        } finally {
            engine.close()
        }
    }
}

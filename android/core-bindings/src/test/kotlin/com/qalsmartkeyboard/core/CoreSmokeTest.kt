package com.qalsmartkeyboard.core

import org.junit.Assert.assertTrue
import org.junit.Test

class CoreSmokeTest {
    @Test
    fun coreVersionComesFromNativeLibrary() {
        val v = CoreSmoke.coreVersion()
        assertTrue("unexpected version '$v'", Regex("""\d+\.\d+\.\d+.*""").matches(v))
    }
}

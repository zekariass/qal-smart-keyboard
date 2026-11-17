package com.qalsmartkeyboard.ime.packs

import com.qalsmartkeyboard.ime.CoreTestEnv
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A1.5/A1.6 seam: the profile catalog built from the real core and the bundled seed packs. */
class InstalledProfilesTest {
    @Test
    fun codeMatchesTheSettingsAlphabet() {
        assertEquals("AM_PHONETIC", profileCodeOf("am-phonetic"))
        assertEquals("TI_PHONETIC_V2", profileCodeOf("ti-phonetic.v2"))
    }

    @Test
    fun bundledTransliterationPacksGiveOneProfilePerLanguage() {
        val tmp = Files.createTempDirectory("qal-profiles").toFile()
        try {
            val files = CoreTestEnv.bundledFiles()
            val r = PackManager(PackTrust.configured(), { files }, null, VersionStore(File(tmp, "versions.txt"))).load()
            try {
                val profiles = r.installedProfiles()
                assertEquals(listOf("am", "ti"), profiles.map { it.language }.sorted())
                val am = profiles.single { it.language == "am" }
                assertEquals("AM_PHONETIC", am.code)
                assertTrue(am.version!!.isNotEmpty())
                assertEquals(false, am.approved) // seed data is DRAFT
            } finally {
                r.engine.close()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}

package com.qalsmartkeyboard.data.secure

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A1.8: the personal dictionary is local only. Whether code *could* reach the network cannot be
 * proven by a unit test, so this is a source-level guard over the modules that hold the user
 * dictionary (`:data`, `:ime`): no network API is referenced and no manifest requests the INTERNET
 * permission. A new dependency that brings its own network use is out of reach of this test and is
 * covered by review (and by the app manifest check below).
 */
class NoNetworkTest {
    private val forbidden = listOf(
        "java.net.", "javax.net.", "HttpURLConnection", "HttpsURLConnection", "okhttp", "retrofit", "ktor",
        "ConnectivityManager", "android.net.", "WebView", "DownloadManager", "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
    )

    private fun androidRoot(): File {
        // Gradle runs unit tests with the module directory as the working directory.
        val here = File(System.getProperty("user.dir")).absoluteFile
        return generateSequence(here) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    }

    @Test fun dataAndImeSourcesAndManifestsUseNoNetwork() {
        val root = androidRoot()
        val files = listOf("data", "ime").flatMap { m ->
            File(root, "$m/src/main").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.name == "AndroidManifest.xml") }.toList()
        }
        assertTrue("sources found", files.size > 10)
        val hits = files.flatMap { f ->
            val text = f.readText()
            forbidden.filter { text.contains(it, ignoreCase = true) }.map { "${f.name}: $it" }
        }
        assertTrue("network references: $hits", hits.isEmpty())
    }

    @Test fun appManifestDoesNotRequestInternet() {
        val manifest = File(androidRoot(), "app/src/main/AndroidManifest.xml").readText()
        assertTrue(!manifest.contains("android.permission.INTERNET"))
    }
}

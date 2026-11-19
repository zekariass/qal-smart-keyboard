package com.qalsmartkeyboard.app.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.qalsmartkeyboard.app.auth.AccountStatus
import com.qalsmartkeyboard.app.privacy.ConsentsState
import com.qalsmartkeyboard.app.privacy.DeletionState
import com.qalsmartkeyboard.app.privacy.PrivacyDashboardScreen
import com.qalsmartkeyboard.ime.voice.VoiceIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** ADR 0029: the keyboard's mic without permission opens this app's permission screen; its states; the disclosure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h3000dp")
class VoicePermissionTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun theKeyboardsIntentOpensThePermissionScreen() {
        val info = ctx.packageManager.resolveActivity(VoiceIntents.permission(ctx.packageName), 0)
        assertNotNull("no activity for the keyboard's voice-permission intent", info)
        assertEquals(VoicePermissionActivity::class.java.name, info!!.activityInfo.name)
        assertEquals("", info.activityInfo.taskAffinity.orEmpty()) // its own task: finishing returns to the typing app
    }

    @Test fun theAppRequestsTheMicrophonePermission() {
        val requested = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(requested.contains(Manifest.permission.RECORD_AUDIO))
    }

    @Test fun theStateFollowsWhatAndroidReports() {
        val m = VoicePermissionModel
        assertEquals(VoicePermissionState.GRANTED, m.state(granted = true, askedBefore = true, rationale = false))
        assertEquals(VoicePermissionState.EXPLAIN, m.state(granted = false, askedBefore = false, rationale = false))
        assertEquals(VoicePermissionState.ASK_AGAIN, m.state(granted = false, askedBefore = true, rationale = true))
        assertEquals(VoicePermissionState.BLOCKED, m.state(granted = false, askedBefore = true, rationale = false))
    }

    @Test fun explainThenAsk() {
        var asked = 0
        compose.setContent { VoicePermissionScreen(VoicePermissionState.EXPLAIN, { asked++ }, {}, {}) }
        compose.onNodeWithText("Microphone for voice typing").assertExists()
        compose.onNodeWithText("Qal doesn't record or store your voice.", substring = true).assertExists()
        compose.onNodeWithText("Allow microphone").performClick()
        assertEquals(1, asked)
    }

    @Test fun blockedLeadsToTheAppSettings() {
        var settings = 0
        var closed = 0
        compose.setContent { VoicePermissionScreen(VoicePermissionState.BLOCKED, {}, { settings++ }, { closed++ }) }
        compose.onNodeWithText("Allow microphone").assertDoesNotExist()
        compose.onNodeWithText("The microphone is turned off for Qal Keyboard.", substring = true).assertExists()
        compose.onNodeWithText("Open app settings").performClick()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(1, settings)
        assertEquals(1, closed)
    }

    @Test fun thePrivacyDashboardExplainsVoiceTyping() {
        compose.setContent { PrivacyDashboardScreen(AccountStatus.Registered, ConsentsState(), DeletionState(), false, {}, {}, {}) }
        compose.onNodeWithText("Voice typing").assertExists()
        compose.onNodeWithText("Qal doesn't record, store or send your voice", substring = true).assertExists()
    }
}

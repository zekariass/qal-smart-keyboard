package com.qalsmartkeyboard.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.ime.AppLanguage
import androidx.core.content.ContextCompat
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.app.ui.theme.QalSmartKeyboardTheme

/** What the permission screen shows (ADR 0029). */
enum class VoicePermissionState {
    /** Not asked yet: explain, then the system dialog. */
    EXPLAIN,

    /** Denied once: explain again and offer the dialog again. */
    ASK_AGAIN,

    /** Denied for good (or blocked by policy): the dialog will not show; explain how to allow it in settings. */
    BLOCKED,

    /** Allowed: back to typing. */
    GRANTED,
}

/** Pure decision for the screen, from what Android reports (JVM-testable). */
object VoicePermissionModel {
    /**
     * @param askedBefore the dialog was shown at least once (Android cannot tell "never asked" from "don't ask again").
     * @param rationale `shouldShowRequestPermissionRationale`: true after a plain denial.
     */
    fun state(granted: Boolean, askedBefore: Boolean, rationale: Boolean): VoicePermissionState = when {
        granted -> VoicePermissionState.GRANTED
        !askedBefore -> VoicePermissionState.EXPLAIN
        rationale -> VoicePermissionState.ASK_AGAIN
        else -> VoicePermissionState.BLOCKED
    }
}

/**
 * The microphone-permission screen the keyboard opens when its mic is tapped without RECORD_AUDIO (ADR 0029): an input
 * method cannot show the system dialog itself. It explains why, asks, and finishes as soon as the permission is
 * granted, so the user lands back in the app they were typing in (own task, excluded from recents). When Android will
 * no longer show the dialog, it explains how to allow the microphone in the app's settings and opens them.
 */
class VoicePermissionActivity : ComponentActivity() {
    // ADR 0032: the app's UI language below Android 13 (13+: the platform applies it).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private var state by mutableStateOf(VoicePermissionState.EXPLAIN)

    private val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) done() else update()
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        update()
        setContent {
            QalSmartKeyboardTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    VoicePermissionScreen(
                        state = state,
                        onAllow = ::ask,
                        onOpenSettings = ::openSettings,
                        onClose = ::finish,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the app's settings page: allowed there means done.
        if (granted()) done() else update()
    }

    private fun granted() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun update() {
        state = VoicePermissionModel.state(
            granted(),
            prefs.getBoolean(KEY_ASKED, false),
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
        )
    }

    private fun ask() {
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        request.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun openSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun done() {
        if (isFinishing) return
        Toast.makeText(this, R.string.voice_perm_granted, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        private const val PREFS = "voice_permission"
        private const val KEY_ASKED = "asked"
    }
}

@Composable
fun VoicePermissionScreen(
    state: VoicePermissionState,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.voice_perm_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.voice_perm_why))
        Text(stringResource(R.string.voice_perm_privacy))
        when (state) {
            VoicePermissionState.EXPLAIN, VoicePermissionState.ASK_AGAIN -> {
                if (state == VoicePermissionState.ASK_AGAIN) Text(stringResource(R.string.voice_perm_denied))
                Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.voice_perm_allow)) }
            }
            VoicePermissionState.BLOCKED -> {
                Text(stringResource(R.string.voice_perm_blocked))
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.voice_perm_open_settings)) }
            }
            VoicePermissionState.GRANTED -> Text(stringResource(R.string.voice_perm_granted))
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.voice_perm_not_now)) }
    }
}

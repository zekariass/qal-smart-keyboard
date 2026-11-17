package com.qalsmartkeyboard.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qalsmartkeyboard.app.onboarding.ImeStatusReader
import com.qalsmartkeyboard.app.onboarding.OnboardingScreen
import com.qalsmartkeyboard.app.onboarding.OnboardingViewModel
import com.qalsmartkeyboard.app.ui.theme.QalSmartKeyboardTheme
import com.qalsmartkeyboard.core.CoreSmoke
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.SettingsRepository
import com.qalsmartkeyboard.data.ThemeMode

class MainActivity : ComponentActivity() {
    private val repository by lazy { SettingsRepository.get(applicationContext) }

    private val onboarding: OnboardingViewModel by viewModels {
        viewModelFactory { initializer { OnboardingViewModel(SettingsRepository.get(applicationContext)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by repository.settings.collectAsState(initial = KeyboardSettings.DEFAULT)
            // null while the stored flag loads, so neither screen flashes by mistake.
            val completed by onboarding.completed.collectAsState(initial = null)
            var rerun by rememberSaveable { mutableStateOf(false) }
            val flow by onboarding.state.collectAsState()
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // First run: show the flow. A re-run from settings stays until finished or skipped.
            val showFlow = completed == false || (rerun && !flow.finished)
            LaunchedEffect(flow.finished) { if (flow.finished) rerun = false }
            QalSmartKeyboardTheme(darkTheme = dark) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when {
                        completed == null -> Unit
                        showFlow -> OnboardingScreen(
                            state = flow,
                            onNext = onboarding::next,
                            onBack = onboarding::back,
                            onSkip = onboarding::skip,
                            onEdit = onboarding::edit,
                            modifier = Modifier.padding(innerPadding),
                        )
                        else -> Column(Modifier.padding(innerPadding)) {
                            Greeting(
                                name = "core " + CoreSmoke.coreVersion(), // A1.1: proves the UniFFI binding
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            SettingsScreen(settings, repository, onRunSetup = {
                                onboarding.restart()
                                rerun = true
                            })
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        onboarding.onSystemState(ImeStatusReader.read(this))
    }

    // The input-method picker is a dialog window: the activity may not pause, but it loses and regains focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) onboarding.onSystemState(ImeStatusReader.read(this))
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    QalSmartKeyboardTheme {
        Greeting("Android")
    }
}

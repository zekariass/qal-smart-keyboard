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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qalsmartkeyboard.app.auth.AccountSection
import com.qalsmartkeyboard.app.auth.AccountStatus
import com.qalsmartkeyboard.app.auth.AuthGraph
import com.qalsmartkeyboard.app.auth.CredentialManagerGoogleSource
import com.qalsmartkeyboard.app.auth.SignInActions
import com.qalsmartkeyboard.app.auth.SignInPane
import com.qalsmartkeyboard.app.auth.SignInStep
import com.qalsmartkeyboard.app.auth.SignInViewModel
import com.qalsmartkeyboard.app.auth.UpdateRequiredScreen
import com.qalsmartkeyboard.app.auth.openStorePage
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
        viewModelFactory { initializer { OnboardingViewModel(SettingsRepository.get(applicationContext), createSavedStateHandle()) } }
    }

    private val graph by lazy { AuthGraph.get(applicationContext) }

    private val signIn: SignInViewModel by viewModels {
        viewModelFactory { initializer { SignInViewModel(graph.repository) } }
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
            val accountStatus by remember { graph.repository.status() }
                .collectAsState(initial = graph.repository.currentStatus())
            val signInState by signIn.state.collectAsState()
            val updateRequired by graph.updateRequired.collectAsState()
            var updateDismissed by rememberSaveable { mutableStateOf(false) }
            var showSignIn by rememberSaveable { mutableStateOf(false) }
            val googleSource = remember { CredentialManagerGoogleSource(this@MainActivity, BuildConfig.GOOGLE_WEB_CLIENT_ID) }
            val actions = SignInActions(
                onPhone = signIn::setPhone,
                onSendCode = signIn::submitPhone,
                onCode = signIn::setCode,
                onVerify = signIn::submitCode,
                onResend = signIn::resend,
                onAgeConfirmed = signIn::setAgeConfirmed,
                onGoogle = { signIn.google(googleSource) },
                onGuest = signIn::guest,
                onChangeNumber = signIn::backToOptions,
                onDone = {
                    signIn.reset()
                    showSignIn = false
                },
            )
            val hasGuest = accountStatus == AccountStatus.Guest
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
                        updateRequired && !updateDismissed -> UpdateRequiredScreen(
                            onOpenStore = { openStorePage(this@MainActivity) },
                            onDismiss = { updateDismissed = true },
                            modifier = Modifier.padding(innerPadding),
                        )
                        showSignIn && !showFlow -> Column(
                            Modifier.padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
                        ) {
                            SignInPane(signInState, actions, googleSource.isConfigured, graph.repository.guestSupported, hasGuest)
                            if (signInState.step != SignInStep.DONE) {
                                OutlinedButton(onClick = { signIn.reset(); showSignIn = false }) { Text(stringResource(R.string.onb_back)) }
                            }
                        }
                        showFlow -> OnboardingScreen(
                            state = flow,
                            onNext = onboarding::next,
                            onBack = onboarding::back,
                            onSkip = onboarding::skip,
                            onEdit = onboarding::edit,
                            modifier = Modifier.padding(innerPadding),
                            accountContent = {
                                if (signInState.step != SignInStep.DONE && accountStatus == AccountStatus.Registered) {
                                    Text(stringResource(R.string.onb_account_title), style = MaterialTheme.typography.headlineSmall)
                                    Text(stringResource(R.string.acct_signed_in))
                                } else {
                                    Text(stringResource(R.string.onb_account_title), style = MaterialTheme.typography.headlineSmall)
                                    Text(stringResource(R.string.onb_account_body))
                                    SignInPane(signInState, actions, googleSource.isConfigured, graph.repository.guestSupported, hasGuest)
                                    Text(stringResource(R.string.onb_account_skip_hint), style = MaterialTheme.typography.bodySmall)
                                }
                            },
                        )
                        else -> Column(Modifier.padding(innerPadding)) {
                            Greeting(
                                name = "core " + CoreSmoke.coreVersion(), // A1.1: proves the UniFFI binding
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            SettingsScreen(settings, repository, onRunSetup = {
                                onboarding.restart()
                                rerun = true
                            }, accountContent = {
                                AccountSection(
                                    status = accountStatus,
                                    onSignIn = { signIn.reset(); showSignIn = true },
                                    onSignOut = { signIn.reset(); signIn.signOut() },
                                )
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

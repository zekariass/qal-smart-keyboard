package com.qalsmartkeyboard.app

import android.content.Intent
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
import androidx.lifecycle.lifecycleScope
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
import com.qalsmartkeyboard.app.link.DeepLinkAction
import com.qalsmartkeyboard.app.link.DeepLinks
import com.qalsmartkeyboard.app.packs.PackUpdateScheduler
import com.qalsmartkeyboard.app.privacy.PrivacyEntryCard
import com.qalsmartkeyboard.app.privacy.PrivacyGraph
import com.qalsmartkeyboard.app.privacy.PrivacyHost
import com.qalsmartkeyboard.app.privacy.PrivacyRoute
import com.qalsmartkeyboard.app.privacy.PrivacyViewModel
import com.qalsmartkeyboard.app.sync.DictionarySyncSection
import com.qalsmartkeyboard.app.sync.SyncGraph
import com.qalsmartkeyboard.app.sync.SyncScheduler
import com.qalsmartkeyboard.api.SessionState
import com.qalsmartkeyboard.app.onboarding.OnboardingScreen
import com.qalsmartkeyboard.app.onboarding.OnboardingViewModel
import com.qalsmartkeyboard.app.ui.theme.QalSmartKeyboardTheme
import com.qalsmartkeyboard.core.CoreSmoke
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.SettingsRepository
import com.qalsmartkeyboard.app.themes.ThemesHost
import com.qalsmartkeyboard.app.themes.themeStore
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import com.qalsmartkeyboard.ime.ai.AiLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
class MainActivity : ComponentActivity() {
    private val repository by lazy { SettingsRepository.get(applicationContext) }

    private val onboarding: OnboardingViewModel by viewModels {
        viewModelFactory { initializer { OnboardingViewModel(SettingsRepository.get(applicationContext), createSavedStateHandle()) } }
    }

    private val graph by lazy { AuthGraph.get(applicationContext) }

    private val signIn: SignInViewModel by viewModels {
        viewModelFactory { initializer { SignInViewModel(graph.repository) } }
    }

    private val privacy: PrivacyViewModel by viewModels {
        viewModelFactory { initializer { PrivacyViewModel(PrivacyGraph.get(applicationContext)) } }
    }

    // The keyboard toolbar's link (ADR 0007 D40), consumed once the screen state is known.
    private var pendingLink by mutableStateOf<AiLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingLink = DeepLinks.fromIntent(intent)
        // A2.3: language-pack updates. The first value is the app-start check; a later change of the
        // mobile-data switch re-queues the check with the new network constraint.
        lifecycleScope.launch {
            var first = true
            repository.settings.map { it.packUpdatesOnMobileData }.distinctUntilChanged().collect { allow ->
                PackUpdateScheduler.schedule(applicationContext, allow, replaceNow = !first)
                first = false
            }
        }
        // A2.4: sync triggers. Periodic (unmetered); app start and a settings change (debounced); sign-in;
        // sign-out forgets the account's sync state. All network work is in SyncWorker, never here.
        SyncScheduler.schedulePeriodic(applicationContext)
        lifecycleScope.launch {
            repository.settings.map { listOf(it.languages, it.numeralStyle, it.theme, it.hapticsEnabled) }
                .distinctUntilChanged().debounce(SETTINGS_DEBOUNCE_MS).collect { SyncScheduler.requestSoon(applicationContext) }
        }
        lifecycleScope.launch {
            var previous: SessionState? = null
            graph.session.state.collect { s ->
                if (s is SessionState.SignedIn) {
                    SyncScheduler.requestSoon(applicationContext)
                    privacy.refreshHistoryFlag() // A2.6: another device may have turned AI history on or off
                } else if (previous is SessionState.SignedIn) {
                    withContext(Dispatchers.IO) { SyncGraph.get(applicationContext).engine.accountChanged() }
                    PrivacyGraph.get(applicationContext).onSignedOut() // the toolbar stops asking for history to be saved
                }
                previous = s
            }
        }
        lifecycleScope.launch {
            // Sign-in finished (also guest -> account, which keeps the session state SignedIn).
            signIn.state.map { it.step == SignInStep.DONE }.distinctUntilChanged().filter { it }.collect {
                withContext(Dispatchers.IO) { SyncGraph.get(applicationContext).engine.signedIn() }
                SyncScheduler.requestSoon(applicationContext)
            }
        }
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
            var showPlans by rememberSaveable { mutableStateOf(false) }
            // The privacy area (A2.6): null = closed, else a PrivacyRoute name.
            var privacyRoute by rememberSaveable { mutableStateOf<String?>(null) }
            // Opened from the keyboard's gear: settings offer "Back to typing" (and Back) to return to the app.
            var fromKeyboard by rememberSaveable { mutableStateOf(false) }
            // The theme picker (themes package).
            var themesOpen by rememberSaveable { mutableStateOf(false) }
            val link = pendingLink
            LaunchedEffect(link, completed) {
                if (link == null || completed == null) return@LaunchedEffect // wait until the stored flag is read
                pendingLink = null
                when (DeepLinks.route(link, graph.repository.currentStatus(), completed == true)) {
                    DeepLinkAction.NONE -> Unit
                    DeepLinkAction.SHOW_SETTINGS -> { showSignIn = false; showPlans = false; privacyRoute = null; themesOpen = false; rerun = false }
                    DeepLinkAction.SHOW_PRIVACY -> { showSignIn = false; showPlans = false; rerun = false; privacyRoute = PrivacyRoute.DASHBOARD.name }
                    DeepLinkAction.SHOW_SIGN_IN -> { signIn.reset(); showPlans = false; privacyRoute = null; showSignIn = true }
                    DeepLinkAction.SHOW_PLANS -> { showSignIn = false; privacyRoute = null; showPlans = true }
                    DeepLinkAction.OPEN_STORE -> openStorePage(this@MainActivity)
                    DeepLinkAction.SHOW_KEYBOARD_SETTINGS -> { showSignIn = false; showPlans = false; privacyRoute = null; rerun = false; fromKeyboard = true }
                }
            }
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
            val systemDark = isSystemInDarkTheme()
            val dark = ThemeCatalog.find(settings.theme)?.let { !it.isLight } ?: systemDark
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
                        showPlans && !showFlow -> PlansScreen(onBack = { showPlans = false }, modifier = Modifier.padding(innerPadding))
                        showSignIn && !showFlow -> Column(
                            Modifier.padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
                        ) {
                            SignInPane(signInState, actions, googleSource.isConfigured, graph.repository.guestSupported, hasGuest)
                            if (signInState.step != SignInStep.DONE) {
                                OutlinedButton(onClick = { signIn.reset(); showSignIn = false }) { Text(stringResource(R.string.onb_back)) }
                            }
                        }
                        privacyRoute != null && !showFlow -> PrivacyHost(
                            route = PrivacyRoute.entries.firstOrNull { it.name == privacyRoute } ?: PrivacyRoute.DASHBOARD,
                            onRoute = { privacyRoute = it.name },
                            onClose = { privacyRoute = null },
                            account = accountStatus,
                            viewModel = privacy,
                            onSignIn = { signIn.reset(); showSignIn = true },
                            onPlans = { showPlans = true },
                            modifier = Modifier.padding(innerPadding),
                            syncCard = { DictionarySyncSection(accountStatus) },
                            crashReportsOn = settings.crashReports,
                            onCrashReports = { on -> lifecycleScope.launch { repository.update { it.copy(crashReports = on) } } },
                        )
                        themesOpen && !showFlow -> ThemesHost(
                            appliedId = settings.theme,
                            store = repository.themeStore(),
                            systemDark = systemDark,
                            onUnlock = { showPlans = true },
                            onBack = { themesOpen = false },
                            modifier = Modifier.padding(innerPadding),
                        )
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
                            if (fromKeyboard) KeyboardReturnBar(onDone = { finishAndRemoveTask() })
                            Greeting(
                                name = "core " + CoreSmoke.coreVersion(), // A1.1: proves the UniFFI binding
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            SettingsScreen(settings, repository, onRunSetup = {
                                onboarding.restart()
                                rerun = true
                            }, onOpenThemes = { themesOpen = true }, syncContent = {
                                PrivacyEntryCard(onOpen = { privacyRoute = PrivacyRoute.DASHBOARD.name })
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

    private companion object {
        const val SETTINGS_DEBOUNCE_MS = 2_000L
    }

    // CLEAR_TOP from the keyboard reaches a running activity here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLinks.fromIntent(intent)?.let { pendingLink = it }
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

/** Placeholder for the plans screen the toolbar's "See plans" link opens (billing UI comes with the payments steps). */
@Composable
fun PlansScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.plans_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.plans_body))
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.onb_back)) }
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

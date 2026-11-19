package com.qalsmartkeyboard.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import com.qalsmartkeyboard.app.auth.AccountSection
import com.qalsmartkeyboard.app.auth.AccountStatus
import com.qalsmartkeyboard.app.link.DeepLinkAction
import com.qalsmartkeyboard.app.link.DeepLinks
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.SettingsRepository
import com.qalsmartkeyboard.ime.KeyboardLabels
import com.qalsmartkeyboard.ime.ai.AiLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * ADR 0035: the settings the keyboard's gear opens put the account first: "Sign in" (straight to the sign-in screen)
 * when signed out, "Signed in as ..." leading to "Sign out" when signed in; the bar's "Sign in to use AI" lands on the
 * sign-in pane over those settings (Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h3000dp")
class KeyboardAccountTest {
    @get:Rule val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("keyboard-account").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = SettingsRepository(
        DataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { File(dir, "s.preferences_pb").absolutePath.toPath() },
            scope = scope,
        ),
    )

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
        KeyboardLabels.reset()
    }

    @Test fun signedOutOffersSignIn() {
        var signIns = 0
        compose.setContent { AccountSection(AccountStatus.SignedOut(null), onSignIn = { signIns++ }, onSignOut = {}) }
        compose.onNodeWithText("You are not signed in. Typing works without an account.").assertExists()
        compose.onNodeWithText("Sign in").performClick()
        assertEquals(1, signIns)
    }

    @Test fun signedInShowsWhoAndSignOut() {
        compose.setContent { AccountSection(AccountStatus.Registered, onSignIn = {}, onSignOut = {}, label = "+251 9•• ••• 567") }
        compose.onNodeWithText("Signed in as +251 9•• ••• 567").assertExists()
        compose.onNodeWithText("Sign in").assertDoesNotExist()
        compose.onNodeWithText("Sign out").performClick() // asks first
        compose.onNodeWithText("Sign out?").assertExists()
    }

    @Test fun signedInWithoutAKnownLabelSaysSo() {
        compose.setContent { AccountSection(AccountStatus.Registered, onSignIn = {}, onSignOut = {}) }
        compose.onNodeWithText("You are signed in.").assertExists()
    }

    @Test fun theSessionChangingUpdatesTheCardInPlace() {
        var status: AccountStatus by mutableStateOf(AccountStatus.SignedOut(null))
        compose.setContent { AccountSection(status, onSignIn = {}, onSignOut = {}, label = "z•••@example.com") }
        compose.onNodeWithText("Sign in").assertExists()
        status = AccountStatus.Registered // signed in elsewhere (the keyboard's sign-in or another screen)
        compose.onNodeWithText("Signed in as z•••@example.com").assertExists()
        status = AccountStatus.SignedOut(null)
        compose.onNodeWithText("Sign in").assertExists()
    }

    private var signIns = 0

    private fun home(fromKeyboard: Boolean, account: AccountStatus = AccountStatus.SignedOut(null), label: String? = null) =
        compose.setContent {
            SettingsScreen(
                KeyboardSettings.DEFAULT, repository,
                account = account,
                accountLabel = label,
                onSignIn = { signIns++ },
                fromKeyboard = fromKeyboard,
                dictionaryContent = {}, // no native core on the JVM
                accountContent = { AccountSection(account, onSignIn = { signIns++ }, onSignOut = {}, label = label) },
                appVersion = "1.0",
            )
        }

    @Test fun fromTheKeyboardSignedOutTheFirstRowSignsIn() {
        home(fromKeyboard = true)
        val signIn = compose.onNodeWithText("Sign in").getUnclippedBoundsInRoot().top
        val themes = compose.onNodeWithText("View more").getUnclippedBoundsInRoot().top
        assertTrue("the account row is above the themes", signIn < themes)
        compose.onNodeWithText("Not signed in").assertExists()
        compose.onNodeWithText("Sign in").performClick() // straight to the sign-in screen
        assertEquals(1, signIns)
        compose.onNodeWithText("Account").assertDoesNotExist() // not listed twice
    }

    @Test fun fromTheKeyboardSignedInTheFirstRowSaysWhoAndOpensSignOut() {
        home(fromKeyboard = true, account = AccountStatus.Registered, label = "z•••@example.com")
        val account = compose.onNodeWithText("Account").getUnclippedBoundsInRoot().top
        val themes = compose.onNodeWithText("View more").getUnclippedBoundsInRoot().top
        assertTrue(account < themes)
        compose.onNodeWithText("Signed in as z•••@example.com").performClick()
        compose.onNodeWithText("Sign out").assertExists() // the account page
        assertEquals(0, signIns)
    }

    @Test fun otherwiseTheAccountStaysInItsUsualPlace() {
        home(fromKeyboard = false)
        val account = compose.onNodeWithText("Account").getUnclippedBoundsInRoot().top
        val themes = compose.onNodeWithText("View more").getUnclippedBoundsInRoot().top
        assertTrue(themes < account)
    }

    @Test fun theBarsSignInLinkOpensTheSignInPaneOverTheKeyboardSettings() {
        val out = AccountStatus.SignedOut(null)
        assertEquals(DeepLinkAction.SHOW_SIGN_IN, DeepLinks.route(AiLink.SIGN_IN, out, true))
        assertEquals(DeepLinkAction.SHOW_SIGN_IN, DeepLinks.route(AiLink.SIGN_IN, AccountStatus.Guest, true))
        // Already signed in (e.g. in the app a moment ago): the keyboard settings, which say who, with "Back to typing".
        assertEquals(DeepLinkAction.SHOW_KEYBOARD_SETTINGS, DeepLinks.route(AiLink.SIGN_IN, AccountStatus.Registered, true))
        assertEquals(DeepLinkAction.SHOW_KEYBOARD_SETTINGS, DeepLinks.route(AiLink.SETTINGS, out, true))
    }
}

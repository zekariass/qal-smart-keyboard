package com.qalsmartkeyboard.app.auth

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Renders the sign-in UI on the JVM (Robolectric): wiring of state to text and callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignInPaneTest {
    @get:Rule val compose = createComposeRule()

    private val challenge = OtpChallengeInfo(UUID.randomUUID(), 6, 0, 0)

    private fun show(state: SignInState, actions: SignInActions = SignInActions(), google: Boolean = true, guest: Boolean = true, hasGuest: Boolean = false) =
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) { SignInPane(state, actions, google, guest, hasGuest) }
        }

    @Test fun optionsShowPhoneGoogleAndGuest() {
        var guestTaps = 0
        show(SignInState(), SignInActions(onGuest = { guestTaps++ }))
        compose.onNodeWithText("Send code").assertIsEnabled()
        compose.onNodeWithText("Continue with Google").assertIsEnabled()
        compose.onNodeWithText("Continue as guest").performScrollTo().performClick()
        assertEquals(1, guestTaps)
    }

    @Test fun phoneFieldIsEthiopiaOnlyWithAGoogleShortcut() {
        var google = 0
        show(SignInState(), SignInActions(onGoogle = { google++ }))
        compose.onNodeWithText("+251").assertExists()
        compose.onNodeWithText("Ethiopian numbers only (Ethio telecom). Outside Ethiopia? Use Google.").assertExists()
        compose.onNodeWithText("Use Google sign-in").performScrollTo().performClick()
        assertEquals(1, google)
    }

    @Test fun noGoogleShortcutWhenGoogleIsUnavailable() {
        show(SignInState(), google = false)
        compose.onNodeWithText("Use Google sign-in").assertDoesNotExist()
    }

    @Test fun buttonsTheBuildCannotServeAreHidden() {
        show(SignInState(), google = false, guest = false)
        compose.onNodeWithText("Continue with Google").assertDoesNotExist()
        compose.onNodeWithText("Continue as guest").assertDoesNotExist()
    }

    @Test fun existingGuestSessionHidesGuestButtonAndSaysDataIsKept() {
        show(SignInState(), hasGuest = true)
        compose.onNodeWithText("Continue as guest").assertDoesNotExist()
        compose.onNodeWithText("You are using a guest session. If you sign in, your guest data is kept.").assertExists()
    }

    @Test fun busyDisablesTheActionsAndShowsProgress() {
        show(SignInState(busy = Busy.PHONE))
        compose.onNodeWithText("Send code").assertIsNotEnabled()
        compose.onNodeWithText("Please wait…").assertExists()
    }

    @Test fun errorMessagesAreShownAsText() {
        show(SignInState(message = SignInMessage.PhoneNotEthiopianMobile))
        compose.onNodeWithText("Enter a 9-digit Ethio telecom mobile number starting with 9, for example 911 234 567.").assertExists()
    }

    @Test fun codeStepShowsAttemptsAndTheCooldown() {
        val far = System.currentTimeMillis() + 50_000
        show(SignInState(step = SignInStep.CODE, phoneE164 = "+251911234567", challenge = challenge, attemptsLeft = 3, resendAtMs = far))
        compose.onNodeWithText("Attempts left: 3").assertExists()
        compose.onNodeWithText("Send a new code").assertIsNotEnabled()
        compose.onNodeWithText("Verify").assertIsNotEnabled() // no code typed yet
    }

    @Test fun deadChallengeOnlyOffersANewCode() {
        show(
            SignInState(
                step = SignInStep.CODE, phoneE164 = "+251911234567", challenge = challenge, challengeDead = true,
                attemptsLeft = 0, message = SignInMessage.OtpTooManyAttempts, resendAtMs = System.currentTimeMillis() + 50_000,
            ),
        )
        compose.onNodeWithText("Too many wrong codes. Ask for a new code.").assertExists()
        compose.onNodeWithText("Send a new code").assertIsEnabled()
        compose.onNodeWithText("Verify").assertIsNotEnabled()
    }

    @Test fun guestDataOutcomesAreExplained() {
        show(SignInState(step = SignInStep.DONE, outcome = SignInOutcome(AccountKind.REGISTERED, isNewUser = true, guestLink = GuestLink.UPGRADED_IN_PLACE)))
        compose.onNodeWithText("Your guest data was kept: this guest session is now your account.").assertExists()
    }

    @Test fun mergedOutcomeIsExplained() {
        show(SignInState(step = SignInStep.DONE, outcome = SignInOutcome(AccountKind.REGISTERED, isNewUser = false, guestLink = GuestLink.MERGED)))
        compose.onNodeWithText("Your guest data was kept: it was added to your existing account.").assertExists()
        compose.onNodeWithText("Welcome back.").assertExists()
    }
}

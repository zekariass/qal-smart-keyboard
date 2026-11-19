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

    // --- e-mail (ADR 0033) ------------------------------------------------------------------

    @Test fun phoneAndEmailAreTwoOptionsNextToGoogle() {
        var picked: SignInMethod? = null
        show(SignInState(), SignInActions(onMethod = { picked = it }))
        compose.onNodeWithText("Phone").assertExists()
        compose.onNodeWithText("Email").performClick()
        assertEquals(SignInMethod.EMAIL, picked)
        compose.onNodeWithText("Continue with Google").assertExists()
    }

    @Test fun emailSignInFormHasPasswordToggleAndForgotLink() {
        var submit = 0
        var forgot = 0
        var toggles = 0
        var mode: EmailMode? = null
        show(
            SignInState(method = SignInMethod.EMAIL, email = "selam@example.com", password = "secret words here"),
            SignInActions(onEmailSubmit = { submit++ }, onForgot = { forgot++ }, onTogglePassword = { toggles++ }, onEmailMode = { mode = it }),
        )
        compose.onNodeWithText("Send code").assertDoesNotExist() // the phone form is hidden
        // shown masked (the raw value stays in the field's input semantics only)
        compose.onNode(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString("•".repeat("secret words here".length)),
            ),
        ).assertExists()
        compose.onNodeWithText("Show").performClick()
        compose.onNodeWithText("Sign in with email").performClick()
        compose.onNodeWithText("Forgot password?").performScrollTo().performClick()
        compose.onNodeWithText("New here? Create an account").performScrollTo().performClick()
        assertEquals(1, toggles)
        assertEquals(1, submit)
        assertEquals(1, forgot)
        assertEquals(EmailMode.CREATE, mode)
        compose.onNodeWithText("I confirm that I am old enough to create an account where I live.").assertDoesNotExist()
    }

    @Test fun createModeAsksForTheAgeConfirmationAndShowsTheRules() {
        show(SignInState(method = SignInMethod.EMAIL, emailMode = EmailMode.CREATE, password = "visible words", passwordVisible = true))
        compose.onNodeWithText("Create account").assertExists()
        compose.onNodeWithText("visible words").assertExists()
        compose.onNodeWithText("Hide").assertExists()
        compose.onNodeWithText("I confirm that I am old enough to create an account where I live.").assertExists()
        compose.onNodeWithText("At least 10 characters. A short sentence is easy to remember and hard to guess.").assertExists()
        compose.onNodeWithText("Forgot password?").assertDoesNotExist()
    }

    @Test fun emailCodeStepNamesTheAddressAndNeedsNoAgeCheckbox() {
        show(SignInState(step = SignInStep.EMAIL_CODE, method = SignInMethod.EMAIL, email = "selam@example.com", challenge = challenge))
        compose.onNodeWithText("We sent a 6-digit code to selam@example.com. Check your inbox and spam folder.").assertExists()
        compose.onNodeWithText("Use another email").assertExists()
        compose.onNodeWithText("I confirm that I am old enough to create an account where I live.").assertDoesNotExist()
    }

    @Test fun resetStepsAndMessages() {
        var submit = 0
        show(
            SignInState(
                step = SignInStep.RESET_CODE, method = SignInMethod.EMAIL, email = "selam@example.com", code = "123456",
                newPassword = "tej and tibs at noon", notice = SignInNotice.RESET_CODE_SENT, message = SignInMessage.ResetCodeInvalid,
                resendAtMs = System.currentTimeMillis() + 50_000,
            ),
            SignInActions(onResetSubmit = { submit++ }),
        )
        compose.onNodeWithText("Code sent. It can take a minute to arrive.").assertExists()
        compose.onNodeWithText("That code is not right or has expired. Ask for a new code.").assertExists()
        compose.onNodeWithText("Send a new code").assertIsNotEnabled()
        compose.onNodeWithText("Change password").performClick()
        assertEquals(1, submit)
    }

    @Test fun lockedAccountSaysWhenToTryAgain() {
        show(SignInState(method = SignInMethod.EMAIL, message = SignInMessage.AccountLocked(840)))
        compose.onNodeWithText("Too many wrong passwords. Try again in 14 min or reset your password.").assertExists()
    }
}

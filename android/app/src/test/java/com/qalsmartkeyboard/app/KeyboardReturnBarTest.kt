package com.qalsmartkeyboard.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings opened from the keyboard gear: "Back to typing" and the system Back both return (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardReturnBarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun backToTypingButtonReturns() {
        var done = 0
        compose.setContent { KeyboardReturnBar(onDone = { done++ }) }
        compose.onNodeWithText("Back to typing").performClick()
        assertEquals(1, done)
    }

    @Test fun systemBackReturnsToo() {
        var done = 0
        compose.setContent { KeyboardReturnBar(onDone = { done++ }) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, done)
    }
}

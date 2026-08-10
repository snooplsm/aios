package com.aios.phone.telecom

import org.junit.Assert.assertEquals
import org.junit.Test

class CallSelectionPolicyTest {
    @Test
    fun newRingingCallPreemptsTheSelectedActiveCall() {
        assertEquals(
            "waiting",
            CallSelectionPolicy.afterCallAdded(
                currentSelection = "active",
                newCallId = "waiting",
                newCallIsRinging = true,
                currentSelectionStillPresent = true,
            ),
        )
    }

    @Test
    fun backgroundCallDoesNotStealOwnerSelection() {
        assertEquals(
            "active",
            CallSelectionPolicy.afterCallAdded(
                currentSelection = "active",
                newCallId = "outgoing",
                newCallIsRinging = false,
                currentSelectionStillPresent = true,
            ),
        )
    }

    @Test
    fun delayedRingingTransitionPreemptsTheSelectedCall() {
        assertEquals(
            "waiting",
            CallSelectionPolicy.afterStateChanged(
                currentSelection = "active",
                changedCallId = "waiting",
                changedCallIsRinging = true,
            ),
        )
    }

    @Test
    fun nonRingingTransitionKeepsTheCurrentSelection() {
        assertEquals(
            "active",
            CallSelectionPolicy.afterStateChanged(
                currentSelection = "active",
                changedCallId = "background",
                changedCallIsRinging = false,
            ),
        )
    }

    @Test
    fun postDialPromptSelectsTheCallThatNeedsOwnerInput() {
        assertEquals(
            "waiting-for-digits",
            CallSelectionPolicy.forOwnerPrompt(
                currentSelection = "other-call",
                promptCallId = "waiting-for-digits",
            ),
        )
    }
}

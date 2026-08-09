package com.aios.phone.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantCallContractTest {
    @Test
    fun onlyNewerPositiveAssistantStateReplacesVisibleState() {
        assertTrue(AssistantCallSemantics.shouldReplace(null, 1))
        assertTrue(AssistantCallSemantics.shouldReplace(1, 2))
        assertFalse(AssistantCallSemantics.shouldReplace(2, 2))
        assertFalse(AssistantCallSemantics.shouldReplace(2, 1))
        assertFalse(AssistantCallSemantics.shouldReplace(null, 0))
    }
}

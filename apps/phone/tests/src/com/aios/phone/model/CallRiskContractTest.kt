package com.aios.phone.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRiskContractTest {
    @Test
    fun wireValuesAreExactAndUnknownValuesFailClosed() {
        assertEquals(
            CallRiskLabel.LIKELY_LEGITIMATE,
            CallRiskLabel.fromWire("likely_legitimate"),
        )
        assertEquals(CallRiskSource.MODEL, CallRiskSource.fromWire("model"))
        assertNull(CallRiskLabel.fromWire("legitimate"))
        assertNull(CallRiskSource.fromWire("cloud"))
    }

    @Test
    fun labelsRequireConsistentScores() {
        assertTrue(CallRiskLabel.LIKELY_LEGITIMATE.accepts(15))
        assertFalse(CallRiskLabel.LIKELY_LEGITIMATE.accepts(16))
        assertTrue(CallRiskLabel.UNKNOWN.accepts(49))
        assertFalse(CallRiskLabel.UNKNOWN.accepts(50))
        assertTrue(CallRiskLabel.SUSPICIOUS.accepts(50))
        assertFalse(CallRiskLabel.SUSPICIOUS.accepts(75))
        assertTrue(CallRiskLabel.HIGH_RISK.accepts(75))
        assertFalse(CallRiskLabel.HIGH_RISK.accepts(101))
    }

    @Test
    fun reasonCodesRemainBoundedMachineValues() {
        assertTrue(CallRiskSemantics.isValidReasonCode("model_service_request"))
        assertFalse(CallRiskSemantics.isValidReasonCode("Service request"))
        assertFalse(CallRiskSemantics.isValidReasonCode(""))
        assertFalse(CallRiskSemantics.isValidReasonCode("a".repeat(71)))
    }

    @Test
    fun explanationsDoNotExposeRawModelReasonCodes() {
        assertEquals(
            "This number matches one of your contacts.",
            CallRiskSemantics.explanation(
                CallRiskLabel.LIKELY_LEGITIMATE,
                "known_contact",
            ),
        )
        assertEquals(
            "Strong on-device signals match common scam patterns.",
            CallRiskSemantics.explanation(CallRiskLabel.HIGH_RISK, "model_novel_signal"),
        )
    }

    @Test
    fun onlyNewerPositiveRevisionsReplaceVisibleState() {
        assertTrue(CallRiskSemantics.shouldReplace(null, 1))
        assertTrue(CallRiskSemantics.shouldReplace(1, 2))
        assertFalse(CallRiskSemantics.shouldReplace(2, 2))
        assertFalse(CallRiskSemantics.shouldReplace(2, 1))
        assertFalse(CallRiskSemantics.shouldReplace(null, 0))
    }
}

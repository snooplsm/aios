package com.aios.messaging.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageContextPolicyTest {
    private val valid = ProviderContextRecord(
        sourceType = MessageContextPolicy.SOURCE_SMS,
        sourceId = "42",
        address = "+1 202 555 0142",
        countryIso = "us",
        eventAtEpochMillis = 1_700_000_000_000L,
        text = "Need an estimate tomorrow",
    )

    @Test
    fun sanitizesBoundedProviderContent() {
        val value = MessageContextPolicy.sanitize(
            valid.copy(
                address = "  ${"1".repeat(100)}  ",
                countryIso = "Us",
                text = "  ${"x".repeat(5_000)}  ",
            ),
        )!!

        assertEquals(MessageContextPolicy.MAX_ADDRESS_CHARS, value.address.length)
        assertEquals("US", value.countryIso)
        assertEquals(MessageContextPolicy.MAX_INDEX_CHARS, value.text.length)
    }

    @Test
    fun rejectsRecordsThatCannotOwnContext() {
        assertNull(MessageContextPolicy.sanitize(valid.copy(sourceType = "call")))
        assertNull(MessageContextPolicy.sanitize(valid.copy(sourceId = "0")))
        assertNull(MessageContextPolicy.sanitize(valid.copy(address = " ")))
        assertNull(MessageContextPolicy.sanitize(valid.copy(eventAtEpochMillis = 0L)))
        assertNull(MessageContextPolicy.sanitize(valid.copy(text = " ")))
    }

    @Test
    fun keyedFingerprintIsStableAndCoversEveryStoredField() {
        val firstKey = ByteArray(32) { 1 }
        val secondKey = ByteArray(32) { 2 }
        val baseline = MessageContextPolicy.fingerprint(valid, firstKey)

        assertEquals(baseline, MessageContextPolicy.fingerprint(valid, firstKey))
        assertTrue(baseline.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(baseline, MessageContextPolicy.fingerprint(valid, secondKey))
        assertNotEquals(baseline, MessageContextPolicy.fingerprint(
            valid.copy(sourceType = MessageContextPolicy.SOURCE_MMS),
            firstKey,
        ))
        assertNotEquals(baseline, MessageContextPolicy.fingerprint(
            valid.copy(sourceId = "43"),
            firstKey,
        ))
        assertNotEquals(baseline, MessageContextPolicy.fingerprint(
            valid.copy(address = "+1 202 555 0199"),
            firstKey,
        ))
        assertNotEquals(baseline, MessageContextPolicy.fingerprint(
            valid.copy(countryIso = "ES"),
            firstKey,
        ))
        assertNotEquals(baseline, MessageContextPolicy.fingerprint(
            valid.copy(text = "Changed"),
            firstKey,
        ))
        assertNotEquals(baseline, MessageContextPolicy.fingerprint(
            valid.copy(eventAtEpochMillis = valid.eventAtEpochMillis + 1L),
            firstKey,
        ))
    }
}

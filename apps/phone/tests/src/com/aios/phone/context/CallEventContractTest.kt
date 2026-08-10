package com.aios.phone.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallEventContractTest {
    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun contextTextContainsNoAddress() {
        val record = record("calllog:1", "+15551234567", 10L, duration = 42L, video = true)

        assertEquals("Incoming call answered. Duration: 42 seconds. Video call.", record.contextText())
        assertFalse(record.contextText().contains("5551234567"))
        assertFalse(record.toString().contains("5551234567"))
    }

    @Test
    fun fingerprintIsKeyedAndTracksRelevantChanges() {
        val first = record("calllog:1", "+15551234567", 10L)
        val same = first.copy()
        val changed = first.copy(durationSeconds = 1L)

        assertEquals(64, first.fingerprint(secret).length)
        assertEquals(first.fingerprint(secret), same.fingerprint(secret))
        assertNotEquals(first.fingerprint(secret), changed.fingerprint(secret))
        assertFalse(first.fingerprint(secret).contains("5551234567"))
    }

    @Test
    fun reconciliationDeletesMissingAndUpsertsOnlyChangedRows() {
        val unchanged = record("calllog:1", "+15550000001", 30L)
        val changed = record("calllog:2", "+15550000002", 20L, duration = 4L)
        val indexed = mapOf(
            unchanged.sourceId to unchanged.fingerprint(secret),
            changed.sourceId to changed.copy(durationSeconds = 3L).fingerprint(secret),
            "calllog:3" to "a".repeat(64),
        )

        val mutations = CallEventReconciler.reconcile(listOf(unchanged, changed), indexed, secret)

        assertEquals(CallEventMutation.Delete("calllog:3"), mutations[0])
        assertTrue(mutations[1] is CallEventMutation.Upsert)
        assertEquals(changed, (mutations[1] as CallEventMutation.Upsert).record)
        assertEquals(2, mutations.size)
    }

    @Test
    fun reconciliationKeepsOnlyNewestBoundedSet() {
        val records = (1..300).map { index ->
            record("calllog:$index", "+1555${index.toString().padStart(7, '0')}", index.toLong())
        }
        val indexed = mapOf("calllog:1" to records.first().fingerprint(secret))

        val mutations = CallEventReconciler.reconcile(records, indexed, secret)
        val upserts = mutations.filterIsInstance<CallEventMutation.Upsert>()

        assertEquals(CallEventReconciler.MAX_INDEXED_EVENTS, upserts.size)
        assertTrue(CallEventMutation.Delete("calllog:1") in mutations)
        assertEquals("calllog:300", upserts.first().record.sourceId)
        assertEquals("calllog:45", upserts.last().record.sourceId)
    }

    @Test
    fun unknownAndMalformedRowsRemainBoundedAndSafe() {
        val malformed = record("raw-number", "+15551234567", 20L)
        val valid = record("calllog:9", "+15557654321", 10L).copy(kind = CallEventKind.UNKNOWN)

        val mutations = CallEventReconciler.reconcile(listOf(malformed, valid), emptyMap(), secret)

        assertEquals(1, mutations.size)
        val mutation = mutations.single() as CallEventMutation.Upsert
        assertEquals("Call event.", mutation.record.contextText())
    }

    private fun record(
        id: String,
        address: String,
        at: Long,
        duration: Long = 0L,
        video: Boolean = false,
    ) = CallEventRecord(
        sourceId = id,
        address = address,
        countryIso = "US",
        kind = CallEventKind.INCOMING,
        eventAtEpochMillis = at,
        durationSeconds = duration,
        isVideo = video,
    )
}

package com.aios.phone.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TranscriptTimelineReducerTest {
    @Test
    fun cumulativePartialReplacesItsOpenDirection() {
        val first = segment("downlink", "I need", isFinal = false, startMillis = 0L)
        val revised = segment(
            "downlink",
            "I need an estimate",
            isFinal = false,
            startMillis = 0L,
        )

        val result = TranscriptTimelineReducer.reduce(listOf(first), revised, 40)

        assertEquals(listOf(revised), result)
    }

    @Test
    fun interleavedDirectionsReplaceOnlyTheirOwnOpenTurn() {
        val callerPartial = segment("downlink", "My sink", false, 0L)
        val ownerPartial = segment("uplink", "I can", false, 100L)
        val callerRevision = segment("downlink", "My sink is leaking", false, 0L)

        val result = TranscriptTimelineReducer.reduce(
            listOf(callerPartial, ownerPartial),
            callerRevision,
            40,
        )

        assertEquals(listOf(callerRevision, ownerPartial), result)
    }

    @Test
    fun interleavedFinalReplacesPartialWithoutDuplicatingWords() {
        val callerPartial = segment("downlink", "Necesito", false, 0L, "es")
        val ownerPartial = segment("uplink", "Claro", false, 200L, "es")
        val callerFinal = segment("downlink", "Necesito una cita", true, 0L, "es")

        val result = TranscriptTimelineReducer.reduce(
            listOf(callerPartial, ownerPartial),
            callerFinal,
            40,
        )

        assertEquals(listOf(callerFinal, ownerPartial), result)
    }

    @Test
    fun newTurnAppendsAfterThePreviousDirectionWasFinalized() {
        val callerFinal = segment("downlink", "First turn", true, 0L)
        val nextPartial = segment("downlink", "Second", false, 4_000L)

        val result = TranscriptTimelineReducer.reduce(
            listOf(callerFinal),
            nextPartial,
            40,
        )

        assertEquals(listOf(callerFinal, nextPartial), result)
    }

    @Test
    fun malformedCallbackCannotReplaceVisibleTranscript() {
        val existing = listOf(segment("downlink", "Current words", false, 0L))
        val invalid = listOf(
            segment("sideways", "replacement", false, 0L),
            segment("downlink", "replacement", false, 0L, "fr"),
            segment("downlink", "   ", false, 0L),
            segment("downlink", "replacement", false, -1L),
        )

        invalid.forEach { candidate ->
            assertEquals(existing, TranscriptTimelineReducer.reduce(existing, candidate, 40))
        }
    }

    @Test
    fun completedTimelineKeepsOnlyTheNewestBoundedRows() {
        val existing = (0L until 4L).map { index ->
            segment("downlink", "turn $index", true, index * 1_000L)
        }
        val newest = segment("uplink", "newest", true, 5_000L)

        val result = TranscriptTimelineReducer.reduce(existing, newest, 3)

        assertEquals(listOf(existing[2], existing[3], newest), result)
    }

    @Test
    fun nonPositiveBoundIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptTimelineReducer.reduce(
                emptyList(),
                segment("downlink", "hello", false, 0L),
                0,
            )
        }
    }

    private fun segment(
        direction: String,
        text: String,
        isFinal: Boolean,
        startMillis: Long,
        language: String = "en",
    ) = TranscriptUiState(direction, language, text, isFinal, startMillis)
}

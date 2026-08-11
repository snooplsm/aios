package com.aios.messaging.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestOperationQueueTest {
    @Test
    fun replacementRejectsLateCompletionFromSupersededOperation() {
        val queue = LatestOperationQueue<Any>(3)
        val stale = Any()
        val current = Any()
        assertNull(queue.put("complete:token", stale))
        assertNull(queue.put("complete:token", current))

        assertFalse(queue.removeIfCurrent("complete:token", stale))
        assertSame(current, queue.firstOrNull())
        assertTrue(queue.removeIfCurrent("complete:token", current))
        assertNull(queue.firstOrNull())
    }

    @Test
    fun overflowEvictsOldestOperationAtTheDeclaredBound() {
        val queue = LatestOperationQueue<String>(2)
        queue.put("one", "one")
        queue.put("two", "two")
        assertEquals("one", queue.put("three", "three"))
        assertEquals(2, queue.size)
        assertEquals("two", queue.firstOrNull())
    }

    @Test
    fun overflowDoesNotEvictTheOperationCurrentlyCrossingBinder() {
        val queue = LatestOperationQueue<Any>(2)
        val active = Any()
        val waiting = Any()
        val newest = Any()
        queue.put("active", active)
        queue.put("waiting", waiting)

        assertSame(waiting, queue.put("newest", newest, protectedKey = "active"))
        assertTrue(queue.isCurrent("active", active))
        assertTrue(queue.isCurrent("newest", newest))
    }

    @Test
    fun replacementOfInFlightKeyRemainsProtectedFromLaterOverflow() {
        val queue = LatestOperationQueue<Any>(2)
        val active = Any()
        val replacement = Any()
        val waiting = Any()
        queue.put("complete:a", active)
        queue.put("waiting", waiting)
        queue.put("complete:a", replacement, protectedKey = "complete:a")

        assertSame(waiting, queue.put("newest", Any(), protectedKey = "complete:a"))
        assertTrue(queue.isCurrent("complete:a", replacement))
    }

    @Test
    fun cancellationCanRemoveOnlyWorkForItsAssociation() {
        val queue = LatestOperationQueue<String>(4)
        queue.put("stage:a", "stage-a")
        queue.put("complete:a", "complete-a")
        queue.put("stage:b", "stage-b")

        assertEquals(
            listOf("stage-a", "complete-a"),
            queue.removeWhere { it.endsWith(":a") },
        )
        assertEquals("stage-b", queue.firstOrNull())
    }
}

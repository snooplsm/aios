package com.aios.callintelligence;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

public final class CallerHistoryConversationPolicyTest {
    private static final String FIRST = "0".repeat(64);
    private static final String SECOND = "a".repeat(64);

    @Test
    public void validOpaqueExclusionsAreBoundedAndSorted() {
        Set<String> values = CallerHistoryConversationPolicy.validateRequested(
                new String[]{SECOND, FIRST});

        assertArrayEquals(
                new String[]{FIRST, SECOND},
                CallerHistoryConversationPolicy.sortedArray(values));
        assertFalse(CallerHistoryConversationPolicy.isAllowed(FIRST, values));
        assertTrue(CallerHistoryConversationPolicy.isAllowed("b".repeat(64), values));
    }

    @Test
    public void invalidRequestedOrStoredIdentitiesFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
                CallerHistoryConversationPolicy.validateRequested(null));
        assertThrows(IllegalArgumentException.class, () ->
                CallerHistoryConversationPolicy.validateRequested(
                        new String[]{FIRST, FIRST}));
        assertThrows(IllegalArgumentException.class, () ->
                CallerHistoryConversationPolicy.validateRequested(
                        new String[]{"+15555550182"}));
        assertNull(CallerHistoryConversationPolicy.validateStored(Set.of("raw-number")));
        assertFalse(CallerHistoryConversationPolicy.isAllowed(SECOND, Set.of("raw-number")));
        assertFalse(CallerHistoryConversationPolicy.isAllowed("", Set.of()));
    }
}

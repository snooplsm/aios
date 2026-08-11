package com.aios.callintelligence;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CallerHistorySourcePolicyTest {
    @Test
    public void allOwnerCategoriesExpandToTheExactBoundedScope() {
        assertArrayEquals(new String[]{
                "sms", "mms", "call_event", "call_artifact", "contact_note",
                "media_metadata"},
                CallerHistorySourcePolicy.selected(true, true, true));
    }

    @Test
    public void exclusionsRemoveSourcesBeforeContextQuery() {
        assertArrayEquals(
                new String[]{"call_event", "call_artifact", "contact_note"},
                CallerHistorySourcePolicy.selected(false, true, false));
        assertArrayEquals(
                new String[]{"sms", "mms", "media_metadata"},
                CallerHistorySourcePolicy.selected(true, false, true));
    }

    @Test
    public void emptyUnknownAndDuplicateScopesFailClosed() {
        assertFalse(CallerHistorySourcePolicy.anyEnabled(false, false, false));
        assertTrue(CallerHistorySourcePolicy.anyEnabled(false, true, false));
        assertFalse(CallerHistorySourcePolicy.isValidScope(new String[0]));
        assertFalse(CallerHistorySourcePolicy.isValidScope(new String[]{"sms", "sms"}));
        assertFalse(CallerHistorySourcePolicy.isValidScope(new String[]{"unknown"}));
        assertTrue(CallerHistorySourcePolicy.isValidScope(
                CallerHistorySourcePolicy.selected(true, true, true)));
    }
}

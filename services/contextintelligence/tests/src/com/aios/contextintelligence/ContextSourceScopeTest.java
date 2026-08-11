package com.aios.contextintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class ContextSourceScopeTest {
    @Test
    public void sourceArgumentsPrecedeLaterQueryArguments() {
        ArrayList<String> arguments = new ArrayList<>(List.of("identity"));

        String clause = ContextSourceScope.selectionClause(
                new String[]{"sms", "call_artifact"}, arguments);

        assertEquals(" AND e.source_type IN (?,?)", clause);
        assertEquals(List.of("identity", "sms", "call_artifact"), arguments);
    }

    @Test
    public void emptyScopeCannotAccidentallyBecomeAnUnfilteredQuery() {
        assertThrows(IllegalArgumentException.class,
                () -> ContextSourceScope.selectionClause(new String[0], new ArrayList<>()));
        assertThrows(IllegalArgumentException.class,
                () -> ContextSourceScope.selectionClause(
                        new String[]{"sms", ""}, new ArrayList<>()));
    }
}

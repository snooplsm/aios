package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class PriorContextFormatterTest {
    @Test
    public void formatsOnlyBoundedIdentifierFreeContext() {
        List<PriorContextFormatter.Item> values = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            values.add(new PriorContextFormatter.Item(
                    "sms", 1_000L + index, "message " + index));
        }

        String result = PriorContextFormatter.format(values);

        assertTrue(result.startsWith("["));
        assertTrue(result.endsWith("]"));
        assertTrue(result.contains("message 0"));
        assertTrue(result.contains("message 7"));
        assertFalse(result.contains("message 8"));
        assertTrue(result.length() <= PriorContextFormatter.MAX_JSON_CHARS);
        assertFalse(result.contains("source_id"));
    }

    @Test
    public void rejectsUnknownRowsAndEscapesJsonText() {
        String result = PriorContextFormatter.format(List.of(
                new PriorContextFormatter.Item("unknown", 1L, "discard"),
                new PriorContextFormatter.Item("sms", 0L, "discard"),
                new PriorContextFormatter.Item("call_artifact", 2L,
                        "  caller said \"hello\"\nthen left  ")));

        assertEquals(
                "[{\"source_type\":\"call_artifact\",\"event_at_epoch_ms\":2,"
                        + "\"excerpt\":\"caller said \\\"hello\\\" then left\"}]",
                result);
    }
}

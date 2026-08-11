package com.aios.callintelligence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Maps owner-facing history categories to the exact context source allowlist. */
final class CallerHistorySourcePolicy {
    static final String SMS = "sms";
    static final String MMS = "mms";
    static final String CALL_EVENT = "call_event";
    static final String CALL_ARTIFACT = "call_artifact";
    static final String CONTACT_NOTE = "contact_note";
    static final String MEDIA_METADATA = "media_metadata";

    private static final Set<String> SUPPORTED = Set.of(
            SMS, MMS, CALL_EVENT, CALL_ARTIFACT, CONTACT_NOTE, MEDIA_METADATA);

    private CallerHistorySourcePolicy() {}

    static boolean anyEnabled(boolean messages, boolean calls, boolean photos) {
        return messages || calls || photos;
    }

    static String[] selected(boolean messages, boolean calls, boolean photos) {
        ArrayList<String> selected = new ArrayList<>(SUPPORTED.size());
        if (messages) {
            selected.add(SMS);
            selected.add(MMS);
        }
        if (calls) {
            selected.add(CALL_EVENT);
            selected.add(CALL_ARTIFACT);
            selected.add(CONTACT_NOTE);
        }
        if (photos) selected.add(MEDIA_METADATA);
        return selected.toArray(new String[0]);
    }

    static boolean isValidScope(String[] sourceTypes) {
        if (sourceTypes == null || sourceTypes.length < 1
                || sourceTypes.length > SUPPORTED.size()) return false;
        HashSet<String> unique = new HashSet<>();
        for (String sourceType : sourceTypes) {
            if (!SUPPORTED.contains(sourceType) || !unique.add(sourceType)) return false;
        }
        return true;
    }
}

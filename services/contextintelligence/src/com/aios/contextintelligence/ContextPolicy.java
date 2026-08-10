package com.aios.contextintelligence;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Fail-closed caller, source, retention, and size policy for communication RAG. */
final class ContextPolicy {
    static final int MAX_DOCUMENT_CHARS = 4_096;
    static final int MAX_QUERY_CHARS = 256;
    static final int MAX_QUERY_RESULTS = 8;
    static final int MAX_SNIPPET_CHARS = 512;
    static final long CALL_ARTIFACT_TTL_MILLIS = 24L * 60L * 60L * 1_000L;

    static final String SMS = "sms";
    static final String MMS = "mms";
    static final String CALL_EVENT = "call_event";
    static final String CALL_ARTIFACT = "call_artifact";
    static final String CONTACT_NOTE = "contact_note";
    static final String MEDIA_METADATA = "media_metadata";

    private static final Map<String, Set<String>> WRITERS = Map.of(
            "com.aios.messaging", Set.of(SMS, MMS),
            "com.aios.phone", Set.of(CALL_EVENT),
            "com.aios.callintelligence", Set.of(CALL_ARTIFACT),
            "com.aios.mediaintelligence", Set.of(MEDIA_METADATA));
    private static final Set<String> READERS = Set.of(
            "com.aios.messaging", "com.aios.phone", "com.aios.callintelligence");
    private static final Pattern IDENTITY = Pattern.compile(
            "(?:number|contact):[0-9a-f]{64}");

    private ContextPolicy() {}

    static boolean isClient(String packageName) {
        return WRITERS.containsKey(packageName) || READERS.contains(packageName);
    }

    static boolean canQuery(String packageName) {
        return READERS.contains(packageName);
    }

    static void validateWrite(
            String packageName,
            String sourceType,
            String sourceId,
            long revision,
            String conversationKey,
            String contactKey,
            String[] relatedConversationKeys,
            long eventAtEpochMillis,
            long expiresAtEpochMillis,
            String text) {
        Set<String> allowed = WRITERS.get(packageName);
        if (allowed == null || !allowed.contains(sourceType)) {
            throw new SecurityException("caller cannot write this context source");
        }
        if (sourceId == null || sourceId.isBlank() || sourceId.length() > 128
                || revision <= 0L || eventAtEpochMillis <= 0L
                || text == null || text.isBlank() || text.length() > MAX_DOCUMENT_CHARS) {
            throw new IllegalArgumentException("invalid communication context document");
        }
        validateIdentity(conversationKey, contactKey, relatedConversationKeys);
        if (CALL_ARTIFACT.equals(sourceType)) {
            long maximumExpiry = saturatedAdd(eventAtEpochMillis, CALL_ARTIFACT_TTL_MILLIS);
            if (expiresAtEpochMillis <= eventAtEpochMillis
                    || expiresAtEpochMillis > maximumExpiry) {
                throw new IllegalArgumentException("call artifacts must expire within 24 hours");
            }
        } else if (expiresAtEpochMillis != 0L) {
            throw new IllegalArgumentException("only call artifacts may carry a TTL");
        }
    }

    static void validateDelete(
            String packageName, String sourceType, String sourceId, long revision) {
        Set<String> allowed = WRITERS.get(packageName);
        if (allowed == null || !allowed.contains(sourceType)) {
            throw new SecurityException("caller cannot delete this context source");
        }
        if (sourceId == null || sourceId.isBlank() || sourceId.length() > 128
                || revision <= 0L) {
            throw new IllegalArgumentException("invalid communication context deletion");
        }
    }

    static void validateQuery(
            String packageName,
            String conversationKey,
            String contactKey,
            String[] relatedConversationKeys,
            String query,
            int limit,
            long nowEpochMillis) {
        if (!canQuery(packageName)) {
            throw new SecurityException("caller cannot query communication context");
        }
        validateIdentity(conversationKey, contactKey, relatedConversationKeys);
        if (query == null || query.length() > MAX_QUERY_CHARS
                || limit < 1 || limit > MAX_QUERY_RESULTS || nowEpochMillis <= 0L) {
            throw new IllegalArgumentException("invalid communication context query");
        }
    }

    static void validateIdentity(
            String conversationKey, String contactKey, String[] relatedConversationKeys) {
        if (conversationKey == null || !IDENTITY.matcher(conversationKey).matches()
                || contactKey == null
                || (!contactKey.isEmpty() && !IDENTITY.matcher(contactKey).matches())
                || relatedConversationKeys == null || relatedConversationKeys.length < 1
                || relatedConversationKeys.length > 32) {
            throw new IllegalArgumentException("invalid opaque conversation identity");
        }
        Set<String> related = new HashSet<>();
        for (String key : relatedConversationKeys) {
            if (key == null || !key.startsWith("number:")
                    || !IDENTITY.matcher(key).matches() || !related.add(key)) {
                throw new IllegalArgumentException("invalid related conversation identity");
            }
        }
        if (!related.contains(conversationKey)) {
            throw new IllegalArgumentException("primary conversation identity is missing");
        }
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}

package com.aios.callintelligence;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Validates bounded, salted conversation identities used for history exclusions. */
final class CallerHistoryConversationPolicy {
    static final int MAX_EXCLUDED_CONVERSATIONS = 256;
    private static final Pattern ADDRESS_HASH = Pattern.compile("[0-9a-f]{64}");

    private CallerHistoryConversationPolicy() {}

    static Set<String> validateRequested(String[] values) {
        if (values == null || values.length > MAX_EXCLUDED_CONVERSATIONS) {
            throw new IllegalArgumentException("invalid caller-history exclusions");
        }
        HashSet<String> result = new HashSet<>();
        for (String value : values) {
            if (!isAddressHash(value) || !result.add(value)) {
                throw new IllegalArgumentException("invalid caller-history exclusions");
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** Returns null for corrupted durable state so retrieval can fail closed. */
    static Set<String> validateStored(Set<String> values) {
        if (values == null) return Set.of();
        if (values.size() > MAX_EXCLUDED_CONVERSATIONS) return null;
        HashSet<String> result = new HashSet<>();
        for (String value : values) {
            if (!isAddressHash(value) || !result.add(value)) return null;
        }
        return Collections.unmodifiableSet(result);
    }

    static boolean isAllowed(String addressHash, Set<String> storedExclusions) {
        Set<String> exclusions = validateStored(storedExclusions);
        return exclusions != null
                && isAddressHash(addressHash)
                && !exclusions.contains(addressHash);
    }

    static String[] sortedArray(Set<String> validated) {
        return new TreeSet<>(validated).toArray(new String[0]);
    }

    private static boolean isAddressHash(String value) {
        return value != null && ADDRESS_HASH.matcher(value).matches();
    }
}

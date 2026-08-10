package com.aios.mediaintelligence;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Fail-closed validation and bounded hashing for conversation-linked photos. */
final class MediaAssociationPolicy {
    static final long MAX_PHOTO_BYTES = 128L * 1024L * 1024L;
    static final long INCOMPLETE_TTL_MILLIS = 24L * 60L * 60L * 1_000L;
    static final int MAX_CONTEXT_CHARS = 4_096;

    private static final Pattern IDENTITY = Pattern.compile(
            "(?:number|contact):[0-9a-f]{64}");
    private static final Pattern SOURCE_ID = Pattern.compile("mms:[1-9][0-9]{0,18}");

    private MediaAssociationPolicy() {}

    static String validateToken(String value) {
        if (value == null || value.length() != 36) {
            throw new IllegalArgumentException("invalid media-association token");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        try {
            if (!UUID.fromString(normalized).toString().equals(normalized)) {
                throw new IllegalArgumentException("non-canonical media-association token");
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("invalid media-association token", error);
        }
        return normalized;
    }

    static String sourceId(long providerId) {
        if (providerId <= 0L) throw new IllegalArgumentException("invalid MMS provider ID");
        return "mms:" + providerId;
    }

    static void validateSourceId(String value) {
        if (value == null || !SOURCE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid media-association source");
        }
    }

    static void validateStage(
            String token,
            String mimeType,
            String conversationKey,
            String contactKey,
            String[] relatedKeys,
            long eventAtEpochMillis) {
        validateToken(token);
        if (mimeType == null || mimeType.length() > 128 || !mimeType.startsWith("image/")
                || eventAtEpochMillis <= 0L) {
            throw new IllegalArgumentException("invalid selected-photo stage");
        }
        validateIdentity(conversationKey, contactKey, relatedKeys);
    }

    static void validateIdentity(
            String conversationKey, String contactKey, String[] relatedKeys) {
        if (conversationKey == null || !IDENTITY.matcher(conversationKey).matches()
                || contactKey == null
                || (!contactKey.isEmpty() && !IDENTITY.matcher(contactKey).matches())
                || relatedKeys == null || relatedKeys.length < 1 || relatedKeys.length > 32) {
            throw new IllegalArgumentException("invalid opaque conversation identity");
        }
        Set<String> unique = new HashSet<>();
        for (String key : relatedKeys) {
            if (key == null || !key.startsWith("number:")
                    || !IDENTITY.matcher(key).matches() || !unique.add(key)) {
                throw new IllegalArgumentException("invalid related conversation identity");
            }
        }
        if (!unique.contains(conversationKey)) {
            throw new IllegalArgumentException("primary conversation identity is missing");
        }
    }

    static String sha256(InputStream stream) throws IOException {
        if (stream == null) throw new IllegalArgumentException("photo stream is required");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            long total = 0L;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_PHOTO_BYTES) {
                    throw new IOException("selected photo exceeds association bound");
                }
                digest.update(buffer, 0, read);
            }
            if (total == 0L) throw new IOException("selected photo is empty");
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest.digest()) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    static boolean publishable(
            boolean staged,
            boolean carrierCompleted,
            boolean uniqueMediaMatch,
            boolean deletionPending) {
        return staged && carrierCompleted && uniqueMediaMatch && !deletionPending;
    }
}

package com.aios.modelbroker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Hashes the running build identity and compares it with benchmark evidence. */
final class BuildFingerprintPolicy {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private BuildFingerprintPolicy() {}

    static String sha256(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            throw new IllegalArgumentException("build fingerprint is required");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    fingerprint.getBytes(StandardCharsets.UTF_8));
            char[] encoded = new char[digest.length * 2];
            for (int index = 0; index < digest.length; index++) {
                int value = digest[index] & 0xff;
                encoded[index * 2] = HEX[value >>> 4];
                encoded[index * 2 + 1] = HEX[value & 0x0f];
            }
            return new String(encoded);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    static boolean matches(String evidenceDigest, String runningDigest) {
        if (!isDigest(evidenceDigest) || !isDigest(runningDigest)) {
            return false;
        }
        return MessageDigest.isEqual(
                evidenceDigest.getBytes(StandardCharsets.US_ASCII),
                runningDigest.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean isDigest(String value) {
        if (value == null || value.length() != 64) return false;
        for (int index = 0; index < value.length(); index++) {
            char item = value.charAt(index);
            if (!((item >= '0' && item <= '9') || (item >= 'a' && item <= 'f'))) {
                return false;
            }
        }
        return true;
    }
}

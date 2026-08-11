package com.aios.modelbroker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BuildFingerprintPolicyTest {
    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    public void hashesTheExactUtf8Fingerprint() {
        assertEquals(ABC_SHA256, BuildFingerprintPolicy.sha256("abc"));
        assertThrows(
                IllegalArgumentException.class,
                () -> BuildFingerprintPolicy.sha256(""));
    }

    @Test
    public void onlyExactLowercaseDigestsMatch() {
        assertTrue(BuildFingerprintPolicy.matches(ABC_SHA256, ABC_SHA256));
        assertFalse(BuildFingerprintPolicy.matches(ABC_SHA256, "0".repeat(64)));
        assertFalse(BuildFingerprintPolicy.matches(ABC_SHA256.toUpperCase(), ABC_SHA256));
        assertFalse(BuildFingerprintPolicy.matches(ABC_SHA256, null));
    }
}

package com.aios.modelbroker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class PolicyFileReaderTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void readsExactUtf8WithoutDesktopJavaApis() throws Exception {
        File policy = temporary.newFile("policy.json");
        String value = "{\"language\":\"español\"}";
        try (FileOutputStream stream = new FileOutputStream(policy)) {
            stream.write(value.getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(value, PolicyFileReader.readUtf8(policy));
    }

    @Test
    public void rejectsMissingEmptyAndOversizedPolicies() throws Exception {
        assertIOException(new File(temporary.getRoot(), "missing.json"));
        assertIOException(temporary.newFile("empty.json"));
        File oversized = temporary.newFile("oversized.json");
        try (FileOutputStream stream = new FileOutputStream(oversized)) {
            stream.getChannel().position(PolicyFileReader.MAX_POLICY_BYTES);
            stream.write(0);
        }
        assertIOException(oversized);
    }

    private static void assertIOException(File path) throws Exception {
        try {
            PolicyFileReader.readUtf8(path);
            fail("expected IOException");
        } catch (IOException expected) {
            // Fail-closed input rejection is the contract.
        }
    }
}

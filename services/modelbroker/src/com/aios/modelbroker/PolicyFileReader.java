package com.aios.modelbroker;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Bounded Android-compatible reader for AVB-protected broker policy files. */
final class PolicyFileReader {
    static final int MAX_POLICY_BYTES = 2 * 1024 * 1024;

    private PolicyFileReader() {}

    static String readUtf8(File path) throws IOException {
        if (path == null || !path.isFile()) {
            throw new IOException("policy file is absent, empty, or oversized");
        }
        long byteCount = path.length();
        if (byteCount <= 0L || byteCount > MAX_POLICY_BYTES) {
            throw new IOException("policy file is absent, empty, or oversized");
        }
        byte[] bytes = new byte[(int) byteCount];
        int offset = 0;
        try (FileInputStream stream = new FileInputStream(path)) {
            while (offset < bytes.length) {
                int count = stream.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IOException("policy file was truncated");
                if (count > 0) offset += count;
            }
            if (stream.read() >= 0) throw new IOException("policy file grew while reading");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

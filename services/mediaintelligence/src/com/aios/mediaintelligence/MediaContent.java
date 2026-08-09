package com.aios.mediaintelligence;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Generation and digest helpers used before and after inference. */
final class MediaContent {
    private static final int BUFFER_BYTES = 1024 * 1024;

    private MediaContent() {}

    static long generation(ContentResolver resolver, Uri uri) throws IOException {
        try (Cursor cursor = resolver.query(
                uri,
                new String[]{
                        MediaStore.MediaColumns.GENERATION_MODIFIED,
                        MediaStore.MediaColumns.IS_PENDING
                },
                null,
                null,
                null)) {
            if (cursor == null || !cursor.moveToFirst() || cursor.getInt(1) != 0) {
                throw new FileNotFoundException("media is absent or still pending");
            }
            return cursor.getLong(0);
        }
    }

    static String sha256(ContentResolver resolver, Uri uri) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream raw = resolver.openInputStream(uri);
            if (raw == null) {
                throw new FileNotFoundException("media stream is unavailable");
            }
            try (BufferedInputStream stream = new BufferedInputStream(raw, BUFFER_BYTES)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest.digest()) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }
}

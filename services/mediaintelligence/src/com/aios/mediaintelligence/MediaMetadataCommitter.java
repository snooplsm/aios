package com.aios.mediaintelligence;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Conservatively commits portable AIOS XMP to simple JPEGs.
 *
 * <p>The encrypted result index remains authoritative. A source backup and a durable journal are
 * synced before MediaStore is opened for truncating output. Recovery either verifies the candidate
 * or restores the exact original; an unexpected concurrent value is retained in app-private
 * storage before restoration.
 */
final class MediaMetadataCommitter {
    enum Outcome {
        WRITTEN,
        INDEX_ONLY
    }

    private static final String TAG = "AiosMediaMetadata";
    private static final String JOURNAL_DIRECTORY = "media_metadata_journal";
    private static final long MAX_JPEG_BYTES = 128L * 1024L * 1024L;
    private static final long SUPPRESSION_MILLIS = 24L * 60L * 60L * 1000L;
    private static final int BUFFER_BYTES = 1024 * 1024;

    private final ContentResolver resolver;
    private final File journalDirectory;

    MediaMetadataCommitter(Context context) {
        resolver = context.getContentResolver();
        journalDirectory = new File(context.getFilesDir(), JOURNAL_DIRECTORY);
    }

    Outcome commit(MediaJobStore.PortableJob job, MediaJobStore store) throws IOException {
        if (!"image/jpeg".equals(job.mimeType)) {
            store.markPortableSkipped(job.id);
            return Outcome.INDEX_ONLY;
        }
        Uri uri = trustedMediaUri(job.uri);
        byte[] original;
        try {
            original = readContent(uri);
        } catch (ContentTooLargeException error) {
            store.markPortableSkipped(job.id);
            return Outcome.INDEX_ONLY;
        }
        if (!sha256(original).equals(job.contentDigest)) {
            store.markPortableSkipped(job.id);
            return Outcome.INDEX_ONLY;
        }

        byte[] candidate;
        try {
            candidate = JpegXmpInjector.inject(original, job.portableXmp);
        } catch (JpegXmpInjector.UnsafeJpegException error) {
            store.markPortableSkipped(job.id);
            return Outcome.INDEX_ONLY;
        }
        if (candidate.length > MAX_JPEG_BYTES) {
            store.markPortableSkipped(job.id);
            return Outcome.INDEX_ONLY;
        }
        String candidateDigest = sha256(candidate);
        Journal journal = new Journal(
                job.id, job.uri, job.contentDigest, candidateDigest);
        ensureJournalDirectory();
        writeSynced(journal.backupFile(journalDirectory), original);
        try {
            writeJournal(journal);
        } catch (IOException | RuntimeException error) {
            journal.backupFile(journalDirectory).delete();
            throw error;
        }
        store.beginOwnMutation(job.uri, suppressionExpiry());

        try {
            writeContent(uri, candidate);
            long generation = verifyCandidate(uri, candidate, candidateDigest);
            store.finishOwnMutation(job.uri, generation, suppressionExpiry());
        } catch (IOException error) {
            try {
                restoreOriginal(journal, store);
            } catch (IOException | RuntimeException restoreError) {
                error.addSuppressed(restoreError);
            }
            throw error;
        }

        store.markPortableWritten(job.id);
        deleteJournal(journal);
        return Outcome.WRITTEN;
    }

    void recover(MediaJobStore store) {
        if (!journalDirectory.isDirectory()) {
            return;
        }
        File[] files = journalDirectory.listFiles(
                (directory, name) -> name.endsWith(".json"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try {
                recoverOne(readJournal(file), store);
            } catch (IOException | JSONException | RuntimeException error) {
                Log.e(TAG, "portable metadata recovery deferred: " + file, error);
            }
        }
    }

    private void recoverOne(Journal journal, MediaJobStore store) throws IOException {
        Uri uri = trustedMediaUri(journal.uri);
        File backupFile = journal.backupFile(journalDirectory);
        byte[] backup = readFile(backupFile);
        if (!sha256(backup).equals(journal.sourceDigest)) {
            throw new IOException("metadata journal backup digest mismatch");
        }
        byte[] current = readContent(uri);
        String currentDigest = sha256(current);
        if (currentDigest.equals(journal.candidateDigest)
                && JpegXmpInjector.containsOneAiosPacket(current)) {
            long generation = stableGeneration(uri, current);
            store.finishOwnMutation(journal.uri, generation, suppressionExpiry());
            store.markPortableWritten(journal.jobId);
            deleteJournal(journal);
            return;
        }
        if (currentDigest.equals(journal.sourceDigest)) {
            store.clearOwnMutation(journal.uri);
            deleteJournal(journal);
            return;
        }
        File conflict = new File(
                journalDirectory,
                journal.jobId + "-" + System.currentTimeMillis() + ".conflict");
        writeSynced(conflict, current);
        Log.e(TAG, "preserved unexpected media bytes before restoring " + journal.uri);
        writeContent(uri, backup);
        long generation = verifyContent(uri, backup, journal.sourceDigest);
        store.finishOwnMutation(journal.uri, generation, suppressionExpiry());
        deleteJournal(journal);
    }

    private void restoreOriginal(Journal journal, MediaJobStore store) throws IOException {
        byte[] backup = readFile(journal.backupFile(journalDirectory));
        if (!sha256(backup).equals(journal.sourceDigest)) {
            throw new IOException("cannot restore invalid metadata backup");
        }
        writeContent(trustedMediaUri(journal.uri), backup);
        long generation = verifyContent(
                trustedMediaUri(journal.uri), backup, journal.sourceDigest);
        store.finishOwnMutation(journal.uri, generation, suppressionExpiry());
        deleteJournal(journal);
    }

    private long verifyCandidate(Uri uri, byte[] expected, String expectedDigest)
            throws IOException {
        long generationBefore = MediaContent.generation(resolver, uri);
        byte[] actual = readContent(uri);
        long generationAfter = MediaContent.generation(resolver, uri);
        if (!MessageDigest.isEqual(expected, actual)
                || !sha256(actual).equals(expectedDigest)
                || !JpegXmpInjector.containsOneAiosPacket(actual)
                || generationBefore != generationAfter) {
            throw new IOException("portable JPEG verification failed");
        }
        return generationAfter;
    }

    private long verifyContent(Uri uri, byte[] expected, String expectedDigest)
            throws IOException {
        long generationBefore = MediaContent.generation(resolver, uri);
        byte[] actual = readContent(uri);
        long generationAfter = MediaContent.generation(resolver, uri);
        if (!MessageDigest.isEqual(expected, actual)
                || !sha256(actual).equals(expectedDigest)
                || generationBefore != generationAfter) {
            throw new IOException("restored JPEG verification failed");
        }
        return generationAfter;
    }

    private long stableGeneration(Uri uri, byte[] expected) throws IOException {
        long generationBefore = MediaContent.generation(resolver, uri);
        byte[] actual = readContent(uri);
        long generationAfter = MediaContent.generation(resolver, uri);
        if (generationBefore != generationAfter
                || !MessageDigest.isEqual(expected, actual)) {
            throw new IOException("media changed during recovery verification");
        }
        return generationAfter;
    }

    private Uri trustedMediaUri(String value) throws IOException {
        Uri uri = Uri.parse(value);
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                || !"media".equals(uri.getAuthority())) {
            throw new IOException("refusing non-MediaStore URI");
        }
        return uri;
    }

    private byte[] readContent(Uri uri) throws IOException {
        InputStream raw = resolver.openInputStream(uri);
        if (raw == null) {
            throw new FileNotFoundException("media stream is unavailable");
        }
        try (InputStream stream = raw) {
            return readBounded(stream);
        }
    }

    private byte[] readFile(File file) throws IOException {
        try (InputStream stream = new FileInputStream(file)) {
            return readBounded(stream);
        }
    }

    private byte[] readBounded(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0L;
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            total += read;
            if (total > MAX_JPEG_BYTES) {
                throw new ContentTooLargeException();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void writeContent(Uri uri, byte[] value) throws IOException {
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "rwt");
        if (descriptor == null) {
            throw new FileNotFoundException("writable media descriptor is unavailable");
        }
        try (ParcelFileDescriptor.AutoCloseOutputStream stream =
                     new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
            stream.write(value);
            stream.flush();
            stream.getFD().sync();
        }
    }

    private void writeSynced(File file, byte[] value) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(value);
            stream.flush();
            stream.getFD().sync();
        }
    }

    private void writeJournal(Journal journal) throws IOException {
        AtomicFile atomic = new AtomicFile(journal.journalFile(journalDirectory));
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            stream.write(journal.toJson().toString().getBytes(StandardCharsets.UTF_8));
            stream.flush();
            stream.getFD().sync();
            atomic.finishWrite(stream);
        } catch (IOException | RuntimeException error) {
            if (stream != null) {
                atomic.failWrite(stream);
            }
            throw error;
        }
    }

    private Journal readJournal(File file) throws IOException, JSONException {
        byte[] bytes = readFile(file);
        return Journal.fromJson(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
    }

    private void deleteJournal(Journal journal) {
        new AtomicFile(journal.journalFile(journalDirectory)).delete();
        if (!journal.backupFile(journalDirectory).delete()
                && journal.backupFile(journalDirectory).exists()) {
            Log.w(TAG, "could not delete committed metadata backup");
        }
    }

    private void ensureJournalDirectory() throws IOException {
        if (!journalDirectory.isDirectory() && !journalDirectory.mkdirs()) {
            throw new IOException("cannot create metadata journal directory");
        }
    }

    private static String sha256(byte[] value) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(value);
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest.digest()) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static long suppressionExpiry() {
        return System.currentTimeMillis() + SUPPRESSION_MILLIS;
    }

    private static final class ContentTooLargeException extends IOException {}

    private static final class Journal {
        final long jobId;
        final String uri;
        final String sourceDigest;
        final String candidateDigest;

        Journal(long jobId, String uri, String sourceDigest, String candidateDigest) {
            this.jobId = jobId;
            this.uri = uri;
            this.sourceDigest = sourceDigest;
            this.candidateDigest = candidateDigest;
        }

        File journalFile(File directory) {
            return new File(directory, jobId + ".json");
        }

        File backupFile(File directory) {
            return new File(directory, jobId + ".original");
        }

        JSONObject toJson() throws IOException {
            try {
                return new JSONObject()
                        .put("schema_version", 1)
                        .put("job_id", jobId)
                        .put("uri", uri)
                        .put("source_digest", sourceDigest)
                        .put("candidate_digest", candidateDigest);
            } catch (JSONException impossible) {
                throw new IOException("cannot encode metadata journal", impossible);
            }
        }

        static Journal fromJson(JSONObject value) throws JSONException, IOException {
            if (value.getInt("schema_version") != 1) {
                throw new IOException("unsupported metadata journal version");
            }
            long jobId = value.getLong("job_id");
            String sourceDigest = value.getString("source_digest");
            String candidateDigest = value.getString("candidate_digest");
            if (jobId <= 0L
                    || !sourceDigest.matches("[0-9a-f]{64}")
                    || !candidateDigest.matches("[0-9a-f]{64}")) {
                throw new IOException("invalid metadata journal fields");
            }
            return new Journal(
                    jobId, value.getString("uri"), sourceDigest, candidateDigest);
        }
    }
}

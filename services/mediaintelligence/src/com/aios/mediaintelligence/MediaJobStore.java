package com.aios.mediaintelligence;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Durable queue and encrypted-at-rest index (credential-encrypted app data). */
final class MediaJobStore extends SQLiteOpenHelper {
    private static final String DATABASE = "media_intelligence.db";
    private static final int VERSION = 3;
    private static final Pattern VOLUME_NAME = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    static final int STATUS_PENDING = 0;
    static final int STATUS_RUNNING = 1;
    static final int STATUS_INDEXED = 2;
    static final int STATUS_STALE = 3;
    static final int STATUS_FAILED = 4;
    static final int PORTABLE_SKIPPED = -1;
    static final int PORTABLE_PENDING = 0;
    static final int PORTABLE_WRITTEN = 1;

    MediaJobStore(Context context) {
        super(context, DATABASE, null, VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase database) {
        database.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE jobs ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "media_uri TEXT NOT NULL,"
                        + "generation INTEGER NOT NULL,"
                        + "mime_type TEXT NOT NULL,"
                        + "work_class INTEGER NOT NULL,"
                        + "status INTEGER NOT NULL,"
                        + "created_at_epoch_ms INTEGER NOT NULL,"
                        + "UNIQUE(media_uri, generation))");
        database.execSQL(
                "CREATE TABLE results ("
                        + "job_id INTEGER PRIMARY KEY,"
                        + "content_digest TEXT NOT NULL,"
                        + "result_json TEXT NOT NULL,"
                        + "model_id TEXT NOT NULL,"
                        + "model_digest TEXT NOT NULL,"
                        + "inferred_at_epoch_ms INTEGER NOT NULL,"
                        + "portable_xmp TEXT NOT NULL,"
                        + "portable_metadata_written INTEGER NOT NULL DEFAULT 0,"
                        + "FOREIGN KEY(job_id) REFERENCES jobs(_id) ON DELETE CASCADE)");
        database.execSQL(
                "CREATE TABLE own_mutations ("
                        + "media_uri TEXT PRIMARY KEY,"
                        + "generation INTEGER NOT NULL,"
                        + "expires_at_epoch_ms INTEGER NOT NULL)");
        createTimingTable(database);
        createScanStateTable(database);
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        if (oldVersion < 1 || oldVersion > newVersion || newVersion != VERSION) {
            throw new IllegalStateException("explicit media-index migration required");
        }
        if (oldVersion < 2) {
            createTimingTable(database);
        }
        if (oldVersion < 3) {
            createScanStateTable(database);
        }
    }

    private static void createTimingTable(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE timing_samples ("
                        + "job_id INTEGER PRIMARY KEY,"
                        + "media_kind TEXT NOT NULL CHECK(media_kind IN ('photo','video')),"
                        + "observed_to_index_ms INTEGER NOT NULL,"
                        + "queue_to_start_ms INTEGER NOT NULL,"
                        + "processing_ms INTEGER NOT NULL CHECK(processing_ms>=0),"
                        + "input_preparation_ms INTEGER NOT NULL"
                        + " CHECK(input_preparation_ms>=0),"
                        + "model_request_ms INTEGER NOT NULL CHECK(model_request_ms>=0),"
                        + "completed_at_epoch_ms INTEGER NOT NULL,"
                        + "FOREIGN KEY(job_id) REFERENCES jobs(_id) ON DELETE CASCADE)");
        database.execSQL(
                "CREATE INDEX timing_samples_kind_completed"
                        + " ON timing_samples(media_kind, completed_at_epoch_ms DESC)");
    }

    private static void createScanStateTable(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE media_scan_state ("
                        + "volume_name TEXT PRIMARY KEY,"
                        + "media_store_version TEXT NOT NULL,"
                        + "generation INTEGER NOT NULL CHECK(generation>=0),"
                        + "media_id INTEGER NOT NULL CHECK(media_id>=0))");
    }

    void enqueue(CaptureCoalescer.ObservedMedia media, int workClass) {
        ContentValues values = new ContentValues();
        values.put("media_uri", media.uri);
        values.put("generation", media.generation);
        values.put("mime_type", media.mimeType);
        values.put("work_class", workClass);
        values.put("status", STATUS_PENDING);
        values.put("created_at_epoch_ms", media.observedAtEpochMillis);
        SQLiteDatabase database = getWritableDatabase();
        long row = database.insertWithOnConflict(
                "jobs", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (row < 0L && !hasJob(database, media.uri, media.generation)) {
            throw new IllegalStateException("cannot durably enqueue media job");
        }
    }

    private static boolean hasJob(SQLiteDatabase database, String uri, long generation) {
        try (Cursor cursor = database.query(
                "jobs",
                new String[]{"1"},
                "media_uri=? AND generation=?",
                new String[]{uri, Long.toString(generation)},
                null,
                null,
                null,
                "1")) {
            return cursor.moveToFirst();
        }
    }

    ScanState scanState(String volumeName) {
        validateVolumeName(volumeName);
        try (Cursor cursor = getReadableDatabase().query(
                "media_scan_state",
                new String[]{"media_store_version", "generation", "media_id"},
                "volume_name=?",
                new String[]{volumeName},
                null,
                null,
                null)) {
            if (!cursor.moveToFirst()) return null;
            return new ScanState(cursor.getString(0), cursor.getLong(1), cursor.getLong(2));
        }
    }

    void writeScanState(
            String volumeName, String mediaStoreVersion, long generation, long mediaId) {
        validateVolumeName(volumeName);
        if (mediaStoreVersion == null || mediaStoreVersion.isBlank()
                || mediaStoreVersion.length() > 1_024 || generation < 0L || mediaId < 0L) {
            throw new IllegalArgumentException("invalid MediaStore scan state");
        }
        ContentValues values = new ContentValues();
        values.put("volume_name", volumeName);
        values.put("media_store_version", mediaStoreVersion);
        values.put("generation", generation);
        values.put("media_id", mediaId);
        long row = getWritableDatabase().insertWithOnConflict(
                "media_scan_state", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        if (row < 0L) throw new IllegalStateException("cannot store MediaStore scan state");
    }

    private static void validateVolumeName(String volumeName) {
        if (volumeName == null || !VOLUME_NAME.matcher(volumeName).matches()) {
            throw new IllegalArgumentException("invalid MediaStore volume");
        }
    }

    List<SourceRef> sourceBatch(long afterJobId, int limit) {
        if (afterJobId < 0L || limit < 1 || limit > 512) {
            throw new IllegalArgumentException("invalid media liveness page");
        }
        List<SourceRef> sources = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "jobs",
                new String[]{"_id", "media_uri"},
                "_id>?",
                new String[]{Long.toString(afterJobId)},
                null,
                null,
                "_id ASC",
                Integer.toString(limit))) {
            while (cursor.moveToNext()) {
                sources.add(new SourceRef(cursor.getLong(0), cursor.getString(1)));
            }
        }
        return sources;
    }

    void deleteMediaUri(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("invalid media URI");
        }
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete("jobs", "media_uri=?", new String[]{uri});
            database.delete("own_mutations", "media_uri=?", new String[]{uri});
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    void purgeVolume(String volumeName) {
        validateVolumeName(volumeName);
        String prefix = "content://media/" + volumeName + "/*";
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete("jobs", "media_uri GLOB ?", new String[]{prefix});
            database.delete("own_mutations", "media_uri GLOB ?", new String[]{prefix});
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    PendingJob claimNext(int workClass) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            PendingJob result = null;
            try (Cursor cursor = database.query(
                    "jobs",
                    new String[]{
                            "_id", "media_uri", "generation", "mime_type",
                            "created_at_epoch_ms"
                    },
                    "work_class=? AND status=?",
                    new String[]{Integer.toString(workClass), Integer.toString(STATUS_PENDING)},
                    null,
                    null,
                    "created_at_epoch_ms ASC",
                    "1")) {
                if (cursor.moveToFirst()) {
                    result = new PendingJob(
                            cursor.getLong(0), cursor.getString(1), cursor.getLong(2),
                            cursor.getString(3), cursor.getLong(4));
                }
            }
            if (result == null) {
                database.setTransactionSuccessful();
                return null;
            }
            ContentValues values = new ContentValues();
            values.put("status", STATUS_RUNNING);
            int changed = database.update(
                    "jobs",
                    values,
                    "_id=? AND status=?",
                    new String[]{Long.toString(result.id), Integer.toString(STATUS_PENDING)});
            if (changed != 1) {
                return null;
            }
            database.setTransactionSuccessful();
            return result;
        } finally {
            database.endTransaction();
        }
    }

    void markPending(long jobId) {
        markStatus(jobId, STATUS_PENDING);
    }

    void markStale(long jobId) {
        markStatus(jobId, STATUS_STALE);
    }

    void markFailed(long jobId) {
        markStatus(jobId, STATUS_FAILED);
    }

    void commitResult(
            PendingJob job,
            String contentDigest,
            String resultJson,
            String modelId,
            String modelDigest,
            long inferredAtEpochMillis,
            String portableXmp,
            MediaTiming.Sample timing) {
        if (!MediaTiming.kind(job.mimeType).equals(timing.mediaKind)
                || timing.completedAtEpochMillis != inferredAtEpochMillis) {
            throw new IllegalArgumentException("media timing does not match committed result");
        }
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            ContentValues result = new ContentValues();
            result.put("job_id", job.id);
            result.put("content_digest", contentDigest);
            result.put("result_json", resultJson);
            result.put("model_id", modelId);
            result.put("model_digest", modelDigest);
            result.put("inferred_at_epoch_ms", inferredAtEpochMillis);
            result.put("portable_xmp", portableXmp);
            result.put("portable_metadata_written", 0);
            long row = database.insertWithOnConflict(
                    "results", null, result, SQLiteDatabase.CONFLICT_REPLACE);
            if (row < 0L) {
                throw new IllegalStateException("cannot store media result");
            }
            ContentValues timingValues = new ContentValues();
            timingValues.put("job_id", job.id);
            timingValues.put("media_kind", timing.mediaKind);
            timingValues.put("observed_to_index_ms", timing.observedToIndexMillis);
            timingValues.put("queue_to_start_ms", timing.queueToStartMillis);
            timingValues.put("processing_ms", timing.processingMillis);
            timingValues.put("input_preparation_ms", timing.inputPreparationMillis);
            timingValues.put("model_request_ms", timing.modelRequestMillis);
            timingValues.put("completed_at_epoch_ms", timing.completedAtEpochMillis);
            long timingRow = database.insertWithOnConflict(
                    "timing_samples", null, timingValues, SQLiteDatabase.CONFLICT_REPLACE);
            if (timingRow < 0L) {
                throw new IllegalStateException("cannot store media timing");
            }
            ContentValues indexed = new ContentValues();
            indexed.put("status", STATUS_INDEXED);
            int changed = database.update(
                    "jobs",
                    indexed,
                    "_id=? AND status=?",
                    new String[]{Long.toString(job.id), Integer.toString(STATUS_RUNNING)});
            if (changed != 1) {
                throw new IllegalStateException("media job state changed during commit");
            }
            database.delete(
                    "jobs",
                    "media_uri=? AND _id<>?",
                    new String[]{job.uri, Long.toString(job.id)});
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    boolean hasPending(int workClass) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT 1 FROM jobs WHERE work_class=? AND status=? LIMIT 1",
                new String[]{Integer.toString(workClass), Integer.toString(STATUS_PENDING)})) {
            return cursor.moveToFirst();
        }
    }

    MediaTimingSummary.Snapshot timingSummary() {
        return MediaTimingSummary.snapshot(
                System.currentTimeMillis(),
                timingSamples(MediaTiming.KIND_PHOTO),
                timingSamples(MediaTiming.KIND_VIDEO));
    }

    private List<MediaTiming.Sample> timingSamples(String mediaKind) {
        List<MediaTiming.Sample> samples = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "timing_samples",
                new String[]{
                        "observed_to_index_ms", "queue_to_start_ms", "processing_ms",
                        "input_preparation_ms", "model_request_ms", "completed_at_epoch_ms"
                },
                "media_kind=?",
                new String[]{mediaKind},
                null,
                null,
                "completed_at_epoch_ms DESC",
                Integer.toString(MediaTimingSummary.MAX_SAMPLES_PER_KIND))) {
            while (cursor.moveToNext()) {
                samples.add(new MediaTiming.Sample(
                        mediaKind,
                        cursor.getLong(0),
                        cursor.getLong(1),
                        cursor.getLong(2),
                        cursor.getLong(3),
                        cursor.getLong(4),
                        cursor.getLong(5)));
            }
        }
        return samples;
    }

    PortableJob nextPortableMetadata(int workClass) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT jobs._id, jobs.media_uri, jobs.generation, jobs.mime_type,"
                        + " results.content_digest, results.portable_xmp"
                        + " FROM jobs JOIN results ON results.job_id=jobs._id"
                        + " WHERE jobs.work_class=? AND jobs.status=?"
                        + " AND results.portable_metadata_written=?"
                        + " ORDER BY jobs.created_at_epoch_ms ASC LIMIT 1",
                new String[]{
                        Integer.toString(workClass),
                        Integer.toString(STATUS_INDEXED),
                        Integer.toString(PORTABLE_PENDING)
                })) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return new PortableJob(
                    cursor.getLong(0),
                    cursor.getString(1),
                    cursor.getLong(2),
                    cursor.getString(3),
                    cursor.getString(4),
                    cursor.getString(5));
        }
    }

    boolean hasPortableMetadataPending(int workClass) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT 1 FROM jobs JOIN results ON results.job_id=jobs._id"
                        + " WHERE jobs.work_class=? AND jobs.status=?"
                        + " AND results.portable_metadata_written=? LIMIT 1",
                new String[]{
                        Integer.toString(workClass),
                        Integer.toString(STATUS_INDEXED),
                        Integer.toString(PORTABLE_PENDING)
                })) {
            return cursor.moveToFirst();
        }
    }

    void markPortableWritten(long jobId) {
        markPortableState(jobId, PORTABLE_WRITTEN);
    }

    void markPortableSkipped(long jobId) {
        markPortableState(jobId, PORTABLE_SKIPPED);
    }

    void beginOwnMutation(String uri, long expiresAtEpochMillis) {
        ContentValues values = new ContentValues();
        values.put("media_uri", uri);
        values.put("generation", -1L);
        values.put("expires_at_epoch_ms", expiresAtEpochMillis);
        getWritableDatabase().insertWithOnConflict(
                "own_mutations", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void finishOwnMutation(String uri, long generation, long expiresAtEpochMillis) {
        ContentValues values = new ContentValues();
        values.put("media_uri", uri);
        values.put("generation", generation);
        values.put("expires_at_epoch_ms", expiresAtEpochMillis);
        getWritableDatabase().insertWithOnConflict(
                "own_mutations", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void clearOwnMutation(String uri) {
        getWritableDatabase().delete(
                "own_mutations", "media_uri=?", new String[]{uri});
    }

    boolean shouldSuppressOwnMutation(String uri, long generation) {
        long now = System.currentTimeMillis();
        SQLiteDatabase database = getWritableDatabase();
        database.delete(
                "own_mutations",
                "expires_at_epoch_ms<?",
                new String[]{Long.toString(now)});
        try (Cursor cursor = database.query(
                "own_mutations",
                new String[]{"generation"},
                "media_uri=? AND expires_at_epoch_ms>=?",
                new String[]{uri, Long.toString(now)},
                null,
                null,
                null)) {
            if (!cursor.moveToFirst()) {
                return false;
            }
            long recordedGeneration = cursor.getLong(0);
            return recordedGeneration == -1L || recordedGeneration == generation;
        }
    }

    void recoverInterruptedWork() {
        ContentValues values = new ContentValues();
        values.put("status", STATUS_PENDING);
        getWritableDatabase().update(
                "jobs",
                values,
                "status=?",
                new String[]{Integer.toString(STATUS_RUNNING)});
    }

    private void markPortableState(long jobId, int state) {
        ContentValues values = new ContentValues();
        values.put("portable_metadata_written", state);
        int changed = getWritableDatabase().update(
                "results",
                values,
                "job_id=? AND portable_metadata_written=?",
                new String[]{
                        Long.toString(jobId), Integer.toString(PORTABLE_PENDING)
                });
        if (changed == 1) {
            return;
        }
        try (Cursor cursor = getReadableDatabase().query(
                "results",
                new String[]{"portable_metadata_written"},
                "job_id=?",
                new String[]{Long.toString(jobId)},
                null,
                null,
                null)) {
            if (cursor.moveToFirst() && cursor.getInt(0) == state) {
                return;
            }
        }
        throw new IllegalStateException("portable metadata state changed");
    }

    private void markStatus(long jobId, int status) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        getWritableDatabase().update(
                "jobs", values, "_id=?", new String[]{Long.toString(jobId)});
    }

    static final class PendingJob {
        final long id;
        final String uri;
        final long generation;
        final String mimeType;
        final long observedAtEpochMillis;

        PendingJob(
                long id,
                String uri,
                long generation,
                String mimeType,
                long observedAtEpochMillis) {
            this.id = id;
            this.uri = uri;
            this.generation = generation;
            this.mimeType = mimeType;
            this.observedAtEpochMillis = observedAtEpochMillis;
        }
    }

    static final class PortableJob {
        final long id;
        final String uri;
        final long generation;
        final String mimeType;
        final String contentDigest;
        final String portableXmp;

        PortableJob(
                long id,
                String uri,
                long generation,
                String mimeType,
                String contentDigest,
                String portableXmp) {
            this.id = id;
            this.uri = uri;
            this.generation = generation;
            this.mimeType = mimeType;
            this.contentDigest = contentDigest;
            this.portableXmp = portableXmp;
        }
    }

    static final class ScanState {
        final String mediaStoreVersion;
        final long generation;
        final long mediaId;

        ScanState(String mediaStoreVersion, long generation, long mediaId) {
            this.mediaStoreVersion = mediaStoreVersion;
            this.generation = generation;
            this.mediaId = mediaId;
        }
    }

    static final class SourceRef {
        final long jobId;
        final String uri;

        SourceRef(long jobId, String uri) {
            this.jobId = jobId;
            this.uri = uri;
        }
    }
}

package com.aios.mediaintelligence;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** Durable queue and encrypted-at-rest index (credential-encrypted app data). */
final class MediaJobStore extends SQLiteOpenHelper {
    private static final String DATABASE = "media_intelligence.db";
    private static final int VERSION = 1;
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
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        throw new IllegalStateException("explicit media-index migration required");
    }

    void enqueue(CaptureCoalescer.ObservedMedia media, int workClass) {
        ContentValues values = new ContentValues();
        values.put("media_uri", media.uri);
        values.put("generation", media.generation);
        values.put("mime_type", media.mimeType);
        values.put("work_class", workClass);
        values.put("status", STATUS_PENDING);
        values.put("created_at_epoch_ms", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                "jobs", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    PendingJob claimNext(int workClass) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            PendingJob result = null;
            try (Cursor cursor = database.query(
                    "jobs",
                    new String[]{"_id", "media_uri", "generation", "mime_type"},
                    "work_class=? AND status=?",
                    new String[]{Integer.toString(workClass), Integer.toString(STATUS_PENDING)},
                    null,
                    null,
                    "created_at_epoch_ms ASC",
                    "1")) {
                if (cursor.moveToFirst()) {
                    result = new PendingJob(
                            cursor.getLong(0), cursor.getString(1), cursor.getLong(2),
                            cursor.getString(3));
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
            String portableXmp) {
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

        PendingJob(long id, String uri, long generation, String mimeType) {
            this.id = id;
            this.uri = uri;
            this.generation = generation;
            this.mimeType = mimeType;
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
}

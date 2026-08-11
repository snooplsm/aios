package com.aios.mediaintelligence;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.aios.context.ConversationIdentity;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Durable queue and encrypted-at-rest index (credential-encrypted app data). */
final class MediaJobStore extends SQLiteOpenHelper {
    private static final String DATABASE = "media_intelligence.db";
    private static final int VERSION = 8;
    private static final Pattern VOLUME_NAME = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern EXPORT_TOKEN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final int ASSOCIATION_ACTIVE = 0;
    private static final int ASSOCIATION_DELETE_PENDING = 1;
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
                        + "audio_status TEXT NOT NULL,"
                        + "audio_model_id TEXT NOT NULL,"
                        + "audio_model_digest TEXT NOT NULL,"
                        + "audio_language TEXT NOT NULL,"
                        + "FOREIGN KEY(job_id) REFERENCES jobs(_id) ON DELETE CASCADE)");
        database.execSQL(
                "CREATE TABLE own_mutations ("
                        + "media_uri TEXT PRIMARY KEY,"
                        + "generation INTEGER NOT NULL,"
                        + "expires_at_epoch_ms INTEGER NOT NULL)");
        createTimingTable(database);
        createScanStateTable(database);
        createResultDigestTable(database);
        createAssociationTables(database);
        createVideoSubtitleTables(database);
        createVideoExportJournal(database);
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
        if (oldVersion < 4) {
            createResultDigestTable(database);
            database.execSQL(
                    "INSERT INTO result_digests(job_id,content_digest)"
                            + " SELECT job_id,content_digest FROM results");
            createAssociationTables(database);
        }
        if (oldVersion < 5) {
            database.execSQL(
                    "ALTER TABLE association_state ADD COLUMN instance_id TEXT NOT NULL"
                            + " DEFAULT ''");
            database.execSQL(
                    "UPDATE association_state SET instance_id=lower(hex(randomblob(16)))"
                            + " WHERE singleton=1");
        }
        if (oldVersion < 6) {
            database.execSQL(
                    "ALTER TABLE results ADD COLUMN audio_status TEXT NOT NULL"
                            + " DEFAULT 'not_applicable'");
            database.execSQL(
                    "ALTER TABLE results ADD COLUMN audio_model_id TEXT NOT NULL DEFAULT ''");
            database.execSQL(
                    "ALTER TABLE results ADD COLUMN audio_model_digest TEXT NOT NULL DEFAULT ''");
            database.execSQL(
                    "ALTER TABLE results ADD COLUMN audio_language TEXT NOT NULL DEFAULT ''");
            createVideoSubtitleTables(database);
            // Old videos have only the storyboard result. Requeue them once so the same
            // generation gains a complete primary-audio subtitle pass.
            database.execSQL(
                    "DELETE FROM result_digests WHERE job_id IN"
                            + " (SELECT _id FROM jobs WHERE mime_type LIKE 'video/%')");
            database.execSQL(
                    "DELETE FROM timing_samples WHERE job_id IN"
                            + " (SELECT _id FROM jobs WHERE mime_type LIKE 'video/%')");
            database.execSQL(
                    "DELETE FROM results WHERE job_id IN"
                            + " (SELECT _id FROM jobs WHERE mime_type LIKE 'video/%')");
            database.execSQL(
                    "UPDATE jobs SET status=" + STATUS_PENDING
                            + " WHERE mime_type LIKE 'video/%' AND status=" + STATUS_INDEXED);
        }
        if (oldVersion >= 2 && oldVersion < 7) {
            database.execSQL(
                    "ALTER TABLE timing_samples ADD COLUMN video_audio_duration_ms"
                            + " INTEGER NOT NULL DEFAULT -1");
            database.execSQL(
                    "ALTER TABLE timing_samples ADD COLUMN video_audio_pipeline_ms"
                            + " INTEGER NOT NULL DEFAULT -1");
        }
        if (oldVersion < 8) {
            createVideoExportJournal(database);
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
                        + "video_audio_duration_ms INTEGER NOT NULL"
                        + " CHECK(video_audio_duration_ms>=-1),"
                        + "video_audio_pipeline_ms INTEGER NOT NULL"
                        + " CHECK(video_audio_pipeline_ms>=-1),"
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

    private static void createResultDigestTable(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE result_digests ("
                        + "job_id INTEGER NOT NULL,"
                        + "content_digest TEXT NOT NULL,"
                        + "PRIMARY KEY(job_id,content_digest),"
                        + "FOREIGN KEY(job_id) REFERENCES jobs(_id) ON DELETE CASCADE)");
        database.execSQL(
                "CREATE INDEX result_digests_digest ON result_digests(content_digest)");
    }

    private static void createVideoExportJournal(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE video_export_journal ("
                        + "token TEXT PRIMARY KEY,"
                        + "source_uri TEXT NOT NULL,"
                        + "source_generation INTEGER NOT NULL CHECK(source_generation>=0),"
                        + "output_volume TEXT NOT NULL,"
                        + "output_uri TEXT NOT NULL DEFAULT '',"
                        + "created_at_epoch_ms INTEGER NOT NULL CHECK(created_at_epoch_ms>0))");
        database.execSQL(
                "CREATE INDEX video_export_journal_created"
                        + " ON video_export_journal(created_at_epoch_ms)");
    }

    private static void createAssociationTables(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE context_associations ("
                        + "token TEXT PRIMARY KEY,"
                        + "source_id TEXT NOT NULL DEFAULT '',"
                        + "content_digest TEXT NOT NULL DEFAULT '',"
                        + "conversation_key TEXT NOT NULL DEFAULT '',"
                        + "contact_key TEXT NOT NULL DEFAULT '',"
                        + "related_keys TEXT NOT NULL DEFAULT '',"
                        + "event_at_epoch_ms INTEGER NOT NULL DEFAULT 0,"
                        + "created_at_epoch_ms INTEGER NOT NULL,"
                        + "committed INTEGER NOT NULL DEFAULT 0,"
                        + "lifecycle_state INTEGER NOT NULL DEFAULT 0,"
                        + "published_revision INTEGER NOT NULL DEFAULT 0,"
                        + "published_store_instance TEXT NOT NULL DEFAULT '',"
                        + "resolved_job_id INTEGER,"
                        + "resolved_media_uri TEXT NOT NULL DEFAULT '',"
                        + "FOREIGN KEY(resolved_job_id) REFERENCES jobs(_id) ON DELETE SET NULL)");
        database.execSQL(
                "CREATE UNIQUE INDEX context_associations_source"
                        + " ON context_associations(source_id) WHERE source_id<>''");
        database.execSQL(
                "CREATE INDEX context_associations_digest"
                        + " ON context_associations(content_digest,lifecycle_state,committed)");
        database.execSQL(
                "CREATE TABLE association_state ("
                        + "singleton INTEGER PRIMARY KEY CHECK(singleton=1),"
                        + "revision INTEGER NOT NULL,"
                        + "clear_pending INTEGER NOT NULL,"
                        + "instance_id TEXT NOT NULL)");
        database.execSQL(
                "INSERT INTO association_state(singleton,revision,clear_pending,instance_id)"
                        + " VALUES(1,0,0,lower(hex(randomblob(16))))");
    }

    private static void createVideoSubtitleTables(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE video_subtitles ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "job_id INTEGER NOT NULL,"
                        + "sequence INTEGER NOT NULL,"
                        + "language TEXT NOT NULL CHECK(language IN ('en','es')),"
                        + "start_millis INTEGER NOT NULL CHECK(start_millis>=0),"
                        + "end_millis INTEGER NOT NULL CHECK(end_millis>start_millis),"
                        + "text TEXT NOT NULL,"
                        + "confidence REAL NOT NULL CHECK(confidence>=0 AND confidence<=1),"
                        + "UNIQUE(job_id,sequence),"
                        + "FOREIGN KEY(job_id) REFERENCES jobs(_id) ON DELETE CASCADE)");
        database.execSQL(
                "CREATE INDEX video_subtitles_timeline"
                        + " ON video_subtitles(job_id,start_millis,sequence)");
        database.execSQL(
                "CREATE VIRTUAL TABLE video_subtitle_fts"
                        + " USING fts4(text,content='video_subtitles')");
        database.execSQL(
                "CREATE TRIGGER video_subtitles_ai AFTER INSERT ON video_subtitles BEGIN"
                        + " INSERT INTO video_subtitle_fts(docid,text) VALUES(new._id,new.text);"
                        + " END");
        database.execSQL(
                // FTS4 external-content deletion must run while the content row still exists.
                "CREATE TRIGGER video_subtitles_ad BEFORE DELETE ON video_subtitles BEGIN"
                        + " DELETE FROM video_subtitle_fts WHERE docid=old._id;"
                        + " END");
    }

    void enqueue(ObservedMedia media, int workClass) {
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

    boolean deleteMediaUri(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("invalid media URI");
        }
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            int associations = markAssociationsDeletedForUri(database, uri, false);
            database.delete("jobs", "media_uri=?", new String[]{uri});
            database.delete("own_mutations", "media_uri=?", new String[]{uri});
            database.setTransactionSuccessful();
            return associations > 0;
        } finally {
            database.endTransaction();
        }
    }

    boolean purgeVolume(String volumeName) {
        validateVolumeName(volumeName);
        String prefix = "content://media/" + volumeName + "/*";
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            int associations = markAssociationsDeletedForUri(database, prefix, true);
            database.delete("jobs", "media_uri GLOB ?", new String[]{prefix});
            database.delete("own_mutations", "media_uri GLOB ?", new String[]{prefix});
            database.setTransactionSuccessful();
            return associations > 0;
        } finally {
            database.endTransaction();
        }
    }

    void stageMmsPhoto(
            String token,
            String contentDigest,
            ConversationIdentity identity,
            long eventAtEpochMillis,
            long nowEpochMillis) {
        MediaAssociationPolicy.validateToken(token);
        if (contentDigest == null || !DIGEST.matcher(contentDigest).matches()
                || identity == null || nowEpochMillis <= 0L) {
            throw new IllegalArgumentException("invalid staged selected photo");
        }
        MediaAssociationPolicy.validateIdentity(
                identity.conversationKey,
                identity.contactKey,
                identity.relatedConversationKeys);
        if (eventAtEpochMillis <= 0L) {
            throw new IllegalArgumentException("invalid selected-photo event time");
        }
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("content_digest", contentDigest);
            values.put("conversation_key", identity.conversationKey);
            values.put("contact_key", identity.contactKey);
            values.put("related_keys", String.join("\n", identity.relatedConversationKeys));
            int changed = database.update(
                    "context_associations", values, "token=?", new String[]{token});
            if (changed == 0) {
                values.put("token", token);
                values.put("event_at_epoch_ms", eventAtEpochMillis);
                values.put("created_at_epoch_ms", nowEpochMillis);
                if (database.insertOrThrow("context_associations", null, values) < 0L) {
                    throw new IllegalStateException("cannot stage selected photo");
                }
            } else {
                database.execSQL(
                        "UPDATE context_associations SET event_at_epoch_ms="
                                + "MAX(event_at_epoch_ms,?) WHERE token=?",
                        new Object[]{eventAtEpochMillis, token});
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    void completeMmsPhoto(
            String token, String sourceId, long eventAtEpochMillis, long nowEpochMillis) {
        MediaAssociationPolicy.validateToken(token);
        MediaAssociationPolicy.validateSourceId(sourceId);
        if (eventAtEpochMillis <= 0L || nowEpochMillis <= 0L) {
            throw new IllegalArgumentException("invalid completed selected photo");
        }
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            String existingToken = associationTokenForSource(database, sourceId);
            if (existingToken != null && !existingToken.equals(token)) {
                throw new IllegalStateException("MMS source already has a media association");
            }
            ContentValues values = new ContentValues();
            values.put("source_id", sourceId);
            values.put("committed", 1);
            int changed = database.update(
                    "context_associations", values, "token=?", new String[]{token});
            if (changed == 0) {
                values.put("token", token);
                values.put("event_at_epoch_ms", eventAtEpochMillis);
                values.put("created_at_epoch_ms", nowEpochMillis);
                if (database.insertOrThrow("context_associations", null, values) < 0L) {
                    throw new IllegalStateException("cannot complete selected photo");
                }
            } else {
                database.execSQL(
                        "UPDATE context_associations SET event_at_epoch_ms="
                                + "MAX(event_at_epoch_ms,?) WHERE token=?",
                        new Object[]{eventAtEpochMillis, token});
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    void cancelMmsPhoto(String token) {
        MediaAssociationPolicy.validateToken(token);
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            AssociationPublication publication = associationPublication(database, token);
            if (publication == null) {
                database.setTransactionSuccessful();
                return;
            }
            if (publication.publishedRevision > 0L && !publication.sourceId.isEmpty()) {
                ContentValues values = new ContentValues();
                values.put("lifecycle_state", ASSOCIATION_DELETE_PENDING);
                values.put("committed", 0);
                database.update(
                        "context_associations", values, "token=?", new String[]{token});
            } else {
                database.delete("context_associations", "token=?", new String[]{token});
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    void requestDeleteMmsPhoto(String sourceId, long nowEpochMillis) {
        MediaAssociationPolicy.validateSourceId(sourceId);
        if (nowEpochMillis <= 0L) throw new IllegalArgumentException("invalid deletion time");
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            String token = associationTokenForSource(database, sourceId);
            if (token == null) {
                ContentValues values = new ContentValues();
                values.put("token", "delete:" + UUID.randomUUID());
                values.put("source_id", sourceId);
                values.put("created_at_epoch_ms", nowEpochMillis);
                values.put("lifecycle_state", ASSOCIATION_DELETE_PENDING);
                database.insertOrThrow("context_associations", null, values);
            } else {
                ContentValues values = new ContentValues();
                values.put("lifecycle_state", ASSOCIATION_DELETE_PENDING);
                values.put("committed", 0);
                database.update(
                        "context_associations", values, "token=?", new String[]{token});
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    void requestClearMmsPhotos() {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            // Establish a local generation boundary now. Associations staged after this
            // transaction must survive the outstanding remote bulk delete and be republished.
            database.delete("context_associations", null, null);
            ContentValues values = new ContentValues();
            values.put("clear_pending", 1);
            if (database.update("association_state", values, "singleton=1", null) != 1) {
                throw new IllegalStateException("cannot request media-context clear");
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    boolean clearMmsPhotosPending() {
        try (Cursor cursor = getReadableDatabase().query(
                "association_state",
                new String[]{"clear_pending"},
                "singleton=1",
                null,
                null,
                null,
                null)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("association state missing");
            return cursor.getInt(0) != 0;
        }
    }

    String associationInstanceId() {
        try (Cursor cursor = getReadableDatabase().query(
                "association_state",
                new String[]{"instance_id"},
                "singleton=1",
                null,
                null,
                null,
                null)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("association state missing");
            String value = cursor.getString(0);
            if (value == null || !value.matches("[0-9a-f]{32}")) {
                throw new IllegalStateException("association instance is invalid");
            }
            return value;
        }
    }

    List<ReadyAssociation> readyAssociationBatch(String storeInstance, int limit) {
        if (storeInstance == null || !storeInstance.matches("[0-9a-f]{32}")
                || limit < 1 || limit > 128) {
            throw new IllegalArgumentException("invalid media-association page");
        }
        String sql = "SELECT a.token,a.source_id,a.conversation_key,a.contact_key,"
                + "a.related_keys,a.event_at_epoch_ms,j._id,j.media_uri,r.result_json"
                + " FROM context_associations a"
                + " JOIN result_digests d ON d.content_digest=a.content_digest"
                + " JOIN jobs j ON j._id=d.job_id"
                + " JOIN results r ON r.job_id=j._id"
                + " WHERE a.lifecycle_state=? AND a.committed=1"
                + " AND a.source_id<>'' AND a.content_digest<>''"
                + " AND a.conversation_key<>'' AND a.related_keys<>''"
                + " AND j.status=?"
                + " AND (a.published_store_instance<>? OR a.resolved_job_id IS NULL"
                + " OR a.resolved_job_id<>j._id)"
                + " AND 1=(SELECT COUNT(DISTINCT d2.job_id)"
                + " FROM result_digests d2 JOIN jobs j2 ON j2._id=d2.job_id"
                + " WHERE d2.content_digest=a.content_digest AND j2.status=?)"
                + " ORDER BY a.created_at_epoch_ms ASC LIMIT ?";
        List<ReadyAssociation> values = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                sql,
                new String[]{
                        Integer.toString(ASSOCIATION_ACTIVE),
                        Integer.toString(STATUS_INDEXED),
                        storeInstance,
                        Integer.toString(STATUS_INDEXED),
                        Integer.toString(limit)
                })) {
            while (cursor.moveToNext()) {
                values.add(new ReadyAssociation(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getString(4).split("\\n", -1),
                        cursor.getLong(5),
                        cursor.getLong(6),
                        cursor.getString(7),
                        cursor.getString(8)));
            }
        }
        return values;
    }

    void markAssociationPublished(
            String token,
            long jobId,
            String mediaUri,
            long revision,
            String storeInstance) {
        if (token == null || token.isBlank() || jobId <= 0L || mediaUri == null
                || mediaUri.isBlank() || revision <= 0L || storeInstance == null
                || !storeInstance.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("invalid media-association publication");
        }
        ContentValues values = new ContentValues();
        values.put("published_revision", revision);
        values.put("published_store_instance", storeInstance);
        values.put("resolved_job_id", jobId);
        values.put("resolved_media_uri", mediaUri);
        int changed = getWritableDatabase().update(
                "context_associations",
                values,
                "token=? AND lifecycle_state=?",
                new String[]{token, Integer.toString(ASSOCIATION_ACTIVE)});
        if (changed != 1) throw new IllegalStateException("media association changed during publish");
    }

    List<PendingDeletion> associationDeletionBatch(int limit) {
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("invalid deletion page");
        List<PendingDeletion> values = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "context_associations",
                new String[]{"token", "source_id"},
                "lifecycle_state=? AND source_id<>''",
                new String[]{Integer.toString(ASSOCIATION_DELETE_PENDING)},
                null,
                null,
                "created_at_epoch_ms ASC",
                Integer.toString(limit))) {
            while (cursor.moveToNext()) {
                values.add(new PendingDeletion(cursor.getString(0), cursor.getString(1)));
            }
        }
        return values;
    }

    void completeAssociationDeletion(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("invalid token");
        getWritableDatabase().delete(
                "context_associations",
                "token=? AND lifecycle_state=?",
                new String[]{token, Integer.toString(ASSOCIATION_DELETE_PENDING)});
    }

    int expireIncompleteAssociations(long cutoffEpochMillis) {
        if (cutoffEpochMillis <= 0L) throw new IllegalArgumentException("invalid expiry cutoff");
        return getWritableDatabase().delete(
                "context_associations",
                "lifecycle_state=? AND published_revision=0 AND created_at_epoch_ms<?"
                        + " AND (committed=0 OR content_digest='' OR conversation_key='')",
                new String[]{
                        Integer.toString(ASSOCIATION_ACTIVE),
                        Long.toString(cutoffEpochMillis)
                });
    }

    long nextAssociationRevision(long floor) {
        if (floor <= 0L) throw new IllegalArgumentException("invalid revision floor");
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            long current = associationRevision(database);
            if (current == Long.MAX_VALUE) throw new IllegalStateException("revision exhausted");
            long revision = Math.max(current + 1L, floor);
            ContentValues values = new ContentValues();
            values.put("revision", revision);
            if (database.update("association_state", values, "singleton=1", null) != 1) {
                throw new IllegalStateException("cannot persist media-context revision");
            }
            database.setTransactionSuccessful();
            return revision;
        } finally {
            database.endTransaction();
        }
    }

    void completeAssociationClear(long remoteWatermark) {
        if (remoteWatermark <= 0L) throw new IllegalArgumentException("invalid clear watermark");
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("revision", Math.max(associationRevision(database), remoteWatermark));
            values.put("clear_pending", 0);
            if (database.update("association_state", values, "singleton=1", null) != 1) {
                throw new IllegalStateException("cannot complete media-context clear");
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private static long associationRevision(SQLiteDatabase database) {
        try (Cursor cursor = database.query(
                "association_state",
                new String[]{"revision"},
                "singleton=1",
                null,
                null,
                null,
                null)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("association state missing");
            return cursor.getLong(0);
        }
    }

    private static String associationTokenForSource(SQLiteDatabase database, String sourceId) {
        try (Cursor cursor = database.query(
                "context_associations",
                new String[]{"token"},
                "source_id=?",
                new String[]{sourceId},
                null,
                null,
                null,
                "1")) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private static AssociationPublication associationPublication(
            SQLiteDatabase database, String token) {
        try (Cursor cursor = database.query(
                "context_associations",
                new String[]{"source_id", "published_revision"},
                "token=?",
                new String[]{token},
                null,
                null,
                null,
                "1")) {
            return cursor.moveToFirst()
                    ? new AssociationPublication(cursor.getString(0), cursor.getLong(1))
                    : null;
        }
    }

    private static int markAssociationsDeletedForUri(
            SQLiteDatabase database, String uriOrGlob, boolean glob) {
        ContentValues values = new ContentValues();
        values.put("lifecycle_state", ASSOCIATION_DELETE_PENDING);
        values.put("committed", 0);
        return database.update(
                "context_associations",
                values,
                "lifecycle_state=? AND resolved_media_uri " + (glob ? "GLOB" : "=") + " ?",
                new String[]{Integer.toString(ASSOCIATION_ACTIVE), uriOrGlob});
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
            MediaTiming.Sample timing,
            VideoTranscript transcript) {
        if (!MediaTiming.kind(job.mimeType).equals(timing.mediaKind)
                || timing.completedAtEpochMillis != inferredAtEpochMillis
                || transcript == null
                || (MediaInputPolicy.isVideo(job.mimeType)
                && VideoTranscript.STATUS_NOT_APPLICABLE.equals(transcript.status))
                || (MediaInputPolicy.isImage(job.mimeType)
                && !VideoTranscript.STATUS_NOT_APPLICABLE.equals(transcript.status))) {
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
            result.put("audio_status", transcript.status);
            result.put("audio_model_id", transcript.modelId);
            result.put("audio_model_digest", transcript.modelDigest);
            result.put("audio_language", transcript.language);
            long row = database.insertWithOnConflict(
                    "results", null, result, SQLiteDatabase.CONFLICT_REPLACE);
            if (row < 0L) {
                throw new IllegalStateException("cannot store media result");
            }
            database.delete(
                    "result_digests", "job_id=?", new String[]{Long.toString(job.id)});
            insertResultDigest(database, job.id, contentDigest);
            database.delete(
                    "video_subtitles", "job_id=?", new String[]{Long.toString(job.id)});
            for (VideoTranscript.Segment segment : transcript.segments) {
                ContentValues subtitle = new ContentValues();
                subtitle.put("job_id", job.id);
                subtitle.put("sequence", segment.sequence);
                subtitle.put("language", segment.language);
                subtitle.put("start_millis", segment.startMillis);
                subtitle.put("end_millis", segment.endMillis);
                subtitle.put("text", segment.text);
                subtitle.put("confidence", segment.confidence);
                if (database.insertOrThrow("video_subtitles", null, subtitle) < 0L) {
                    throw new IllegalStateException("cannot store video subtitle");
                }
            }
            ContentValues timingValues = new ContentValues();
            timingValues.put("job_id", job.id);
            timingValues.put("media_kind", timing.mediaKind);
            timingValues.put("observed_to_index_ms", timing.observedToIndexMillis);
            timingValues.put("queue_to_start_ms", timing.queueToStartMillis);
            timingValues.put("processing_ms", timing.processingMillis);
            timingValues.put("input_preparation_ms", timing.inputPreparationMillis);
            timingValues.put("model_request_ms", timing.modelRequestMillis);
            timingValues.put("video_audio_duration_ms", timing.videoAudioDurationMillis);
            timingValues.put("video_audio_pipeline_ms", timing.videoAudioPipelineMillis);
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

    VideoEmbeddedMetadata.Data videoExportData(String mediaUri, long generation)
            throws JSONException {
        if (mediaUri == null || mediaUri.isBlank() || generation < 0L) {
            throw new IllegalArgumentException("invalid enhanced-video lookup");
        }
        SQLiteDatabase database = getReadableDatabase();
        database.beginTransaction();
        try {
            long jobId;
            String contentDigest;
            MediaResult result;
            String visionModelId;
            String visionModelDigest;
            long inferredAtEpochMillis;
            String audioStatus;
            String audioModelId;
            String audioModelDigest;
            String audioLanguage;
            try (Cursor cursor = database.rawQuery(
                    "SELECT jobs._id,results.content_digest,results.result_json,"
                            + "results.model_id,results.model_digest,"
                            + "results.inferred_at_epoch_ms,results.audio_status,"
                            + "results.audio_model_id,results.audio_model_digest,"
                            + "results.audio_language"
                            + " FROM jobs JOIN results ON results.job_id=jobs._id"
                            + " WHERE jobs.media_uri=? AND jobs.generation=?"
                            + " AND jobs.status=? AND jobs.mime_type='video/mp4' LIMIT 1",
                    new String[]{
                            mediaUri,
                            Long.toString(generation),
                            Integer.toString(STATUS_INDEXED)
                    })) {
                if (!cursor.moveToFirst()) {
                    database.setTransactionSuccessful();
                    return null;
                }
                jobId = cursor.getLong(0);
                contentDigest = cursor.getString(1);
                result = MediaResult.parse(cursor.getString(2));
                visionModelId = cursor.getString(3);
                visionModelDigest = cursor.getString(4);
                inferredAtEpochMillis = cursor.getLong(5);
                audioStatus = cursor.getString(6);
                audioModelId = cursor.getString(7);
                audioModelDigest = cursor.getString(8);
                audioLanguage = cursor.getString(9);
            }
            List<VideoEmbeddedMetadata.Cue> cues = new ArrayList<>();
            try (Cursor cursor = database.query(
                    "video_subtitles",
                    new String[]{
                            "sequence", "language", "start_millis", "end_millis",
                            "text", "confidence"
                    },
                    "job_id=?",
                    new String[]{Long.toString(jobId)},
                    null,
                    null,
                    "start_millis ASC,sequence ASC")) {
                while (cursor.moveToNext()) {
                    cues.add(new VideoEmbeddedMetadata.Cue(
                            cursor.getInt(0),
                            cursor.getString(1),
                            cursor.getLong(2),
                            cursor.getLong(3),
                            cursor.getString(4),
                            cursor.getFloat(5)));
                }
            }
            VideoEmbeddedMetadata.Data data = new VideoEmbeddedMetadata.Data(
                    generation,
                    contentDigest,
                    result.caption,
                    result.tags,
                    result.language,
                    result.confidence,
                    visionModelId,
                    visionModelDigest,
                    inferredAtEpochMillis,
                    audioStatus,
                    audioModelId,
                    audioModelDigest,
                    audioLanguage,
                    cues);
            database.setTransactionSuccessful();
            return data;
        } finally {
            database.endTransaction();
        }
    }

    private List<MediaTiming.Sample> timingSamples(String mediaKind) {
        List<MediaTiming.Sample> samples = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "timing_samples",
                new String[]{
                        "observed_to_index_ms", "queue_to_start_ms", "processing_ms",
                        "input_preparation_ms", "model_request_ms",
                        "video_audio_duration_ms", "video_audio_pipeline_ms",
                        "completed_at_epoch_ms"
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
                        cursor.getLong(5),
                        cursor.getLong(6),
                        cursor.getLong(7)));
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

    void markPortableWritten(long jobId, String currentContentDigest) {
        if (currentContentDigest == null || !DIGEST.matcher(currentContentDigest).matches()) {
            throw new IllegalArgumentException("invalid portable-media digest");
        }
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            insertResultDigest(database, jobId, currentContentDigest);
            markPortableState(database, jobId, PORTABLE_WRITTEN);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    void markPortableSkipped(long jobId) {
        markPortableState(jobId, PORTABLE_SKIPPED);
    }

    void beginVideoExport(
            String token,
            String sourceUri,
            long sourceGeneration,
            String outputVolume,
            long createdAtEpochMillis) {
        validateExportToken(token);
        validateVolumeName(outputVolume);
        if (sourceUri == null || sourceUri.isBlank() || sourceUri.length() > 2_048
                || sourceGeneration < 0L || createdAtEpochMillis <= 0L) {
            throw new IllegalArgumentException("invalid enhanced-video export journal");
        }
        ContentValues values = new ContentValues();
        values.put("token", token);
        values.put("source_uri", sourceUri);
        values.put("source_generation", sourceGeneration);
        values.put("output_volume", outputVolume);
        values.put("output_uri", "");
        values.put("created_at_epoch_ms", createdAtEpochMillis);
        getWritableDatabase().insertOrThrow("video_export_journal", null, values);
    }

    void attachVideoExportOutput(String token, String outputUri) {
        validateExportToken(token);
        if (outputUri == null || outputUri.isBlank() || outputUri.length() > 2_048) {
            throw new IllegalArgumentException("invalid enhanced-video output URI");
        }
        ContentValues values = new ContentValues();
        values.put("output_uri", outputUri);
        int changed = getWritableDatabase().update(
                "video_export_journal",
                values,
                "token=? AND output_uri=''",
                new String[]{token});
        if (changed != 1) {
            throw new IllegalStateException("enhanced-video output cannot be attached");
        }
    }

    List<PendingVideoExport> pendingVideoExports(int limit) {
        if (limit <= 0 || limit > 128) {
            throw new IllegalArgumentException("invalid enhanced-video recovery limit");
        }
        List<PendingVideoExport> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "video_export_journal",
                new String[]{
                        "token", "source_uri", "source_generation", "output_volume",
                        "output_uri", "created_at_epoch_ms"
                },
                null,
                null,
                null,
                null,
                "created_at_epoch_ms ASC,token ASC",
                Integer.toString(limit))) {
            while (cursor.moveToNext()) {
                result.add(new PendingVideoExport(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getLong(2),
                        cursor.getString(3),
                        cursor.getString(4),
                        cursor.getLong(5)));
            }
        }
        return result;
    }

    PendingVideoExport pendingVideoExport(String token) {
        validateExportToken(token);
        try (Cursor cursor = getReadableDatabase().query(
                "video_export_journal",
                new String[]{
                        "token", "source_uri", "source_generation", "output_volume",
                        "output_uri", "created_at_epoch_ms"
                },
                "token=?",
                new String[]{token},
                null,
                null,
                null)) {
            if (!cursor.moveToFirst()) return null;
            return new PendingVideoExport(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getLong(2),
                    cursor.getString(3),
                    cursor.getString(4),
                    cursor.getLong(5));
        }
    }

    void clearVideoExportJournal(String token) {
        validateExportToken(token);
        getWritableDatabase().delete(
                "video_export_journal", "token=?", new String[]{token});
    }

    void clearFailedVideoExport(String token, String outputUri) {
        validateExportToken(token);
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete("video_export_journal", "token=?", new String[]{token});
            if (outputUri != null && !outputUri.isBlank()) {
                database.delete(
                        "own_mutations", "media_uri=?", new String[]{outputUri});
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private static void validateExportToken(String token) {
        if (token == null || !EXPORT_TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("invalid enhanced-video export token");
        }
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
        markPortableState(getWritableDatabase(), jobId, state);
    }

    private void markPortableState(SQLiteDatabase database, long jobId, int state) {
        ContentValues values = new ContentValues();
        values.put("portable_metadata_written", state);
        int changed = database.update(
                "results",
                values,
                "job_id=? AND portable_metadata_written=?",
                new String[]{
                        Long.toString(jobId), Integer.toString(PORTABLE_PENDING)
                });
        if (changed == 1) {
            return;
        }
        try (Cursor cursor = database.query(
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

    private static void insertResultDigest(
            SQLiteDatabase database, long jobId, String contentDigest) {
        if (jobId <= 0L || contentDigest == null || !DIGEST.matcher(contentDigest).matches()) {
            throw new IllegalArgumentException("invalid media-result digest");
        }
        ContentValues values = new ContentValues();
        values.put("job_id", jobId);
        values.put("content_digest", contentDigest);
        long row = database.insertWithOnConflict(
                "result_digests", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (row < 0L) {
            try (Cursor cursor = database.query(
                    "result_digests",
                    new String[]{"1"},
                    "job_id=? AND content_digest=?",
                    new String[]{Long.toString(jobId), contentDigest},
                    null,
                    null,
                    null,
                    "1")) {
                if (!cursor.moveToFirst()) {
                    throw new IllegalStateException("cannot store media-result digest");
                }
            }
        }
    }

    private void markStatus(long jobId, int status) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        getWritableDatabase().update(
                "jobs", values, "_id=?", new String[]{Long.toString(jobId)});
    }

    static final class PendingVideoExport {
        final String token;
        final String sourceUri;
        final long sourceGeneration;
        final String outputVolume;
        final String outputUri;
        final long createdAtEpochMillis;

        PendingVideoExport(
                String token,
                String sourceUri,
                long sourceGeneration,
                String outputVolume,
                String outputUri,
                long createdAtEpochMillis) {
            this.token = token;
            this.sourceUri = sourceUri;
            this.sourceGeneration = sourceGeneration;
            this.outputVolume = outputVolume;
            this.outputUri = outputUri;
            this.createdAtEpochMillis = createdAtEpochMillis;
        }
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

    static final class ReadyAssociation {
        final String token;
        final String sourceId;
        final String conversationKey;
        final String contactKey;
        final String[] relatedKeys;
        final long eventAtEpochMillis;
        final long jobId;
        final String mediaUri;
        final String resultJson;

        ReadyAssociation(
                String token,
                String sourceId,
                String conversationKey,
                String contactKey,
                String[] relatedKeys,
                long eventAtEpochMillis,
                long jobId,
                String mediaUri,
                String resultJson) {
            this.token = token;
            this.sourceId = sourceId;
            this.conversationKey = conversationKey;
            this.contactKey = contactKey;
            this.relatedKeys = relatedKeys.clone();
            this.eventAtEpochMillis = eventAtEpochMillis;
            this.jobId = jobId;
            this.mediaUri = mediaUri;
            this.resultJson = resultJson;
        }
    }

    static final class PendingDeletion {
        final String token;
        final String sourceId;

        PendingDeletion(String token, String sourceId) {
            this.token = token;
            this.sourceId = sourceId;
        }
    }

    private static final class AssociationPublication {
        final String sourceId;
        final long publishedRevision;

        AssociationPublication(String sourceId, long publishedRevision) {
            this.sourceId = sourceId;
            this.publishedRevision = publishedRevision;
        }
    }
}

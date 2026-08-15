package com.aios.contextintelligence;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.aios.context.ContextDocument;
import com.aios.context.ContextSnippet;
import com.aios.context.ConversationIdentity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Credential-encrypted, revisioned hybrid index for bounded local retrieval. */
final class ContextStore extends SQLiteOpenHelper {
    private static final String DATABASE = "communication_context.db";
    private static final int VERSION = 6;
    private static final int MAX_EMBEDDING_BATCH = 16;

    private final Context context;

    ContextStore(Context context) {
        super(context.getApplicationContext(), DATABASE, null, VERSION);
        this.context = context.getApplicationContext();
    }

    @Override
    public void onConfigure(SQLiteDatabase database) {
        database.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE entries ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "source_type TEXT NOT NULL,"
                        + "source_id TEXT NOT NULL,"
                        + "revision INTEGER NOT NULL,"
                        + "conversation_key TEXT NOT NULL,"
                        + "contact_key TEXT NOT NULL,"
                        + "event_at_epoch_ms INTEGER NOT NULL,"
                        + "expires_at_epoch_ms INTEGER NOT NULL,"
                        + "expiry_boot_identity TEXT NOT NULL,"
                        + "created_at_elapsed_ms INTEGER NOT NULL,"
                        + "expires_at_elapsed_ms INTEGER NOT NULL,"
                        + "body TEXT NOT NULL,"
                        + "UNIQUE(source_type, source_id))");
        database.execSQL(
                "CREATE INDEX entries_conversation_time"
                        + " ON entries(conversation_key, event_at_epoch_ms DESC)");
        database.execSQL(
                "CREATE INDEX entries_contact_time"
                        + " ON entries(contact_key, event_at_epoch_ms DESC)");
        database.execSQL(
                "CREATE TABLE tombstones ("
                        + "source_type TEXT NOT NULL,"
                        + "source_id TEXT NOT NULL,"
                        + "revision INTEGER NOT NULL,"
                        + "PRIMARY KEY(source_type, source_id))");
        createSourceDeleteWatermarks(database);
        database.execSQL("CREATE VIRTUAL TABLE entries_fts USING fts4(body, content='entries')");
        database.execSQL(
                "CREATE TRIGGER entries_after_insert AFTER INSERT ON entries BEGIN "
                        + "INSERT INTO entries_fts(docid, body) VALUES(new._id, new.body); END");
        database.execSQL(
                "CREATE TRIGGER entries_before_delete BEFORE DELETE ON entries BEGIN "
                        + "DELETE FROM entries_fts WHERE docid=old._id; END");
        createEmbeddingTable(database);
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        if (oldVersion < 1 || oldVersion > newVersion || newVersion != VERSION) {
            throw new IllegalStateException("explicit communication-index migration required");
        }
        if (oldVersion < 2) {
            createSourceDeleteWatermarks(database);
            migrateTombstonesToWatermark(database, ContextPolicy.CALL_EVENT);
        }
        if (oldVersion < 3) {
            migrateTombstonesToWatermark(database, ContextPolicy.SMS);
            migrateTombstonesToWatermark(database, ContextPolicy.MMS);
        }
        if (oldVersion < 4) {
            migrateTombstonesToWatermark(database, ContextPolicy.MEDIA_METADATA);
        }
        if (oldVersion < 5) {
            // Existing expiring rows cannot prove a monotonic deadline or boot
            // identity. Empty/zero migration values make them fail closed on
            // the first query, service start, boot sweep, or expiry alarm.
            database.execSQL(
                    "ALTER TABLE entries ADD COLUMN expiry_boot_identity"
                            + " TEXT NOT NULL DEFAULT ''");
            database.execSQL(
                    "ALTER TABLE entries ADD COLUMN created_at_elapsed_ms"
                            + " INTEGER NOT NULL DEFAULT 0");
            database.execSQL(
                    "ALTER TABLE entries ADD COLUMN expires_at_elapsed_ms"
                            + " INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 6) {
            createEmbeddingTable(database);
        }
    }

    void upsert(ContextDocument document) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            long current = revision(database, "entries", document.sourceType, document.sourceId);
            long tombstone = revision(
                    database, "tombstones", document.sourceType, document.sourceId);
            long deleteWatermark = sourceDeleteWatermark(database, document.sourceType);
            if (!RevisionGate.accepts(
                    document.revision, current, tombstone, deleteWatermark)) {
                database.setTransactionSuccessful();
                return;
            }
            database.delete(
                    "entries", "source_type=? AND source_id=?",
                    new String[]{document.sourceType, document.sourceId});
            ContentValues values = new ContentValues();
            values.put("source_type", document.sourceType);
            values.put("source_id", document.sourceId);
            values.put("revision", document.revision);
            values.put("conversation_key", document.identity.conversationKey);
            values.put("contact_key", document.identity.contactKey);
            values.put("event_at_epoch_ms", document.eventAtEpochMillis);
            values.put("expires_at_epoch_ms", document.expiresAtEpochMillis);
            values.put("expiry_boot_identity", document.expiryBootIdentity);
            values.put(
                    "created_at_elapsed_ms",
                    document.createdAtElapsedRealtimeMillis);
            values.put(
                    "expires_at_elapsed_ms",
                    document.expiresAtElapsedRealtimeMillis);
            values.put("body", document.text);
            if (database.insertOrThrow("entries", null, values) < 0L) {
                throw new IllegalStateException("cannot store communication context");
            }
            database.delete(
                    "tombstones", "source_type=? AND source_id=?",
                    new String[]{document.sourceType, document.sourceId});
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    void deleteSource(String sourceType, String sourceId, long revision) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            long current = revision(database, "entries", sourceType, sourceId);
            long tombstone = revision(database, "tombstones", sourceType, sourceId);
            long deleteWatermark = sourceDeleteWatermark(database, sourceType);
            if (revision <= tombstone || revision <= deleteWatermark) {
                database.setTransactionSuccessful();
                return;
            }
            if (current <= revision) {
                database.delete(
                        "entries", "source_type=? AND source_id=?",
                        new String[]{sourceType, sourceId});
            }
            ContentValues values = new ContentValues();
            values.put("source_type", sourceType);
            values.put("revision", revision);
            if (usesSourceDeleteWatermark(sourceType)) {
                database.insertWithOnConflict(
                        "source_delete_watermarks",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE);
                database.delete("tombstones", "source_type=?", new String[]{sourceType});
            } else {
                values.put("source_id", sourceId);
                database.insertWithOnConflict(
                        "tombstones", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    long deleteSourceType(String sourceType, long revision) {
        if (!usesSourceDeleteWatermark(sourceType)) {
            throw new IllegalArgumentException("source does not support bulk deletion");
        }
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            long effectiveRevision = Math.max(
                    revision, highestSourceRevision(database, sourceType));
            database.delete(
                    "entries",
                    "source_type=?",
                    new String[]{sourceType});
            ContentValues values = new ContentValues();
            values.put("source_type", sourceType);
            values.put("revision", effectiveRevision);
            database.insertWithOnConflict(
                    "source_delete_watermarks",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE);
            database.delete("tombstones", "source_type=?", new String[]{sourceType});
            database.setTransactionSuccessful();
            return effectiveRevision;
        } finally {
            database.endTransaction();
        }
    }

    List<ContextSnippet> query(
            ConversationIdentity identity,
            String[] sourceTypes,
            String query,
            int limit,
            long nowEpochMillis) {
        purgeExpired(nowEpochMillis);
        StringBuilder identityClause = new StringBuilder("e.conversation_key IN (");
        List<String> arguments = new ArrayList<>();
        for (String key : identity.relatedConversationKeys) {
            if (!arguments.isEmpty()) identityClause.append(',');
            identityClause.append('?');
            arguments.add(key);
        }
        identityClause.append(')');
        String sourceClause = ContextSourceScope.selectionClause(sourceTypes, arguments);
        String fts = ContextText.ftsQuery(query);
        String sql;
        if (fts.isEmpty()) {
            sql = "SELECT e.source_type,e.source_id,e.revision,e.event_at_epoch_ms,e.body"
                    + " FROM entries e WHERE " + identityClause
                    + sourceClause
                    + " AND (e.expires_at_epoch_ms=0 OR e.expires_at_epoch_ms>?)"
                    + " ORDER BY e.event_at_epoch_ms DESC LIMIT ?";
        } else {
            sql = "SELECT e.source_type,e.source_id,e.revision,e.event_at_epoch_ms,e.body"
                    + " FROM entries e JOIN entries_fts ON entries_fts.docid=e._id WHERE "
                    + identityClause
                    + sourceClause
                    + " AND (e.expires_at_epoch_ms=0 OR e.expires_at_epoch_ms>?)"
                    + " AND entries_fts MATCH ? ORDER BY e.event_at_epoch_ms DESC LIMIT ?";
        }
        arguments.add(Long.toString(nowEpochMillis));
        if (!fts.isEmpty()) arguments.add(fts);
        arguments.add(Integer.toString(limit));
        List<ContextSnippet> results = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                sql, arguments.toArray(new String[0]))) {
            while (cursor.moveToNext()) {
                results.add(new ContextSnippet(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getLong(2),
                        cursor.getLong(3),
                        ContextText.excerpt(cursor.getString(4))));
            }
        }
        return results;
    }

    /**
     * Reranks an identity/source/expiry-bounded recent pool with one exact model bundle.
     * Corrupt or missing stored vectors degrade per-row to lexical/recency scoring.
     */
    List<ContextSnippet> queryHybrid(
            ConversationIdentity identity,
            String[] sourceTypes,
            String query,
            int limit,
            long nowEpochMillis,
            String modelId,
            String modelBundleSha256,
            float[] queryEmbedding) {
        EmbeddingModelIdentity.validate(modelId, modelBundleSha256);
        QuantizedEmbedding.quantize(queryEmbedding);
        purgeExpired(nowEpochMillis);
        StringBuilder identityClause = new StringBuilder("e.conversation_key IN (");
        List<String> baseArguments = new ArrayList<>();
        baseArguments.add(modelId);
        baseArguments.add(modelBundleSha256);
        boolean firstIdentity = true;
        for (String key : identity.relatedConversationKeys) {
            if (!firstIdentity) identityClause.append(',');
            identityClause.append('?');
            baseArguments.add(key);
            firstIdentity = false;
        }
        identityClause.append(')');
        String sourceClause = ContextSourceScope.selectionClause(sourceTypes, baseArguments);
        String columns = "SELECT e.source_type,e.source_id,e.revision,e.event_at_epoch_ms,e.body,"
                + "x.quantization_scale,x.vector_norm,x.vector"
                + " FROM entries e";
        String embeddingJoin = " LEFT JOIN entry_embeddings x"
                + " ON x.entry_id=e._id AND x.model_id=? AND x.model_bundle_sha256=?"
                + " WHERE " + identityClause + sourceClause
                + " AND (e.expires_at_epoch_ms=0 OR e.expires_at_epoch_ms>?)";
        SQLiteDatabase database = getReadableDatabase();
        Map<String, HybridRetrievalRanker.Candidate> candidateMap = new LinkedHashMap<>();
        String fts = ContextText.ftsQuery(query);
        if (!fts.isEmpty()) {
            List<String> lexicalArguments = new ArrayList<>(baseArguments);
            lexicalArguments.add(Long.toString(nowEpochMillis));
            lexicalArguments.add(fts);
            lexicalArguments.add("128");
            try (Cursor cursor = database.rawQuery(
                    columns + " JOIN entries_fts ON entries_fts.docid=e._id"
                            + embeddingJoin
                            + " AND entries_fts MATCH ?"
                            + " ORDER BY e.event_at_epoch_ms DESC LIMIT ?",
                    lexicalArguments.toArray(new String[0]))) {
                addHybridCandidates(cursor, query, candidateMap);
            }
        }
        List<String> recentArguments = new ArrayList<>(baseArguments);
        recentArguments.add(Long.toString(nowEpochMillis));
        recentArguments.add(Integer.toString(HybridRetrievalRanker.MAX_CANDIDATES));
        try (Cursor cursor = database.rawQuery(
                columns + embeddingJoin
                        + " ORDER BY e.event_at_epoch_ms DESC LIMIT ?",
                recentArguments.toArray(new String[0]))) {
            addHybridCandidates(cursor, query, candidateMap);
        }
        List<HybridRetrievalRanker.Candidate> candidates = new ArrayList<>(
                candidateMap.values());
        List<HybridRetrievalRanker.Candidate> ranked = HybridRetrievalRanker.rank(
                candidates, queryEmbedding, limit, nowEpochMillis);
        List<ContextSnippet> results = new ArrayList<>(ranked.size());
        for (HybridRetrievalRanker.Candidate candidate : ranked) {
            results.add(new ContextSnippet(
                    candidate.sourceType,
                    candidate.sourceId,
                    candidate.revision,
                    candidate.eventAtEpochMillis,
                    ContextText.excerpt(candidate.text)));
        }
        return results;
    }

    private static void addHybridCandidates(
            Cursor cursor,
            String query,
            Map<String, HybridRetrievalRanker.Candidate> candidates) {
        while (cursor.moveToNext()
                && candidates.size() < HybridRetrievalRanker.MAX_CANDIDATES) {
            String sourceType = cursor.getString(0);
            String sourceId = cursor.getString(1);
            String key = sourceType + '\u0000' + sourceId;
            if (candidates.containsKey(key)) continue;
            QuantizedEmbedding embedding = null;
            if (!cursor.isNull(5) && !cursor.isNull(6) && !cursor.isNull(7)) {
                try {
                    embedding = QuantizedEmbedding.restore(
                            cursor.getBlob(7), cursor.getFloat(5), cursor.getFloat(6));
                } catch (IllegalArgumentException ignored) {
                    // A malformed row cannot disable lexical retrieval.
                }
            }
            String text = cursor.getString(4);
            candidates.put(key, new HybridRetrievalRanker.Candidate(
                    sourceType,
                    sourceId,
                    cursor.getLong(2),
                    cursor.getLong(3),
                    text,
                    ContextText.lexicalRank(text, query),
                    embedding));
        }
    }

    /**
     * Returns only current, unexpired revisions missing the exact selected model artifact.
     * The caller must run this off the Binder thread and submit at most one bounded batch.
     */
    List<EmbeddingWorkItem> pendingEmbeddings(
            String modelId,
            String modelBundleSha256,
            int limit,
            long nowEpochMillis) {
        EmbeddingModelIdentity.validate(modelId, modelBundleSha256);
        if (limit < 1 || limit > MAX_EMBEDDING_BATCH || nowEpochMillis <= 0L) {
            throw new IllegalArgumentException("invalid embedding work request");
        }
        purgeExpired(nowEpochMillis);
        List<EmbeddingWorkItem> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT e.source_type,e.source_id,e.revision,e.body"
                        + " FROM entries e LEFT JOIN entry_embeddings x"
                        + " ON x.entry_id=e._id"
                        + " AND x.model_id=? AND x.model_bundle_sha256=?"
                        + " WHERE x.entry_id IS NULL"
                        + " AND (e.expires_at_epoch_ms=0 OR e.expires_at_epoch_ms>?)"
                        + " ORDER BY e.event_at_epoch_ms DESC LIMIT ?",
                new String[]{
                        modelId,
                        modelBundleSha256,
                        Long.toString(nowEpochMillis),
                        Integer.toString(limit)})) {
            while (cursor.moveToNext()) {
                result.add(new EmbeddingWorkItem(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getLong(2),
                        cursor.getString(3)));
            }
        }
        return result;
    }

    /** Commits only if the source still exists at the revision that was embedded. */
    boolean commitEmbedding(
            String sourceType,
            String sourceId,
            long revision,
            String modelId,
            String modelBundleSha256,
            QuantizedEmbedding embedding,
            long embeddedAtEpochMillis) {
        EmbeddingModelIdentity.validate(modelId, modelBundleSha256);
        if (sourceType == null || sourceType.isBlank()
                || sourceId == null || sourceId.isBlank()
                || revision <= 0L || embedding == null || embeddedAtEpochMillis <= 0L) {
            throw new IllegalArgumentException("invalid embedding commit");
        }
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            long entryId;
            long currentRevision;
            long expiresAtEpochMillis;
            try (Cursor cursor = database.query(
                    "entries",
                    new String[]{"_id", "revision", "expires_at_epoch_ms"},
                    "source_type=? AND source_id=?",
                    new String[]{sourceType, sourceId},
                    null,
                    null,
                    null)) {
                if (!cursor.moveToFirst()) {
                    database.setTransactionSuccessful();
                    return false;
                }
                entryId = cursor.getLong(0);
                currentRevision = cursor.getLong(1);
                expiresAtEpochMillis = cursor.getLong(2);
            }
            if (currentRevision != revision
                    || (expiresAtEpochMillis > 0L
                    && expiresAtEpochMillis <= embeddedAtEpochMillis)) {
                database.setTransactionSuccessful();
                return false;
            }
            ContentValues values = new ContentValues();
            values.put("entry_id", entryId);
            values.put("entry_revision", revision);
            values.put("model_id", modelId);
            values.put("model_bundle_sha256", modelBundleSha256);
            values.put("dimensions", QuantizedEmbedding.DIMENSIONS);
            values.put("quantization_scale", embedding.scale());
            values.put("vector_norm", embedding.norm());
            values.put("vector", embedding.values());
            values.put("embedded_at_epoch_ms", embeddedAtEpochMillis);
            if (database.insertWithOnConflict(
                    "entry_embeddings",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE) < 0L) {
                throw new IllegalStateException("cannot store communication embedding");
            }
            database.setTransactionSuccessful();
            return true;
        } finally {
            database.endTransaction();
        }
    }

    void purgeExpired(long nowEpochMillis) {
        ContextRetentionClock.Snapshot now = ContextRetentionClock.capture(context, nowEpochMillis);
        if (now.epochMillis <= 0L || now.elapsedRealtimeMillis < 0L
                || now.bootIdentity == null || now.bootIdentity.isBlank()) {
            throw new IllegalArgumentException("invalid purge time");
        }
        getWritableDatabase().delete(
                "entries",
                "expires_at_epoch_ms>0 AND (expires_at_epoch_ms<=?"
                        // rawQuery binds selection arguments as text. Arithmetic expressions
                        // have no column affinity, so cast the exact TTL before comparison.
                        + " OR expires_at_epoch_ms-event_at_epoch_ms<>CAST(? AS INTEGER)"
                        + " OR expiry_boot_identity<>?"
                        + " OR created_at_elapsed_ms<0"
                        + " OR created_at_elapsed_ms>?"
                        + " OR expires_at_elapsed_ms<=0"
                        + " OR expires_at_elapsed_ms-created_at_elapsed_ms<>CAST(? AS INTEGER)"
                        + " OR expires_at_elapsed_ms<=?)",
                new String[]{
                        Long.toString(now.epochMillis),
                        Long.toString(ContextPolicy.CALL_ARTIFACT_TTL_MILLIS),
                        now.bootIdentity,
                        Long.toString(now.elapsedRealtimeMillis),
                        Long.toString(ContextPolicy.CALL_ARTIFACT_TTL_MILLIS),
                        Long.toString(now.elapsedRealtimeMillis)});
    }

    long nextExpiryElapsedRealtimeMillis() {
        ContextRetentionClock.Snapshot now = ContextRetentionClock.capture(
                context, System.currentTimeMillis());
        purgeExpired(now.epochMillis);
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT MIN(expires_at_elapsed_ms) FROM entries"
                        + " WHERE expires_at_epoch_ms>0",
                null)) {
            if (!cursor.moveToFirst() || cursor.isNull(0)) return Long.MAX_VALUE;
            return Math.max(now.elapsedRealtimeMillis, cursor.getLong(0));
        }
    }

    private static long revision(
            SQLiteDatabase database, String table, String sourceType, String sourceId) {
        try (Cursor cursor = database.query(
                table,
                new String[]{"revision"},
                "source_type=? AND source_id=?",
                new String[]{sourceType, sourceId},
                null,
                null,
                null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    private static long sourceDeleteWatermark(SQLiteDatabase database, String sourceType) {
        if (!usesSourceDeleteWatermark(sourceType)) return 0L;
        try (Cursor cursor = database.query(
                "source_delete_watermarks",
                new String[]{"revision"},
                "source_type=?",
                new String[]{sourceType},
                null,
                null,
                null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    private static long highestSourceRevision(SQLiteDatabase database, String sourceType) {
        long result = sourceDeleteWatermark(database, sourceType);
        for (String table : new String[]{"entries", "tombstones"}) {
            try (Cursor cursor = database.rawQuery(
                    "SELECT MAX(revision) FROM " + table + " WHERE source_type=?",
                    new String[]{sourceType})) {
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    result = Math.max(result, cursor.getLong(0));
                }
            }
        }
        return result;
    }

    private static boolean usesSourceDeleteWatermark(String sourceType) {
        return ContextPolicy.CALL_EVENT.equals(sourceType)
                || ContextPolicy.SMS.equals(sourceType)
                || ContextPolicy.MMS.equals(sourceType)
                || ContextPolicy.MEDIA_METADATA.equals(sourceType);
    }

    private static void createSourceDeleteWatermarks(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE source_delete_watermarks ("
                        + "source_type TEXT PRIMARY KEY,"
                        + "revision INTEGER NOT NULL)");
    }

    private static void createEmbeddingTable(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE entry_embeddings ("
                        + "entry_id INTEGER PRIMARY KEY,"
                        + "entry_revision INTEGER NOT NULL,"
                        + "model_id TEXT NOT NULL,"
                        + "model_bundle_sha256 TEXT NOT NULL,"
                        + "dimensions INTEGER NOT NULL CHECK(dimensions=256),"
                        + "quantization_scale REAL NOT NULL CHECK(quantization_scale>0),"
                        + "vector_norm REAL NOT NULL CHECK(vector_norm>0),"
                        + "vector BLOB NOT NULL CHECK(length(vector)=256),"
                        + "embedded_at_epoch_ms INTEGER NOT NULL,"
                        + "FOREIGN KEY(entry_id) REFERENCES entries(_id) ON DELETE CASCADE)");
        database.execSQL(
                "CREATE INDEX entry_embeddings_model"
                        + " ON entry_embeddings(model_id, model_bundle_sha256)");
    }

    private static void migrateTombstonesToWatermark(
            SQLiteDatabase database, String sourceType) {
        long revision = sourceDeleteWatermark(database, sourceType);
        try (Cursor cursor = database.rawQuery(
                "SELECT MAX(revision) FROM tombstones WHERE source_type=?",
                new String[]{sourceType})) {
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                revision = Math.max(revision, cursor.getLong(0));
            }
        }
        if (revision > 0L) {
            ContentValues values = new ContentValues();
            values.put("source_type", sourceType);
            values.put("revision", revision);
            database.insertWithOnConflict(
                    "source_delete_watermarks",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE);
        }
        database.delete("tombstones", "source_type=?", new String[]{sourceType});
    }

}

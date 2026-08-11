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
import java.util.List;
import java.util.Locale;

/** Credential-encrypted, revisioned lexical index for bounded local retrieval. */
final class ContextStore extends SQLiteOpenHelper {
    private static final String DATABASE = "communication_context.db";
    private static final int VERSION = 5;

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
        String fts = ftsQuery(query);
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
                        excerpt(cursor.getString(4))));
            }
        }
        return results;
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

    static String ftsQuery(String value) {
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        if (normalized.isEmpty()) return "";
        String[] tokens = normalized.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < Math.min(tokens.length, 8); index++) {
            if (tokens[index].isEmpty()) continue;
            // Android's FTS4 build uses the basic syntax, where whitespace is
            // intersection. Treating AND as an operator instead searches for
            // a literal "and" token on affected platform builds.
            if (result.length() > 0) result.append(' ');
            result.append('"').append(tokens[index]).append('"').append('*');
        }
        return result.toString();
    }

    static String excerpt(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= ContextPolicy.MAX_SNIPPET_CHARS
                ? normalized : normalized.substring(0, ContextPolicy.MAX_SNIPPET_CHARS);
    }
}

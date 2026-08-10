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
    private static final int VERSION = 2;

    ContextStore(Context context) {
        super(context, DATABASE, null, VERSION);
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
        if (oldVersion == 1 && newVersion == 2) {
            createSourceDeleteWatermarks(database);
            database.execSQL(
                    "INSERT INTO source_delete_watermarks(source_type, revision) "
                            + "SELECT source_type, MAX(revision) FROM tombstones "
                            + "WHERE source_type=? GROUP BY source_type",
                    new Object[]{ContextPolicy.CALL_EVENT});
            database.delete(
                    "tombstones", "source_type=?", new String[]{ContextPolicy.CALL_EVENT});
            return;
        }
        throw new IllegalStateException("explicit communication-index migration required");
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

    List<ContextSnippet> query(
            ConversationIdentity identity, String query, int limit, long nowEpochMillis) {
        purgeExpired(nowEpochMillis);
        StringBuilder identityClause = new StringBuilder("e.conversation_key IN (");
        List<String> arguments = new ArrayList<>();
        for (String key : identity.relatedConversationKeys) {
            if (!arguments.isEmpty()) identityClause.append(',');
            identityClause.append('?');
            arguments.add(key);
        }
        identityClause.append(')');
        String fts = ftsQuery(query);
        String sql;
        if (fts.isEmpty()) {
            sql = "SELECT e.source_type,e.source_id,e.revision,e.event_at_epoch_ms,e.body"
                    + " FROM entries e WHERE " + identityClause
                    + " AND (e.expires_at_epoch_ms=0 OR e.expires_at_epoch_ms>?)"
                    + " ORDER BY e.event_at_epoch_ms DESC LIMIT ?";
        } else {
            sql = "SELECT e.source_type,e.source_id,e.revision,e.event_at_epoch_ms,e.body"
                    + " FROM entries e JOIN entries_fts ON entries_fts.docid=e._id WHERE "
                    + identityClause
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
        if (nowEpochMillis <= 0L) throw new IllegalArgumentException("invalid purge time");
        getWritableDatabase().delete(
                "entries", "expires_at_epoch_ms>0 AND expires_at_epoch_ms<=?",
                new String[]{Long.toString(nowEpochMillis)});
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

    private static boolean usesSourceDeleteWatermark(String sourceType) {
        return ContextPolicy.CALL_EVENT.equals(sourceType);
    }

    private static void createSourceDeleteWatermarks(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE source_delete_watermarks ("
                        + "source_type TEXT PRIMARY KEY,"
                        + "revision INTEGER NOT NULL)");
    }

    static String ftsQuery(String value) {
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        if (normalized.isEmpty()) return "";
        String[] tokens = normalized.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < Math.min(tokens.length, 8); index++) {
            if (tokens[index].isEmpty()) continue;
            if (result.length() > 0) result.append(" AND ");
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

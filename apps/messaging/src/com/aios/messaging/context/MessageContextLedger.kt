package com.aios.messaging.context

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.math.max

/** Small durable mirror of context documents published from the Telephony provider. */
internal class MessageContextLedger(context: Context) :
    SQLiteOpenHelper(context, DATABASE, null, VERSION) {

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE ledger (" +
                "source_type TEXT NOT NULL," +
                "source_id TEXT NOT NULL," +
                "fingerprint TEXT NOT NULL," +
                "last_seen_epoch INTEGER NOT NULL," +
                "PRIMARY KEY(source_type, source_id))",
        )
        database.execSQL(
            "CREATE INDEX ledger_seen ON ledger(last_seen_epoch, source_type, source_id)",
        )
        database.execSQL(
            "CREATE TABLE clocks (name TEXT PRIMARY KEY, value INTEGER NOT NULL)",
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("explicit message-context ledger migration required")
    }

    @Synchronized
    fun beginSweep(): Long = nextClock(SWEEP_CLOCK, 1L)

    @Synchronized
    fun nextRevision(candidateEpochMillis: Long): Long =
        nextClock(REVISION_CLOCK, candidateEpochMillis.coerceAtLeast(1L))

    @Synchronized
    fun find(sourceType: String, sourceId: String): Entry? {
        validateSource(sourceType, sourceId)
        readableDatabase.query(
            "ledger",
            arrayOf("fingerprint", "last_seen_epoch"),
            "source_type=? AND source_id=?",
            arrayOf(sourceType, sourceId),
            null,
            null,
            null,
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                Entry(sourceType, sourceId, cursor.getString(0), cursor.getLong(1))
            } else {
                null
            }
        }
    }

    @Synchronized
    fun recordSeen(
        sourceType: String,
        sourceId: String,
        fingerprint: String,
        sweepEpoch: Long,
    ) {
        validateSource(sourceType, sourceId)
        require(FINGERPRINT.matches(fingerprint) && sweepEpoch > 0L) {
            "invalid message-context ledger entry"
        }
        val values = ContentValues().apply {
            put("source_type", sourceType)
            put("source_id", sourceId)
            put("fingerprint", fingerprint)
            put("last_seen_epoch", sweepEpoch)
        }
        check(writableDatabase.insertWithOnConflict(
            "ledger",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        ) >= 0L) { "cannot persist message-context ledger entry" }
    }

    @Synchronized
    fun markSeen(sourceType: String, sourceId: String, sweepEpoch: Long) {
        validateSource(sourceType, sourceId)
        require(sweepEpoch > 0L) { "invalid message-context sweep" }
        val values = ContentValues().apply { put("last_seen_epoch", sweepEpoch) }
        check(writableDatabase.update(
            "ledger",
            values,
            "source_type=? AND source_id=?",
            arrayOf(sourceType, sourceId),
        ) == 1) { "message-context ledger entry disappeared" }
    }

    @Synchronized
    fun staleBatch(sweepEpoch: Long, limit: Int): List<Entry> {
        require(sweepEpoch > 0L && limit in 1..MAX_BATCH) {
            "invalid message-context deletion page"
        }
        return entries("last_seen_epoch<?", arrayOf(sweepEpoch.toString()), limit)
    }

    @Synchronized
    fun allBatch(limit: Int): List<Entry> {
        require(limit in 1..MAX_BATCH) { "invalid message-context cleanup page" }
        return entries(null, null, limit)
    }

    @Synchronized
    fun remove(sourceType: String, sourceId: String) {
        validateSource(sourceType, sourceId)
        writableDatabase.delete(
            "ledger",
            "source_type=? AND source_id=?",
            arrayOf(sourceType, sourceId),
        )
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("ledger", null, null)
    }

    @Synchronized
    fun isEmpty(): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM ledger LIMIT 1",
        null,
    ).use { !it.moveToFirst() }

    private fun entries(selection: String?, arguments: Array<String>?, limit: Int): List<Entry> {
        val result = mutableListOf<Entry>()
        readableDatabase.query(
            "ledger",
            arrayOf("source_type", "source_id", "fingerprint", "last_seen_epoch"),
            selection,
            arguments,
            null,
            null,
            "source_type ASC, source_id ASC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += Entry(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getLong(3),
                )
            }
        }
        return result
    }

    private fun nextClock(name: String, floor: Long): Long {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val previous = database.query(
                "clocks",
                arrayOf("value"),
                "name=?",
                arrayOf(name),
                null,
                null,
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            check(previous < Long.MAX_VALUE) { "$name is exhausted" }
            val next = max(floor, previous + 1L)
            val values = ContentValues().apply {
                put("name", name)
                put("value", next)
            }
            check(database.insertWithOnConflict(
                "clocks",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            ) >= 0L) { "cannot persist $name" }
            database.setTransactionSuccessful()
            return next
        } finally {
            database.endTransaction()
        }
    }

    private fun validateSource(sourceType: String, sourceId: String) {
        require(
            (sourceType == MessageContextPolicy.SOURCE_SMS ||
                sourceType == MessageContextPolicy.SOURCE_MMS) && SOURCE_ID.matches(sourceId),
        ) { "invalid message-context source" }
    }

    internal data class Entry(
        val sourceType: String,
        val sourceId: String,
        val fingerprint: String,
        val lastSeenEpoch: Long,
    )

    private companion object {
        const val DATABASE = "message_context_ledger.db"
        const val VERSION = 1
        const val SWEEP_CLOCK = "sweep_epoch"
        const val REVISION_CLOCK = "revision"
        const val MAX_BATCH = 256
        val SOURCE_ID = Regex("[1-9][0-9]{0,18}")
        val FINGERPRINT = Regex("[0-9a-f]{64}")
    }
}

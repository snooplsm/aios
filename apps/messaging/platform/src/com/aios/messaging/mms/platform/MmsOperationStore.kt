package com.aios.messaging.mms.platform

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.aios.messaging.mms.MmsOperationKind
import com.aios.messaging.mms.MmsOperationPolicy
import com.aios.messaging.mms.MmsOperationState

internal data class MmsOperationRecord(
    val token: String,
    val kind: MmsOperationKind,
    val state: MmsOperationState,
    val providerUri: String,
    val pduUri: String,
    val subscriptionId: Int,
    val transactionId: String,
    val contentLocation: String,
    val receivedAtSeconds: Long,
    val expirySeconds: Long,
    val carrierResult: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** App-private journal for callbacks that may arrive after process death. */
internal class MmsOperationStore(context: Context) :
    SQLiteOpenHelper(context, DATABASE, null, VERSION) {

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE operations (
                token TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                state TEXT NOT NULL,
                provider_uri TEXT NOT NULL,
                pdu_uri TEXT NOT NULL,
                subscription_id INTEGER NOT NULL,
                transaction_id TEXT NOT NULL,
                content_location TEXT NOT NULL,
                received_at_seconds INTEGER NOT NULL,
                expiry_seconds INTEGER NOT NULL,
                carrier_result INTEGER NOT NULL,
                created_at_millis INTEGER NOT NULL,
                updated_at_millis INTEGER NOT NULL
            )""".trimIndent(),
        )
        database.execSQL("CREATE INDEX operation_provider ON operations(provider_uri, kind)")
        database.execSQL("CREATE INDEX operation_state ON operations(state, updated_at_millis)")
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun create(record: MmsOperationRecord) {
        check(writableDatabase.insertOrThrow(TABLE, null, values(record)) != -1L)
    }

    fun get(token: String): MmsOperationRecord? = readableDatabase.query(
        TABLE,
        PROJECTION,
        "token=?",
        arrayOf(token),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) record(cursor) else null }

    fun updateUris(token: String, providerUri: String, pduUri: String) {
        val values = ContentValues().apply {
            put("provider_uri", providerUri)
            put("pdu_uri", pduUri)
            put("updated_at_millis", System.currentTimeMillis())
        }
        check(writableDatabase.update(TABLE, values, "token=?", arrayOf(token)) == 1)
    }

    fun transition(token: String, target: MmsOperationState, carrierResult: Int = 0): Boolean {
        val database = writableDatabase
        database.beginTransaction()
        return try {
            val current = database.query(
                TABLE,
                arrayOf("state"),
                "token=?",
                arrayOf(token),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (cursor.moveToFirst()) MmsOperationState.valueOf(cursor.getString(0)) else null
            } ?: return false
            if (!MmsOperationPolicy.canTransition(current, target)) return false
            val values = ContentValues().apply {
                put("state", target.name)
                put("carrier_result", carrierResult)
                put("updated_at_millis", System.currentTimeMillis())
            }
            val changed = database.update(TABLE, values, "token=? AND state=?", arrayOf(
                token,
                current.name,
            )) == 1
            if (changed) database.setTransactionSuccessful()
            changed
        } finally {
            database.endTransaction()
        }
    }

    fun hasActive(providerUri: String, kind: MmsOperationKind): Boolean {
        val terminal = arrayOf(MmsOperationState.SUCCEEDED.name, MmsOperationState.FAILED.name)
        return readableDatabase.query(
            TABLE,
            arrayOf("token"),
            "provider_uri=? AND kind=? AND state NOT IN (?,?)",
            arrayOf(providerUri, kind.name, terminal[0], terminal[1]),
            null,
            null,
            null,
            "1",
        ).use { cursor -> cursor.moveToFirst() }
    }

    fun activeTokens(): Set<String> {
        val terminal = arrayOf(MmsOperationState.SUCCEEDED.name, MmsOperationState.FAILED.name)
        return readableDatabase.query(
            TABLE,
            arrayOf("token"),
            "state NOT IN (?,?)",
            terminal,
            null,
            null,
            null,
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }

    fun recover(nowMillis: Long): List<MmsOperationRecord> {
        val open = readableDatabase.query(
            TABLE,
            PROJECTION,
            "state NOT IN (?,?)",
            arrayOf(MmsOperationState.SUCCEEDED.name, MmsOperationState.FAILED.name),
            null,
            null,
            "updated_at_millis ASC",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(record(cursor)) } }
        return open.filter { item ->
            val recovered = MmsOperationPolicy.recover(
                item.state,
                (nowMillis - item.updatedAtMillis).coerceAtLeast(0L),
            )
            recovered == MmsOperationState.FAILED &&
                transition(item.token, MmsOperationState.FAILED, RECOVERY_FAILURE)
        }
    }

    fun deleteTerminalOlderThan(cutoffMillis: Long) {
        writableDatabase.delete(
            TABLE,
            "state IN (?,?) AND updated_at_millis<?",
            arrayOf(
                MmsOperationState.SUCCEEDED.name,
                MmsOperationState.FAILED.name,
                cutoffMillis.toString(),
            ),
        )
    }

    private fun values(record: MmsOperationRecord) = ContentValues().apply {
        put("token", record.token)
        put("kind", record.kind.name)
        put("state", record.state.name)
        put("provider_uri", record.providerUri)
        put("pdu_uri", record.pduUri)
        put("subscription_id", record.subscriptionId)
        put("transaction_id", record.transactionId)
        put("content_location", record.contentLocation)
        put("received_at_seconds", record.receivedAtSeconds)
        put("expiry_seconds", record.expirySeconds)
        put("carrier_result", record.carrierResult)
        put("created_at_millis", record.createdAtMillis)
        put("updated_at_millis", record.updatedAtMillis)
    }

    private fun record(cursor: Cursor) = MmsOperationRecord(
        token = cursor.getString(0),
        kind = MmsOperationKind.valueOf(cursor.getString(1)),
        state = MmsOperationState.valueOf(cursor.getString(2)),
        providerUri = cursor.getString(3),
        pduUri = cursor.getString(4),
        subscriptionId = cursor.getInt(5),
        transactionId = cursor.getString(6),
        contentLocation = cursor.getString(7),
        receivedAtSeconds = cursor.getLong(8),
        expirySeconds = cursor.getLong(9),
        carrierResult = cursor.getInt(10),
        createdAtMillis = cursor.getLong(11),
        updatedAtMillis = cursor.getLong(12),
    )

    private companion object {
        const val DATABASE = "mms_operations.db"
        const val VERSION = 1
        const val TABLE = "operations"
        const val RECOVERY_FAILURE = -10_001
        val PROJECTION = arrayOf(
            "token",
            "kind",
            "state",
            "provider_uri",
            "pdu_uri",
            "subscription_id",
            "transaction_id",
            "content_location",
            "received_at_seconds",
            "expiry_seconds",
            "carrier_result",
            "created_at_millis",
            "updated_at_millis",
        )
    }
}

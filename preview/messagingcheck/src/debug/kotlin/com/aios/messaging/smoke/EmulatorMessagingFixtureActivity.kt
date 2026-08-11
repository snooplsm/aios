package com.aios.messaging.smoke

import android.app.Activity
import android.content.ContentUris
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import java.io.File

/** Emulator-only provider audit and exact cleanup; absent from the product source set. */
class EmulatorMessagingFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action.orEmpty()
        val incoming = intent?.getStringExtra(EXTRA_INCOMING).orEmpty()
        val outgoing = intent?.getStringExtra(EXTRA_OUTGOING).orEmpty()
        val address = intent?.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val result = runCatching {
            check(isEmulator()) { "fixture requires emulator hardware" }
            check(validToken(incoming) && validToken(outgoing) && incoming != outgoing) {
                "invalid fixture tokens"
            }
            when (action) {
                ACTION_ASSERT -> assertProvider(incoming, outgoing, address)
                ACTION_CLEAN -> cleanProvider(incoming, outgoing)
                else -> error("unsupported fixture action")
            }
        }.getOrElse {
            "{\"schema_version\":1,\"passed\":false,\"error\":\"fixture_failed\"}"
        }
        File(cacheDir, AUDIT_FILE).writeText(result, Charsets.UTF_8)
        finishAndRemoveTask()
    }

    private fun assertProvider(incoming: String, outgoing: String, address: String): String {
        check(address.matches(Regex("[+0-9]{7,20}"))) { "invalid fixture address" }
        val incomingRows = rows(incoming)
        val outgoingRows = rows(outgoing)
        val received = incomingRows.singleOrNull()
        val sent = outgoingRows.singleOrNull {
            it.type == Telephony.Sms.MESSAGE_TYPE_SENT
        }
        val loopback = outgoingRows.singleOrNull {
            it.type == Telephony.Sms.MESSAGE_TYPE_INBOX
        }
        val incomingType = received?.type == Telephony.Sms.MESSAGE_TYPE_INBOX
        val outgoingType = sent != null
        val sameThread = received != null && sent != null && loopback != null &&
            received.threadId > 0L && received.threadId == sent.threadId &&
            received.threadId == loopback.threadId
        val addressMatched = received != null && sent != null && loopback != null &&
            PhoneNumberUtils.compare(received.address, address) &&
            PhoneNumberUtils.compare(sent.address, address) &&
            PhoneNumberUtils.compare(loopback.address, address)
        val validSubscriptions = received != null && sent != null && loopback != null &&
            SubscriptionManager.isValidSubscriptionId(received.subscriptionId) &&
            SubscriptionManager.isValidSubscriptionId(sent.subscriptionId) &&
            SubscriptionManager.isValidSubscriptionId(loopback.subscriptionId)
        val outgoingSentRows = outgoingRows.count {
            it.type == Telephony.Sms.MESSAGE_TYPE_SENT
        }
        val outgoingInboxRows = outgoingRows.count {
            it.type == Telephony.Sms.MESSAGE_TYPE_INBOX
        }
        val outgoingOutboxRows = outgoingRows.count {
            it.type == Telephony.Sms.MESSAGE_TYPE_OUTBOX
        }
        val outgoingFailedRows = outgoingRows.count {
            it.type == Telephony.Sms.MESSAGE_TYPE_FAILED
        }
        val outgoingAddressMatches = outgoingRows.count {
            PhoneNumberUtils.compare(it.address, address)
        }
        val outgoingValidSubscriptions = outgoingRows.count {
            SubscriptionManager.isValidSubscriptionId(it.subscriptionId)
        }
        val outgoingThreadMatches = outgoingRows.count {
            received != null && it.threadId == received.threadId
        }
        val emulatorLoopback = outgoingSentRows == 1 && outgoingInboxRows == 1 &&
            outgoingOutboxRows == 0 && outgoingFailedRows == 0
        val passed = incomingRows.size == 1 && outgoingRows.size == 2 && emulatorLoopback &&
            incomingType && outgoingType && sameThread && addressMatched && validSubscriptions
        return "{\"schema_version\":1,\"passed\":$passed," +
            "\"incoming_rows\":${incomingRows.size}," +
            "\"outgoing_rows\":${outgoingRows.size}," +
            "\"incoming_type\":$incomingType,\"outgoing_type\":$outgoingType," +
            "\"same_thread\":$sameThread,\"address_matched\":$addressMatched," +
            "\"valid_subscriptions\":$validSubscriptions," +
            "\"outgoing_sent_rows\":$outgoingSentRows," +
            "\"outgoing_inbox_rows\":$outgoingInboxRows," +
            "\"outgoing_outbox_rows\":$outgoingOutboxRows," +
            "\"outgoing_failed_rows\":$outgoingFailedRows," +
            "\"outgoing_address_matches\":$outgoingAddressMatches," +
            "\"outgoing_valid_subscriptions\":$outgoingValidSubscriptions," +
            "\"outgoing_thread_matches\":$outgoingThreadMatches," +
            "\"emulator_loopback\":$emulatorLoopback}"
    }

    private fun cleanProvider(incoming: String, outgoing: String): String {
        var deleted = 0
        (rows(incoming) + rows(outgoing)).distinctBy(Row::id).forEach { row ->
            deleted += contentResolver.delete(
                ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, row.id),
                null,
                null,
            )
        }
        val remaining = rows(incoming).size + rows(outgoing).size
        check(remaining == 0) { "fixture rows remain" }
        return "{\"schema_version\":1,\"passed\":true," +
            "\"deleted_rows\":$deleted,\"remaining_rows\":0}"
    }

    private fun rows(body: String): List<Row> {
        val values = mutableListOf<Row>()
        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.TYPE,
                Telephony.Sms.SUBSCRIPTION_ID,
            ),
            "${Telephony.Sms.BODY}=?",
            arrayOf(body),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                values += Row(
                    id = cursor.getLong(0),
                    threadId = cursor.getLong(1),
                    address = cursor.getString(2).orEmpty(),
                    type = cursor.getInt(3),
                    subscriptionId = cursor.getInt(4),
                )
            }
        }
        return values
    }

    private fun validToken(value: String): Boolean =
        value.matches(Regex("AIOS(?:IN|OUT)[A-F0-9]{12}"))

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true)

    private data class Row(
        val id: Long,
        val threadId: Long,
        val address: String,
        val type: Int,
        val subscriptionId: Int,
    )

    private companion object {
        const val ACTION_ASSERT = "com.aios.messaging.smoke.ASSERT"
        const val ACTION_CLEAN = "com.aios.messaging.smoke.CLEAN"
        const val EXTRA_INCOMING = "incoming"
        const val EXTRA_OUTGOING = "outgoing"
        const val EXTRA_ADDRESS = "address"
        const val AUDIT_FILE = "aios-messaging-smoke-audit.json"
    }
}

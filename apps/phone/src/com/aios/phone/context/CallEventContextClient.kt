package com.aios.phone.context

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.ContentObserver
import android.database.Cursor
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Base64
import com.aios.context.ContextDocument
import com.aios.context.ICommunicationContext
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Reconciles the newest presented CallLog rows into identifier-free context.
 * Numbers cross Binder only as transient identity-resolution inputs.
 */
internal class CallEventContextClient(context: Context) {
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { work ->
        Thread(work, "aios-phone-call-context")
    }
    private val preferences = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            scheduleReconcile(CALL_LOG_SETTLE_MILLIS)
        }
    }
    private val reconcile = Runnable { beginReconcile() }

    @Volatile private var enabled = false
    @Volatile private var remote: ICommunicationContext? = null
    private var bound = false
    private var observerRegistered = false
    private var inFlight = false
    private var rerun = false
    private var retryDelayMillis = INITIAL_RETRY_MILLIS

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = ICommunicationContext.Stub.asInterface(binder)
            retryDelayMillis = INITIAL_RETRY_MILLIS
            scheduleReconcile(0L)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            // Android retains this binding and reconnects when the service returns.
        }

        override fun onBindingDied(name: ComponentName?) {
            remote = null
            if (bound) runCatching { application.unbindService(this) }
            bound = false
            scheduleRetry()
        }

        override fun onNullBinding(name: ComponentName?) {
            remote = null
            if (bound) runCatching { application.unbindService(this) }
            bound = false
            scheduleRetry()
        }
    }

    fun setEnabled(value: Boolean) = onMain {
        enabled = value
        retryDelayMillis = INITIAL_RETRY_MILLIS
        if (value) registerObserver() else unregisterObserver()
        scheduleReconcile(0L)
    }

    fun onCallLogMayHaveChanged() = onMain {
        if (enabled) scheduleReconcile(CALL_LOG_SETTLE_MILLIS)
    }

    private fun beginReconcile() {
        if (inFlight) {
            rerun = true
            return
        }
        ensureBound()
        val service = remote
        if (service == null) {
            scheduleRetry()
            return
        }
        inFlight = true
        val targetEnabled = enabled
        worker.execute {
            val result = runCatching { reconcile(service, targetEnabled) }
            main.post {
                inFlight = false
                if (result.exceptionOrNull() is DesiredStateChangedException) {
                    scheduleReconcile(0L)
                } else if (result.isFailure) scheduleRetry()
                else retryDelayMillis = INITIAL_RETRY_MILLIS
                if (rerun) {
                    rerun = false
                    scheduleReconcile(0L)
                } else if (!enabled && loadLedger().isEmpty()) {
                    disconnect()
                }
            }
        }
    }

    private fun reconcile(service: ICommunicationContext, targetEnabled: Boolean) {
        if (targetEnabled && !dialerRoleHeld()) {
            main.post { setEnabled(false) }
            throw DesiredStateChangedException()
        }
        val records = if (targetEnabled) queryRecords() else emptyList()
        if (enabled != targetEnabled) throw DesiredStateChangedException()
        var indexed = loadLedger()
        val mutations = CallEventReconciler.reconcile(records, indexed, fingerprintSecret())
        for (mutation in mutations) {
            if (enabled != targetEnabled) throw DesiredStateChangedException()
            val revision = nextRevision()
            val updated = indexed.toMutableMap()
            when (mutation) {
                is CallEventMutation.Delete -> {
                    service.deleteSource(SOURCE_CALL_EVENT, mutation.sourceId, revision)
                    updated.remove(mutation.sourceId)
                }
                is CallEventMutation.Upsert -> {
                    val record = mutation.record
                    val identity = service.resolveIdentity(record.address, record.countryIso)
                    service.upsert(
                        ContextDocument(
                            SOURCE_CALL_EVENT,
                            record.sourceId,
                            revision,
                            identity,
                            record.eventAtEpochMillis,
                            0L,
                            record.contextText(),
                        ),
                    )
                    updated[record.sourceId] = mutation.fingerprint
                }
            }
            saveLedger(updated)
            indexed = updated
        }
    }

    private fun queryRecords(): List<CallEventRecord> {
        return try {
            queryRecords(CallLog.Calls.CONTENT_URI_WITH_VOICEMAIL)
        } catch (_: SecurityException) {
            queryRecords(CallLog.Calls.CONTENT_URI)
        }
    }

    private fun queryRecords(uri: android.net.Uri): List<CallEventRecord> {
        var cursor: Cursor? = null
        return try {
            cursor = application.contentResolver.query(
                uri,
                PROJECTION,
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            ) ?: throw IllegalStateException("CallLog query returned no cursor")
            val telephony = application.getSystemService(TelephonyManager::class.java)
                ?: return emptyList()
            val countryIso = countryIso(telephony)
            buildList {
                while (cursor.moveToNext() && size < CallEventReconciler.MAX_INDEXED_EVENTS) {
                    val id = cursor.getLong(0)
                    val address = cursor.getString(1).orEmpty().trim().take(MAX_ADDRESS_CHARS)
                    val presentation = cursor.getInt(5)
                    val at = cursor.getLong(3)
                    if (id <= 0L || address.isBlank() || at <= 0L ||
                        presentation != TelecomManager.PRESENTATION_ALLOWED ||
                        PhoneNumberUtils.normalizeNumber(address).isBlank() ||
                        telephony.isEmergencyNumber(address)) continue
                    add(
                        CallEventRecord(
                            sourceId = "calllog:$id",
                            address = address,
                            countryIso = countryIso,
                            kind = CallEventKind.fromCallLogType(cursor.getInt(2)),
                            eventAtEpochMillis = at,
                            durationSeconds = cursor.getLong(4).coerceAtLeast(0L),
                            isVideo = cursor.getInt(6) and CallLog.Calls.FEATURES_VIDEO != 0,
                        ),
                    )
                }
            }
        } finally {
            cursor?.close()
        }
    }

    private fun countryIso(telephony: TelephonyManager): String {
        return telephony.simCountryIso?.takeIf(String::isNotBlank)
            ?: telephony.networkCountryIso?.takeIf(String::isNotBlank)
            ?: Locale.getDefault().country
    }

    private fun dialerRoleHeld(): Boolean =
        application.getSystemService(RoleManager::class.java)
            ?.isRoleHeld(RoleManager.ROLE_DIALER) == true

    private fun loadLedger(): Map<String, String> = preferences
        .getStringSet(LEDGER, emptySet())
        .orEmpty()
        .mapNotNull { encoded ->
            val separator = encoded.indexOf('|')
            if (separator <= 0) return@mapNotNull null
            val sourceId = encoded.substring(0, separator)
            val fingerprint = encoded.substring(separator + 1)
            if (!sourceId.matches(LEDGER_SOURCE_ID) || !fingerprint.matches(FINGERPRINT)) {
                null
            } else {
                sourceId to fingerprint
            }
        }
        .toMap()

    private fun saveLedger(value: Map<String, String>) {
        val encoded = value.mapTo(mutableSetOf()) { (sourceId, fingerprint) ->
            "$sourceId|$fingerprint"
        }
        check(preferences.edit().putStringSet(LEDGER, encoded).commit()) {
            "cannot persist call-event reconciliation ledger"
        }
    }

    private fun fingerprintSecret(): ByteArray {
        preferences.getString(FINGERPRINT_SECRET, null)?.let { encoded ->
            runCatching { Base64.decode(encoded, Base64.NO_WRAP) }
                .getOrNull()
                ?.takeIf { it.size == FINGERPRINT_SECRET_BYTES }
                ?.let { return it }
        }
        val generated = ByteArray(FINGERPRINT_SECRET_BYTES).also(SecureRandom()::nextBytes)
        check(
            preferences.edit().putString(
                FINGERPRINT_SECRET,
                Base64.encodeToString(generated, Base64.NO_WRAP),
            ).commit(),
        ) { "cannot persist call-event fingerprint secret" }
        return generated
    }

    private fun nextRevision(): Long {
        val previous = preferences.getLong(REVISION_CLOCK, 0L)
        check(previous < Long.MAX_VALUE) { "call-event revision clock is exhausted" }
        val next = max(System.currentTimeMillis().coerceAtLeast(1L), previous + 1L)
        check(preferences.edit().putLong(REVISION_CLOCK, next).commit()) {
            "cannot persist call-event revision"
        }
        return next
    }

    private fun registerObserver() {
        if (observerRegistered) return
        runCatching {
            application.contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI,
                true,
                observer,
            )
        }.onSuccess { observerRegistered = true }
    }

    private fun unregisterObserver() {
        if (!observerRegistered) return
        runCatching { application.contentResolver.unregisterContentObserver(observer) }
        observerRegistered = false
    }

    private fun ensureBound() {
        if (bound || (!enabled && loadLedger().isEmpty())) return
        val intent = Intent(ACTION).setComponent(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS))
        bound = application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun disconnect() {
        if (bound) runCatching { application.unbindService(connection) }
        bound = false
        remote = null
    }

    private fun scheduleReconcile(delayMillis: Long) {
        main.removeCallbacks(reconcile)
        main.postDelayed(reconcile, delayMillis)
    }

    private fun scheduleRetry() {
        if (!enabled && loadLedger().isEmpty()) return
        scheduleReconcile(retryDelayMillis)
        retryDelayMillis = min(retryDelayMillis * 2L, MAX_RETRY_MILLIS)
    }

    private inline fun onMain(crossinline operation: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) operation()
        else main.post { operation() }
    }

    private class DesiredStateChangedException : RuntimeException()

    private companion object {
        const val ACTION = "com.aios.context.COMMUNICATION_CONTEXT_SERVICE"
        const val SERVICE_PACKAGE = "com.aios.contextintelligence"
        const val SERVICE_CLASS =
            "com.aios.contextintelligence.CommunicationContextService"
        const val SOURCE_CALL_EVENT = "call_event"
        const val PREFS = "call_event_context"
        const val LEDGER = "indexed_events"
        const val REVISION_CLOCK = "revision_clock"
        const val FINGERPRINT_SECRET = "fingerprint_secret"
        const val FINGERPRINT_SECRET_BYTES = 32
        const val MAX_ADDRESS_CHARS = 80
        const val CALL_LOG_SETTLE_MILLIS = 1_500L
        const val INITIAL_RETRY_MILLIS = 15_000L
        const val MAX_RETRY_MILLIS = 15L * 60L * 1_000L
        val PROJECTION = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.NUMBER_PRESENTATION,
            CallLog.Calls.FEATURES,
        )
        val LEDGER_SOURCE_ID = Regex("calllog:[1-9][0-9]{0,18}")
        val FINGERPRINT = Regex("[0-9a-f]{64}")
    }
}

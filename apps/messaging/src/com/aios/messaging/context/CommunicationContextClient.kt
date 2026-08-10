package com.aios.messaging.context

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Base64
import com.aios.context.ContextDocument
import com.aios.context.ContextSnippet
import com.aios.context.ConversationIdentity
import com.aios.context.ICommunicationContext
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class CommunicationContextClient(
    context: Context,
    private val onMmsSourceDeleted: (Long) -> Unit = {},
    private val onMmsSourcesCleared: () -> Unit = {},
) {
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { work ->
        Thread(work, "aios-message-context")
    }
    private val ledger = MessageContextLedger(application)
    private val provider = MessageContextProvider(application)
    private val preferences = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val pending = mutableListOf<(ICommunicationContext) -> Unit>()
    private val pendingMutations = mutableListOf<PendingMutation>()
    private val reconciliationWaiters = mutableListOf<(Result<Unit>) -> Unit>()
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            if (!selfChange && providerReconciliationEnabled) {
                scheduleReconciliation(PROVIDER_SETTLE_MILLIS)
            }
        }
    }

    @Volatile private var providerReconciliationEnabled = false
    private var remote: ICommunicationContext? = null
    private var bound = false
    private var observerRegistered = false
    private var reconciliationInFlight = false
    private var reconciliationRerun = false
    private var retryDelayMillis = INITIAL_RETRY_MILLIS
    private val queuedMutations = AtomicInteger()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val value = ICommunicationContext.Stub.asInterface(service) ?: return
            remote = value
            val work = pending.toList()
            pending.clear()
            work.forEach { operation -> runCatching { operation(value) } }
            val mutations = pendingMutations.toList()
            pendingMutations.clear()
            mutations.forEach { mutation ->
                submitMutation(value, mutation.revisionFloor, mutation.operation)
            }
            retryDelayMillis = INITIAL_RETRY_MILLIS
            scheduleReconciliation(0L)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
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

    fun connect() {
        if (bound) return
        val intent = Intent(ACTION).setComponent(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS))
        bound = application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun setProviderReconciliationEnabled(value: Boolean) = onMain {
        val changed = providerReconciliationEnabled != value
        providerReconciliationEnabled = value
        retryDelayMillis = INITIAL_RETRY_MILLIS
        if (value) {
            if (changed) {
                check(preferences.edit().putBoolean(ROLE_CLEANUP_COMPLETE, false).commit()) {
                    "cannot persist active message-context role"
                }
            }
            registerObserver()
            connect()
        } else {
            unregisterObserver()
            pending.clear()
            pendingMutations.clear()
        }
        if (changed || (!value && needsRoleCleanup())) scheduleReconciliation(0L)
    }

    fun requestProviderReconciliation(completion: (Result<Unit>) -> Unit) = onMain {
        if (!providerReconciliationEnabled && !needsRoleCleanup()) {
            completion(Result.success(Unit))
            return@onMain
        }
        reconciliationWaiters += completion
        if (reconciliationWaiters.size > MAX_RECONCILIATION_WAITERS) {
            reconciliationWaiters.removeAt(0)(
                Result.failure(IllegalStateException("message-context request superseded")),
            )
        }
        scheduleReconciliation(0L)
    }

    fun indexSms(id: Long, address: String, body: String, atEpochMillis: Long) {
        if (id <= 0L || address.isBlank() || body.isBlank()) return
        withMutation { service, revision ->
            val record = MessageContextPolicy.sanitize(
                ProviderContextRecord(
                    SOURCE_SMS,
                    id.toString(),
                    address,
                    countryIso(),
                    atEpochMillis.coerceAtLeast(1L),
                    body,
                ),
            ) ?: return@withMutation
            val identity = service.resolveIdentity(record.address, record.countryIso)
            service.upsert(
                ContextDocument(
                    record.sourceType,
                    record.sourceId,
                    revision,
                    identity,
                    record.eventAtEpochMillis,
                    0L,
                    record.text,
                ),
            )
            ledger.recordSeen(
                record.sourceType,
                record.sourceId,
                MessageContextPolicy.fingerprint(record, fingerprintSecret()),
                ledger.beginSweep(),
            )
        }
    }

    fun deleteSms(id: Long, revision: Long) {
        if (id <= 0L) return
        withMutation(revision.coerceAtLeast(1L)) { service, orderedRevision ->
            service.deleteSource(
                SOURCE_SMS,
                id.toString(),
                orderedRevision,
            )
            ledger.remove(SOURCE_SMS, id.toString())
        }
    }

    fun indexMms(
        id: Long,
        address: String,
        body: String,
        atEpochMillis: Long,
        hasPhoto: Boolean,
    ) {
        if (id <= 0L || address.isBlank()) return
        val text = buildString {
            append(body.take(MAX_INDEX_CHARS))
            if (hasPhoto && !body.contains("[Photo]")) append("\n[Photo]")
        }.take(MAX_INDEX_CHARS).ifBlank { "[MMS]" }
        withMutation { service, revision ->
            val record = MessageContextPolicy.sanitize(
                ProviderContextRecord(
                    SOURCE_MMS,
                    id.toString(),
                    address,
                    countryIso(),
                    atEpochMillis.coerceAtLeast(1L),
                    text,
                ),
            ) ?: return@withMutation
            val identity = service.resolveIdentity(record.address, record.countryIso)
            service.upsert(
                ContextDocument(
                    record.sourceType,
                    record.sourceId,
                    revision,
                    identity,
                    record.eventAtEpochMillis,
                    0L,
                    record.text,
                ),
            )
            ledger.recordSeen(
                record.sourceType,
                record.sourceId,
                MessageContextPolicy.fingerprint(record, fingerprintSecret()),
                ledger.beginSweep(),
            )
        }
    }

    fun deleteMms(id: Long, revision: Long) {
        if (id <= 0L) return
        withMutation(revision.coerceAtLeast(1L)) { service, orderedRevision ->
            service.deleteSource(
                SOURCE_MMS,
                id.toString(),
                orderedRevision,
            )
            ledger.remove(SOURCE_MMS, id.toString())
        }
    }

    fun queryRecent(address: String, callback: (List<ContextSnippet>) -> Unit) {
        if (address.isBlank()) {
            callback(emptyList())
            return
        }
        withService { service ->
            val result = service.query(
                service.resolveIdentity(address, countryIso()),
                "",
                8,
                System.currentTimeMillis(),
            )
            main.post { callback(result) }
        }
    }

    fun resolveIdentity(address: String, callback: (ConversationIdentity?) -> Unit) {
        if (address.isBlank()) {
            callback(null)
            return
        }
        withService { service ->
            worker.execute {
                val identity = runCatching {
                    service.resolveIdentity(address, countryIso())
                }.getOrNull()
                main.post { callback(identity) }
            }
        }
    }

    fun close() {
        providerReconciliationEnabled = false
        unregisterObserver()
        main.removeCallbacks(reconcileRunnable)
        pending.clear()
        pendingMutations.clear()
        reconciliationWaiters.forEach {
            it(Result.failure(IllegalStateException("message-context client closed")))
        }
        reconciliationWaiters.clear()
        if (bound) runCatching { application.unbindService(connection) }
        bound = false
        remote = null
        worker.shutdownNow()
    }

    private val reconcileRunnable = Runnable { beginReconciliation() }

    private fun beginReconciliation() {
        if (reconciliationInFlight) {
            reconciliationRerun = true
            return
        }
        if (!providerReconciliationEnabled && !needsRoleCleanup()) {
            disconnect()
            return
        }
        connect()
        val service = remote
        if (service == null) {
            scheduleRetry()
            return
        }
        reconciliationInFlight = true
        val targetEnabled = providerReconciliationEnabled
        worker.execute {
            val result = runCatching {
                if (targetEnabled) reconcileProvider(service) else clearProviderContext(service)
            }
            main.post {
                reconciliationInFlight = false
                when {
                    result.exceptionOrNull() is DesiredStateChangedException ||
                        result.exceptionOrNull() is ReconciliationYieldException ->
                        scheduleReconciliation(0L)
                    result.isFailure -> {
                        completeReconciliationWaiters(result)
                        scheduleRetry()
                    }
                    else -> {
                        retryDelayMillis = INITIAL_RETRY_MILLIS
                        if (!targetEnabled) {
                            check(preferences.edit()
                                .putBoolean(ROLE_CLEANUP_COMPLETE, true)
                                .commit()) { "cannot persist message-context role cleanup" }
                        }
                        completeReconciliationWaiters(Result.success(Unit))
                    }
                }
                if (reconciliationRerun) {
                    reconciliationRerun = false
                    scheduleReconciliation(0L)
                } else if (!providerReconciliationEnabled && !needsRoleCleanup()) {
                    disconnect()
                }
            }
        }
    }

    private fun reconcileProvider(service: ICommunicationContext) {
        checkDesiredState(true)
        val storeInstance = service.getStoreInstanceId()
        require(CONTEXT_STORE_INSTANCE_PATTERN.matches(storeInstance)) {
            "communication-context store instance is invalid"
        }
        if (preferences.getString(CONTEXT_STORE_INSTANCE, null) != storeInstance) {
            resetProviderContext(service)
            check(preferences.edit().putString(
                CONTEXT_STORE_INSTANCE,
                storeInstance,
            ).commit()) { "cannot persist communication-context store instance" }
        }
        val highWatermarks = SOURCE_TYPES.associateWith(provider::highWatermark)
        val sweepEpoch = ledger.beginSweep()
        val secret = fingerprintSecret()
        SOURCE_TYPES.forEach { sourceType ->
            var afterId = 0L
            val throughId = highWatermarks.getValue(sourceType)
            while (afterId < throughId) {
                checkDesiredState(true)
                val page = provider.page(sourceType, afterId, throughId, PROVIDER_PAGE_SIZE)
                page.records.forEach { record ->
                    checkDesiredState(true)
                    val observedFingerprint = MessageContextPolicy.fingerprint(record, secret)
                    val current = ledger.find(record.sourceType, record.sourceId)
                    if (current?.fingerprint == observedFingerprint) {
                        ledger.markSeen(record.sourceType, record.sourceId, sweepEpoch)
                    } else {
                        val latest = provider.exact(record.sourceType, record.sourceId)
                            ?: return@forEach
                        val fingerprint = MessageContextPolicy.fingerprint(latest, secret)
                        val identity = service.resolveIdentity(latest.address, latest.countryIso)
                        service.upsert(
                            ContextDocument(
                                latest.sourceType,
                                latest.sourceId,
                                ledger.nextRevision(System.currentTimeMillis()),
                                identity,
                                latest.eventAtEpochMillis,
                                0L,
                                latest.text,
                            ),
                        )
                        ledger.recordSeen(
                            latest.sourceType,
                            latest.sourceId,
                            fingerprint,
                            sweepEpoch,
                        )
                    }
                }
                check(page.complete || page.nextId > afterId) {
                    "Telephony provider reconciliation did not advance"
                }
                afterId = page.nextId
                if (page.complete) break
                if (queuedMutations.get() > 0) throw ReconciliationYieldException()
            }
        }
        while (true) {
            checkDesiredState(true)
            if (queuedMutations.get() > 0) throw ReconciliationYieldException()
            val stale = ledger.staleBatch(sweepEpoch, LEDGER_PAGE_SIZE)
            if (stale.isEmpty()) break
            stale.forEach { entry ->
                service.deleteSource(
                    entry.sourceType,
                    entry.sourceId,
                    ledger.nextRevision(System.currentTimeMillis()),
                )
                ledger.remove(entry.sourceType, entry.sourceId)
                if (entry.sourceType == SOURCE_MMS) {
                    entry.sourceId.toLongOrNull()?.takeIf { it > 0L }
                        ?.let(onMmsSourceDeleted)
                }
            }
        }
    }

    private fun clearProviderContext(service: ICommunicationContext) {
        checkDesiredState(false)
        resetProviderContext(service)
        onMmsSourcesCleared()
    }

    private fun resetProviderContext(service: ICommunicationContext) {
        SOURCE_TYPES.forEach { sourceType ->
            val watermark = service.deleteSourceType(
                sourceType,
                ledger.nextRevision(System.currentTimeMillis()),
            )
            ledger.nextRevision(watermark)
        }
        ledger.clear()
    }

    private fun checkDesiredState(expectedEnabled: Boolean) {
        if (providerReconciliationEnabled != expectedEnabled) {
            throw DesiredStateChangedException()
        }
        if (expectedEnabled && !smsRoleHeld()) {
            main.post { setProviderReconciliationEnabled(false) }
            throw DesiredStateChangedException()
        }
    }

    private fun smsRoleHeld(): Boolean =
        application.getSystemService(RoleManager::class.java)
            ?.isRoleHeld(RoleManager.ROLE_SMS) == true

    private fun registerObserver() {
        if (observerRegistered) return
        runCatching {
            application.contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true,
                observer,
            )
            application.contentResolver.registerContentObserver(
                Telephony.Mms.CONTENT_URI,
                true,
                observer,
            )
        }.onSuccess {
            observerRegistered = true
        }.onFailure {
            runCatching { application.contentResolver.unregisterContentObserver(observer) }
        }
    }

    private fun unregisterObserver() {
        if (!observerRegistered) return
        runCatching { application.contentResolver.unregisterContentObserver(observer) }
        observerRegistered = false
    }

    private fun scheduleReconciliation(delayMillis: Long) {
        main.removeCallbacks(reconcileRunnable)
        main.postDelayed(reconcileRunnable, delayMillis.coerceAtLeast(0L))
    }

    private fun scheduleRetry() {
        if (!providerReconciliationEnabled && !needsRoleCleanup()) return
        scheduleReconciliation(retryDelayMillis)
        retryDelayMillis = min(retryDelayMillis * 2L, MAX_RETRY_MILLIS)
    }

    private fun completeReconciliationWaiters(result: Result<Unit>) {
        val callbacks = reconciliationWaiters.toList()
        reconciliationWaiters.clear()
        callbacks.forEach { callback -> runCatching { callback(result) } }
    }

    private fun needsRoleCleanup(): Boolean =
        !preferences.getBoolean(ROLE_CLEANUP_COMPLETE, false) || !ledger.isEmpty()

    private fun disconnect() {
        if (bound) runCatching { application.unbindService(connection) }
        bound = false
        remote = null
    }

    private fun fingerprintSecret(): ByteArray {
        preferences.getString(FINGERPRINT_SECRET, null)?.let { encoded ->
            runCatching { Base64.decode(encoded, Base64.NO_WRAP) }
                .getOrNull()
                ?.takeIf { it.size == FINGERPRINT_SECRET_BYTES }
                ?.let { return it }
        }
        val generated = ByteArray(FINGERPRINT_SECRET_BYTES).also(SecureRandom()::nextBytes)
        check(preferences.edit().putString(
            FINGERPRINT_SECRET,
            Base64.encodeToString(generated, Base64.NO_WRAP),
        ).commit()) { "cannot persist message-context fingerprint secret" }
        return generated
    }

    private fun withService(operation: (ICommunicationContext) -> Unit) {
        val value = remote
        if (value != null) {
            runCatching { operation(value) }
        } else {
            pending += operation
            if (pending.size > MAX_PENDING_OPERATIONS) pending.removeAt(0)
            connect()
        }
    }

    private fun withMutation(
        revisionFloor: Long = System.currentTimeMillis(),
        operation: (ICommunicationContext, Long) -> Unit,
    ) = onMain {
        val value = remote
        if (value != null) {
            submitMutation(value, revisionFloor, operation)
        } else {
            pendingMutations += PendingMutation(revisionFloor, operation)
            if (pendingMutations.size > MAX_PENDING_OPERATIONS) pendingMutations.removeAt(0)
            connect()
        }
    }

    private fun submitMutation(
        service: ICommunicationContext,
        revisionFloor: Long,
        operation: (ICommunicationContext, Long) -> Unit,
    ) {
        queuedMutations.incrementAndGet()
        worker.execute {
            try {
                if (!providerReconciliationEnabled || !smsRoleHeld()) return@execute
                runCatching {
                    operation(
                        service,
                        ledger.nextRevision(
                            revisionFloor.coerceAtLeast(System.currentTimeMillis()),
                        ),
                    )
                }.onFailure {
                    main.post { scheduleReconciliation(PROVIDER_SETTLE_MILLIS) }
                }
            } finally {
                queuedMutations.decrementAndGet()
            }
        }
    }

    private fun countryIso(): String {
        val telephony = application.getSystemService(TelephonyManager::class.java)
        return telephony?.networkCountryIso?.takeIf(String::isNotBlank)
            ?: Locale.getDefault().country
    }

    private inline fun onMain(crossinline operation: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) operation()
        else main.post { operation() }
    }

    private class DesiredStateChangedException : RuntimeException()
    private class ReconciliationYieldException : RuntimeException()

    private data class PendingMutation(
        val revisionFloor: Long,
        val operation: (ICommunicationContext, Long) -> Unit,
    )

    private companion object {
        const val ACTION = "com.aios.context.COMMUNICATION_CONTEXT_SERVICE"
        const val SERVICE_PACKAGE = "com.aios.contextintelligence"
        const val SERVICE_CLASS =
            "com.aios.contextintelligence.CommunicationContextService"
        const val SOURCE_SMS = "sms"
        const val SOURCE_MMS = "mms"
        const val MAX_INDEX_CHARS = 4_096
        const val MAX_PENDING_OPERATIONS = 128
        const val MAX_RECONCILIATION_WAITERS = 16
        const val PREFS = "message_context_reconciliation"
        const val ROLE_CLEANUP_COMPLETE = "role_cleanup_complete"
        const val CONTEXT_STORE_INSTANCE = "context_store_instance"
        const val FINGERPRINT_SECRET = "fingerprint_secret"
        const val FINGERPRINT_SECRET_BYTES = 32
        const val PROVIDER_PAGE_SIZE = 128
        const val LEDGER_PAGE_SIZE = 128
        const val PROVIDER_SETTLE_MILLIS = 1_500L
        const val INITIAL_RETRY_MILLIS = 15_000L
        const val MAX_RETRY_MILLIS = 15L * 60L * 1_000L
        val SOURCE_TYPES = listOf(SOURCE_SMS, SOURCE_MMS)
        val CONTEXT_STORE_INSTANCE_PATTERN = Regex("[0-9a-f]{32}")
    }
}

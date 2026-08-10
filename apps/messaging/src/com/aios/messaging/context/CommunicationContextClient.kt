package com.aios.messaging.context

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import com.aios.context.ContextDocument
import com.aios.context.ContextSnippet
import com.aios.context.ICommunicationContext
import java.util.Locale

class CommunicationContextClient(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<(ICommunicationContext) -> Unit>()
    private var remote: ICommunicationContext? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val value = ICommunicationContext.Stub.asInterface(service) ?: return
            remote = value
            val work = pending.toList()
            pending.clear()
            work.forEach { operation -> runCatching { operation(value) } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
        }

        override fun onBindingDied(name: ComponentName?) {
            remote = null
            bound = false
            connect()
        }
    }

    fun connect() {
        if (bound) return
        val intent = Intent(ACTION).setComponent(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS))
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun indexSms(id: Long, address: String, body: String, atEpochMillis: Long) {
        if (id <= 0L || address.isBlank() || body.isBlank()) return
        withService { service ->
            val identity = service.resolveIdentity(address, countryIso())
            service.upsert(
                ContextDocument(
                    SOURCE_SMS,
                    id.toString(),
                    atEpochMillis.coerceAtLeast(1L),
                    identity,
                    atEpochMillis.coerceAtLeast(1L),
                    0L,
                    body.take(MAX_INDEX_CHARS),
                ),
            )
        }
    }

    fun deleteSms(id: Long, revision: Long) {
        if (id <= 0L) return
        withService { service ->
            service.deleteSource(SOURCE_SMS, id.toString(), revision.coerceAtLeast(1L))
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

    fun close() {
        pending.clear()
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        remote = null
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

    private fun countryIso(): String {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return telephony?.networkCountryIso?.takeIf(String::isNotBlank)
            ?: Locale.getDefault().country
    }

    private companion object {
        const val ACTION = "com.aios.context.COMMUNICATION_CONTEXT_SERVICE"
        const val SERVICE_PACKAGE = "com.aios.contextintelligence"
        const val SERVICE_CLASS =
            "com.aios.contextintelligence.CommunicationContextService"
        const val SOURCE_SMS = "sms"
        const val MAX_INDEX_CHARS = 4_096
        const val MAX_PENDING_OPERATIONS = 128
    }
}

package com.aios.messaging.context

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.aios.context.ConversationIdentity
import com.aios.media.IMediaContextAssociation
import java.util.LinkedHashSet
import java.util.concurrent.Executors

/** Optional, signature-only client; media context never blocks carrier messaging. */
class MediaContextAssociationClient(context: Context) {
    private data class PendingOperation(
        val key: String,
        val execute: (IMediaContextAssociation) -> Boolean,
        val onSuccess: () -> Unit = {},
    )

    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { work ->
        Thread(work, "aios-message-media-context")
    }
    private val rebindPolicy = MessagingServiceRebindPolicy()
    private val pending = LatestOperationQueue<PendingOperation>(MAX_PENDING_OPERATIONS)
    private val cancelled = LinkedHashSet<String>()
    private var activeOperation: PendingOperation? = null
    private var activeConnection: AssociationConnection? = null
    private var remote: IMediaContextAssociation? = null
    private var binding = false
    private var connected = false
    private var closed = false

    private val rebind = Runnable {
        if (rebindPolicy.begin()) bindAssociationService()
    }

    private inner class AssociationConnection : ServiceConnection {
        val timeout = Runnable { onConnectionTimedOut(this) }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = IMediaContextAssociation.Stub.asInterface(binder)
            if (service == null) {
                replaceTerminalBinding(this, immediate = false)
                return
            }
            val accepted = synchronized(this@MediaContextAssociationClient) {
                if (closed || activeConnection !== this) {
                    false
                } else {
                    remote = service
                    connected = true
                    true
                }
            }
            if (!accepted) return
            main.removeCallbacks(timeout)
            rebindPolicy.connected()
            pump(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Android retains an ordinary crash binding; replace it if reconnect stalls.
            if (!clearCurrent(this)) return
            armConnectionTimeout(this)
        }

        override fun onBindingDied(name: ComponentName?) {
            replaceTerminalBinding(this, immediate = true)
        }

        override fun onNullBinding(name: ComponentName?) {
            replaceTerminalBinding(this, immediate = false)
        }
    }

    fun connect() {
        synchronized(this) {
            if (closed || activeConnection != null || binding) return
        }
        scheduleRebind(immediate = true)
    }

    fun stageMmsPhoto(
        associationToken: String,
        photoUri: String,
        identity: ConversationIdentity,
        eventAtEpochMillis: Long,
    ) {
        if (associationToken.isBlank() || photoUri.isBlank() || eventAtEpochMillis <= 0L) return
        enqueue(PendingOperation(
            key = stageKey(associationToken),
            execute = { service ->
                if (isCancelled(associationToken)) return@PendingOperation false
                val uri = Uri.parse(photoUri)
                if (uri.scheme != "content") return@PendingOperation false
                val mimeType = application.contentResolver.getType(uri)
                    ?.takeIf { it.startsWith("image/") }
                    ?: return@PendingOperation false
                val descriptor = application.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@PendingOperation false
                descriptor.use {
                    service.stageMmsPhoto(
                        associationToken,
                        it,
                        mimeType,
                        identity,
                        eventAtEpochMillis,
                    )
                }
                true
            },
        ))
    }

    fun completeMmsPhoto(
        associationToken: String,
        providerId: Long,
        eventAtEpochMillis: Long,
        onDurablyRecorded: () -> Unit,
    ) {
        if (associationToken.isBlank() || providerId <= 0L || eventAtEpochMillis <= 0L ||
            isCancelled(associationToken)) return
        enqueue(PendingOperation(
            key = completeKey(associationToken),
            execute = { service ->
                if (isCancelled(associationToken)) return@PendingOperation false
                service.completeMmsPhoto(
                    associationToken,
                    "mms:$providerId",
                    eventAtEpochMillis,
                )
                true
            },
            onSuccess = onDurablyRecorded,
        ))
    }

    fun cancelMmsPhoto(associationToken: String) {
        if (associationToken.isBlank()) return
        synchronized(this) {
            if (closed) return
            cancelled += associationToken
            while (cancelled.size > MAX_CANCELLED_TOKENS) {
                cancelled.remove(cancelled.first())
            }
            pending.removeWhere { key ->
                key == stageKey(associationToken) || key == completeKey(associationToken)
            }
        }
        enqueue(PendingOperation(cancelKey(associationToken), { service ->
            service.cancelMmsPhoto(associationToken)
            true
        }))
    }

    fun deleteMmsPhoto(providerId: Long) {
        if (providerId <= 0L) return
        val sourceId = "mms:$providerId"
        enqueue(PendingOperation(deleteKey(sourceId), { service ->
            service.deleteMmsPhoto(sourceId)
            true
        }))
    }

    fun clearMmsPhotos() {
        synchronized(this) {
            if (closed) return
            pending.removeWhere { it.startsWith(DELETE_PREFIX) }
        }
        enqueue(PendingOperation(CLEAR_KEY, { service ->
            service.clearMmsPhotos()
            true
        }))
    }

    fun close() {
        val connection: AssociationConnection?
        synchronized(this) {
            if (closed) return
            closed = true
            pending.clear()
            cancelled.clear()
            activeOperation = null
            remote = null
            connected = false
            connection = activeConnection
            activeConnection = null
            binding = false
        }
        rebindPolicy.close()
        main.removeCallbacks(rebind)
        connection?.let { main.removeCallbacks(it.timeout) }
        unbindQuietly(connection)
        worker.shutdownNow()
    }

    private fun enqueue(operation: PendingOperation) {
        val service = synchronized(this) {
            if (closed) return
            val evicted = pending.put(
                operation.key,
                operation,
                protectedKey = activeOperation?.key,
            )
            if (evicted != null) {
                Log.w(TAG, "Dropping oldest pending media-association operation")
            }
            remote
        }
        if (service == null) connect() else pump(service)
    }

    private fun pump(service: IMediaContextAssociation) {
        val operation = synchronized(this) {
            if (closed || !connected || remote !== service || activeOperation != null) return
            pending.firstOrNull()?.also { activeOperation = it } ?: return
        }
        try {
            worker.execute { perform(service, operation) }
        } catch (_: RuntimeException) {
            synchronized(this) {
                if (activeOperation === operation) activeOperation = null
            }
        }
    }

    private fun perform(service: IMediaContextAssociation, operation: PendingOperation) {
        var completed = false
        var invokeSuccess = false
        var remoteFailure = false
        try {
            invokeSuccess = operation.execute(service)
            completed = true
        } catch (error: RemoteException) {
            remoteFailure = true
            Log.w(TAG, "Media-association service disconnected", error)
        } catch (error: RuntimeException) {
            completed = true
            Log.w(TAG, "Media-association operation rejected", error)
        }

        var next: IMediaContextAssociation?
        var callback: (() -> Unit)? = null
        synchronized(this) {
            if (activeOperation === operation) activeOperation = null
            if (completed && pending.removeIfCurrent(operation.key, operation) && invokeSuccess) {
                callback = operation.onSuccess
            }
            next = remote
        }
        if (remoteFailure) invalidate(service)
        callback?.let { runCatching(it).onFailure { error ->
            Log.w(TAG, "Media-association success callback failed", error)
        } }
        next = synchronized(this) { remote ?: next }
        next?.let(::pump)
    }

    @Synchronized
    private fun isCancelled(token: String): Boolean = token in cancelled

    private fun bindAssociationService() {
        val connection = synchronized(this) {
            if (closed || activeConnection != null || binding) return
            AssociationConnection().also {
                activeConnection = it
                binding = true
            }
        }
        val didBind = runCatching {
            val intent = Intent(ACTION).setComponent(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS))
            application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure { error ->
            Log.e(TAG, "Media-association bind failed", error)
        }.getOrDefault(false)

        var release = false
        var retry = false
        synchronized(this) {
            if (activeConnection !== connection) {
                release = didBind
            } else {
                binding = false
                if (closed) {
                    activeConnection = null
                    release = didBind
                } else if (!didBind) {
                    activeConnection = null
                    retry = true
                }
            }
        }
        when {
            release -> unbindQuietly(connection)
            retry -> scheduleRebind(immediate = false)
            else -> armConnectionTimeout(connection)
        }
    }

    private fun clearCurrent(connection: AssociationConnection): Boolean = synchronized(this) {
        if (closed || activeConnection !== connection) return@synchronized false
        remote = null
        connected = false
        true
    }

    private fun invalidate(service: IMediaContextAssociation) {
        val connection = synchronized(this) {
            if (closed || remote !== service) return
            activeConnection
        }
        replaceTerminalBinding(connection, immediate = false)
    }

    private fun replaceTerminalBinding(
        connection: AssociationConnection?,
        immediate: Boolean,
    ) {
        if (connection == null) return
        val replace = synchronized(this) {
            if (closed || activeConnection !== connection) {
                false
            } else {
                remote = null
                connected = false
                activeConnection = null
                binding = false
                true
            }
        }
        if (!replace) return
        main.removeCallbacks(connection.timeout)
        unbindQuietly(connection)
        scheduleRebind(immediate)
    }

    private fun armConnectionTimeout(connection: AssociationConnection) {
        synchronized(this) {
            if (closed || activeConnection !== connection || connected) return
        }
        main.removeCallbacks(connection.timeout)
        main.postDelayed(connection.timeout, CONNECT_TIMEOUT_MILLIS)
    }

    private fun onConnectionTimedOut(connection: AssociationConnection) {
        synchronized(this) {
            if (closed || activeConnection !== connection || connected) return
        }
        replaceTerminalBinding(connection, immediate = false)
    }

    private fun scheduleRebind(immediate: Boolean) {
        val delay = rebindPolicy.reserve(immediate)
        if (delay != MessagingServiceRebindPolicy.NO_RETRY) {
            main.postDelayed(rebind, delay)
        }
    }

    private fun unbindQuietly(connection: AssociationConnection?) {
        if (connection == null) return
        try {
            application.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // A close or replacement can race bindService completion.
        }
    }

    private companion object {
        const val TAG = "AiosMediaAssociation"
        const val ACTION = "com.aios.media.MEDIA_CONTEXT_ASSOCIATION_SERVICE"
        const val SERVICE_PACKAGE = "com.aios.mediaintelligence"
        const val SERVICE_CLASS =
            "com.aios.mediaintelligence.MediaContextAssociationService"
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
        const val MAX_PENDING_OPERATIONS = 128
        const val MAX_CANCELLED_TOKENS = 256
        const val DELETE_PREFIX = "delete:"
        const val CLEAR_KEY = "clear"

        fun stageKey(token: String) = "stage:$token"
        fun completeKey(token: String) = "complete:$token"
        fun cancelKey(token: String) = "cancel:$token"
        fun deleteKey(sourceId: String) = "$DELETE_PREFIX$sourceId"
    }
}

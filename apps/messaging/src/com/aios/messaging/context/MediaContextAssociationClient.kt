package com.aios.messaging.context

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import com.aios.context.ConversationIdentity
import com.aios.media.IMediaContextAssociation
import java.util.LinkedHashSet
import java.util.concurrent.Executors

/** Optional, signature-only client; media context never blocks carrier messaging. */
class MediaContextAssociationClient(context: Context) {
    private val application = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { work ->
        Thread(work, "aios-message-media-context")
    }
    private val pending = ArrayDeque<(IMediaContextAssociation) -> Unit>()
    private val cancelled = LinkedHashSet<String>()
    private var remote: IMediaContextAssociation? = null
    private var bound = false
    private var closed = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = IMediaContextAssociation.Stub.asInterface(binder) ?: return
            val queued = synchronized(this@MediaContextAssociationClient) {
                if (closed) return
                remote = service
                pending.toList().also { pending.clear() }
            }
            queued.forEach { operation -> submit(service, operation) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(this@MediaContextAssociationClient) { remote = null }
        }

        override fun onBindingDied(name: ComponentName?) = restartBinding()

        override fun onNullBinding(name: ComponentName?) = restartBinding()
    }

    @Synchronized
    fun connect() {
        if (closed || bound) return
        val intent = Intent(ACTION).setComponent(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS))
        bound = application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun stageMmsPhoto(
        associationToken: String,
        photoUri: String,
        identity: ConversationIdentity,
        eventAtEpochMillis: Long,
    ) {
        if (photoUri.isBlank() || eventAtEpochMillis <= 0L) return
        withService { service ->
            if (isCancelled(associationToken)) return@withService
            val uri = Uri.parse(photoUri)
            if (uri.scheme != "content") return@withService
            val mimeType = application.contentResolver.getType(uri)
                ?.takeIf { it.startsWith("image/") }
                ?: return@withService
            application.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                service.stageMmsPhoto(
                    associationToken,
                    descriptor,
                    mimeType,
                    identity,
                    eventAtEpochMillis,
                )
            }
        }
    }

    fun completeMmsPhoto(
        associationToken: String,
        providerId: Long,
        eventAtEpochMillis: Long,
        onDurablyRecorded: () -> Unit,
    ) {
        if (providerId <= 0L || eventAtEpochMillis <= 0L || isCancelled(associationToken)) return
        withService { service ->
            if (!isCancelled(associationToken)) {
                service.completeMmsPhoto(
                    associationToken,
                    "mms:$providerId",
                    eventAtEpochMillis,
                )
                onDurablyRecorded()
            }
        }
    }

    fun cancelMmsPhoto(associationToken: String) {
        synchronized(this) {
            if (closed) return
            cancelled += associationToken
            while (cancelled.size > MAX_CANCELLED_TOKENS) {
                cancelled.remove(cancelled.first())
            }
        }
        withService { service -> service.cancelMmsPhoto(associationToken) }
    }

    fun deleteMmsPhoto(providerId: Long) {
        if (providerId <= 0L) return
        withService { service -> service.deleteMmsPhoto("mms:$providerId") }
    }

    fun clearMmsPhotos() {
        withService(IMediaContextAssociation::clearMmsPhotos)
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        pending.clear()
        cancelled.clear()
        remote = null
        if (bound) runCatching { application.unbindService(connection) }
        bound = false
        worker.shutdownNow()
    }

    private fun withService(operation: (IMediaContextAssociation) -> Unit) {
        val service = synchronized(this) {
            if (closed) return
            remote ?: run {
                pending += operation
                while (pending.size > MAX_PENDING_OPERATIONS) pending.removeFirst()
                connect()
                return
            }
        }
        submit(service, operation)
    }

    private fun submit(
        service: IMediaContextAssociation,
        operation: (IMediaContextAssociation) -> Unit,
    ) {
        worker.execute { runCatching { operation(service) } }
    }

    @Synchronized
    private fun isCancelled(token: String): Boolean = token in cancelled

    private fun restartBinding() {
        synchronized(this) {
            remote = null
            if (closed || !bound) return
            runCatching { application.unbindService(connection) }
            bound = false
        }
        connect()
    }

    private companion object {
        const val ACTION = "com.aios.media.MEDIA_CONTEXT_ASSOCIATION_SERVICE"
        const val SERVICE_PACKAGE = "com.aios.mediaintelligence"
        const val SERVICE_CLASS =
            "com.aios.mediaintelligence.MediaContextAssociationService"
        const val MAX_PENDING_OPERATIONS = 128
        const val MAX_CANCELLED_TOKENS = 256
    }
}

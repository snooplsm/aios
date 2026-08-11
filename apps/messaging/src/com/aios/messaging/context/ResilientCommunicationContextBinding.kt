package com.aios.messaging.context

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.aios.context.ICommunicationContext

/** Restartable, generation-safe binding for Messaging's Communication Context client. */
internal class ResilientCommunicationContextBinding(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected(service: ICommunicationContext)
        fun onDisconnected(service: ICommunicationContext)
    }

    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var rebindPolicy = MessagingServiceRebindPolicy()
    private var activeConnection: ContextConnection? = null
    private var rebindTask: Runnable? = null
    private var watchdog: Runnable? = null
    private var remote: ICommunicationContext? = null
    private var generation = 0L
    private var ready = false
    private var started = false

    private inner class ContextConnection(val generation: Long) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            main.post { acceptConnected(this, binder) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            main.post { acceptDisconnected(this) }
        }

        override fun onBindingDied(name: ComponentName?) {
            main.post { terminate(this, expected = null, immediate = true) }
        }

        override fun onNullBinding(name: ComponentName?) {
            main.post { terminate(this, expected = null, immediate = false) }
        }
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (started) return
        started = true
        rebindPolicy = MessagingServiceRebindPolicy()
        scheduleRebind(immediate = true)
    }

    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!started) return
        started = false
        rebindTask?.let(main::removeCallbacks)
        rebindTask = null
        cancelWatchdog()
        rebindPolicy.close()
        val disconnected = remote
        remote = null
        ready = false
        val connection = activeConnection
        activeConnection = null
        disconnected?.let(listener::onDisconnected)
        connection?.let { runCatching { application.unbindService(it) } }
    }

    fun invalidate(expected: ICommunicationContext) {
        main.post {
            val connection = activeConnection ?: return@post
            terminate(connection, expected, immediate = false)
        }
    }

    private fun acceptConnected(connection: ContextConnection, binder: IBinder?) {
        if (!isCurrentGeneration(connection)) return
        val service = ICommunicationContext.Stub.asInterface(binder)
        if (service == null) {
            terminate(connection, expected = null, immediate = false)
            return
        }
        val displaced = remote
        remote = service
        ready = true
        cancelWatchdog()
        rebindPolicy.connected()
        if (displaced != null && displaced !== service) listener.onDisconnected(displaced)
        listener.onConnected(service)
    }

    private fun acceptDisconnected(connection: ContextConnection) {
        if (!isCurrentGeneration(connection)) return
        val disconnected = remote
        remote = null
        ready = false
        disconnected?.let(listener::onDisconnected)
        // Android retains an ordinary crash binding; replace it if reconnect stalls.
        armWatchdog(connection)
    }

    private fun terminate(
        connection: ContextConnection,
        expected: ICommunicationContext?,
        immediate: Boolean,
    ) {
        if (!isCurrentGeneration(connection) ||
            (expected != null && remote !== expected)
        ) return
        cancelWatchdog()
        val disconnected = remote
        remote = null
        ready = false
        activeConnection = null
        disconnected?.let(listener::onDisconnected)
        runCatching { application.unbindService(connection) }
        scheduleRebind(immediate)
    }

    private fun scheduleRebind(immediate: Boolean) {
        if (!started || activeConnection != null) return
        val delay = rebindPolicy.reserve(immediate)
        if (delay == MessagingServiceRebindPolicy.NO_RETRY) return
        lateinit var task: Runnable
        task = Runnable {
            if (rebindTask !== task) return@Runnable
            rebindTask = null
            if (rebindPolicy.begin()) bindNow()
        }
        rebindTask = task
        if (!main.postDelayed(task, delay)) {
            rebindTask = null
            rebindPolicy.close()
        }
    }

    private fun bindNow() {
        if (!started || activeConnection != null) return
        if (generation == Long.MAX_VALUE) return
        val connection = ContextConnection(++generation)
        activeConnection = connection
        ready = false
        val intent = Intent(ACTION).setComponent(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS))
        val bound = runCatching {
            application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            activeConnection = null
            scheduleRebind(immediate = false)
            return
        }
        armWatchdog(connection)
    }

    private fun armWatchdog(connection: ContextConnection) {
        cancelWatchdog()
        if (!isCurrentGeneration(connection) || ready) return
        lateinit var task: Runnable
        task = Runnable {
            if (watchdog !== task) return@Runnable
            watchdog = null
            if (isCurrentGeneration(connection) && !ready) {
                terminate(connection, expected = null, immediate = false)
            }
        }
        watchdog = task
        if (!main.postDelayed(task, CONNECT_TIMEOUT_MILLIS)) {
            watchdog = null
            terminate(connection, expected = null, immediate = false)
        }
    }

    private fun cancelWatchdog() {
        watchdog?.let(main::removeCallbacks)
        watchdog = null
    }

    private fun isCurrentGeneration(connection: ContextConnection): Boolean =
        started && activeConnection === connection && connection.generation == generation

    private companion object {
        const val ACTION = "com.aios.context.COMMUNICATION_CONTEXT_SERVICE"
        const val SERVICE_PACKAGE = "com.aios.contextintelligence"
        const val SERVICE_CLASS =
            "com.aios.contextintelligence.CommunicationContextService"
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
    }
}

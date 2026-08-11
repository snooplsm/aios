package com.aios.callintelligence;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.aios.context.ICommunicationContext;

/** Generation-safe binding for the optional communication-context service. */
final class ResilientCommunicationContextBinding implements AutoCloseable {
    interface Listener {
        void onConnected(ICommunicationContext service);
        void onDisconnected();
    }

    private static final String TAG = "AiosContextBinding";
    private static final String ACTION = "com.aios.context.COMMUNICATION_CONTEXT_SERVICE";
    private static final String PACKAGE = "com.aios.contextintelligence";
    private static final long CONNECT_TIMEOUT_MILLIS = 15_000L;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ServiceRebindPolicy rebindPolicy = new ServiceRebindPolicy();
    private ContextConnection activeConnection;
    private ICommunicationContext service;
    private boolean binding;
    private boolean connected;
    private boolean closed;

    private final Runnable rebind = () -> {
        if (rebindPolicy.begin()) bindService();
    };

    private final class ContextConnection implements ServiceConnection {
        final Runnable timeout = () -> onConnectionTimedOut(this);

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            ICommunicationContext candidate = ICommunicationContext.Stub.asInterface(binder);
            if (candidate == null) {
                replaceTerminalBinding(this, false);
                return;
            }
            synchronized (ResilientCommunicationContextBinding.this) {
                if (closed || activeConnection != this) return;
                service = candidate;
                connected = true;
            }
            mainHandler.removeCallbacks(timeout);
            rebindPolicy.connected();
            notifyConnected(candidate);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Android normally retains a crash binding. Replace it if reconnect stalls.
            if (!clearCurrent(this)) return;
            notifyDisconnected();
            armConnectionTimeout(this);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            replaceTerminalBinding(this, true);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            replaceTerminalBinding(this, false);
        }
    }

    ResilientCommunicationContextBinding(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        synchronized (this) {
            if (closed || activeConnection != null || binding) return;
        }
        scheduleRebind(true);
    }

    synchronized boolean isCurrent(ICommunicationContext candidate) {
        return !closed && connected && service == candidate;
    }

    void invalidate(ICommunicationContext candidate) {
        ContextConnection connection;
        synchronized (this) {
            if (closed || service != candidate) return;
            connection = activeConnection;
        }
        replaceTerminalBinding(connection, false);
    }

    @Override
    public void close() {
        ContextConnection connection;
        boolean notify;
        synchronized (this) {
            if (closed) return;
            closed = true;
            notify = service != null;
            service = null;
            connected = false;
            connection = activeConnection;
            activeConnection = null;
            binding = false;
        }
        rebindPolicy.close();
        mainHandler.removeCallbacks(rebind);
        if (connection != null) mainHandler.removeCallbacks(connection.timeout);
        if (notify) notifyDisconnected();
        unbindQuietly(connection);
    }

    private void bindService() {
        ContextConnection connection = new ContextConnection();
        synchronized (this) {
            if (closed || activeConnection != null || binding) return;
            activeConnection = connection;
            binding = true;
        }
        boolean didBind = false;
        try {
            Intent intent = new Intent(ACTION).setPackage(PACKAGE);
            didBind = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException error) {
            Log.e(TAG, "Communication Context bind failed", error);
        }

        boolean release = false;
        boolean retry = false;
        synchronized (this) {
            if (activeConnection != connection) {
                release = didBind;
            } else {
                binding = false;
                if (closed) {
                    activeConnection = null;
                    release = didBind;
                } else if (!didBind) {
                    activeConnection = null;
                    retry = true;
                }
            }
        }
        if (release) {
            unbindQuietly(connection);
        } else if (retry) {
            scheduleRebind(false);
        } else {
            armConnectionTimeout(connection);
        }
    }

    private boolean clearCurrent(ContextConnection connection) {
        synchronized (this) {
            if (closed || activeConnection != connection) return false;
            service = null;
            connected = false;
            return true;
        }
    }

    private void replaceTerminalBinding(ContextConnection connection, boolean immediate) {
        if (connection == null) return;
        boolean notify;
        synchronized (this) {
            if (closed || activeConnection != connection) return;
            notify = service != null;
            service = null;
            connected = false;
            activeConnection = null;
            binding = false;
        }
        mainHandler.removeCallbacks(connection.timeout);
        if (notify) notifyDisconnected();
        unbindQuietly(connection);
        scheduleRebind(immediate);
    }

    private void armConnectionTimeout(ContextConnection connection) {
        synchronized (this) {
            if (closed || activeConnection != connection || connected) return;
        }
        mainHandler.removeCallbacks(connection.timeout);
        mainHandler.postDelayed(connection.timeout, CONNECT_TIMEOUT_MILLIS);
    }

    private void onConnectionTimedOut(ContextConnection connection) {
        synchronized (this) {
            if (closed || activeConnection != connection || connected) return;
        }
        replaceTerminalBinding(connection, false);
    }

    private void scheduleRebind(boolean immediate) {
        long delay = rebindPolicy.reserve(immediate);
        if (delay != ServiceRebindPolicy.NO_RETRY) {
            mainHandler.postDelayed(rebind, delay);
        }
    }

    private void notifyConnected(ICommunicationContext candidate) {
        try {
            listener.onConnected(candidate);
        } catch (RuntimeException error) {
            Log.e(TAG, "Communication Context connection listener failed", error);
            invalidate(candidate);
        }
    }

    private void notifyDisconnected() {
        try {
            listener.onDisconnected();
        } catch (RuntimeException error) {
            Log.e(TAG, "Communication Context disconnection listener failed", error);
        }
    }

    private void unbindQuietly(ContextConnection connection) {
        if (connection == null) return;
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            // A close or replacement can race bindService completion.
        }
    }
}

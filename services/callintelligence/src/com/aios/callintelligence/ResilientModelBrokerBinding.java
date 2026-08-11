package com.aios.callintelligence;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.aios.model.IAiosModelService;

/** A generation-safe Model Broker binding that replaces terminal and stalled bindings. */
final class ResilientModelBrokerBinding implements AutoCloseable {
    interface Listener {
        void onConnected(IAiosModelService service);
        void onDisconnected();
    }

    private static final String TAG = "AiosBrokerBinding";
    private static final String BROKER_ACTION = "com.aios.model.MODEL_SERVICE";
    private static final String BROKER_PACKAGE = "com.aios.modelbroker";
    private static final long CONNECT_TIMEOUT_MILLIS = 15_000L;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BrokerServiceRebindPolicy rebindPolicy = new BrokerServiceRebindPolicy();
    private BrokerConnection activeConnection;
    private IAiosModelService service;
    private boolean binding;
    private boolean ready;
    private boolean closed;

    private final Runnable rebind = () -> {
        if (rebindPolicy.begin()) bindBroker();
    };

    private final class BrokerConnection implements ServiceConnection {
        final Runnable timeout = () -> onConnectionTimedOut(this);

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            IAiosModelService candidate = IAiosModelService.Stub.asInterface(binder);
            synchronized (ResilientModelBrokerBinding.this) {
                if (closed || activeConnection != this || candidate == null) return;
                service = candidate;
                ready = false;
            }
            notifyConnected(candidate);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Android retains an ordinary crash binding and reconnects it.
            if (!clearCurrent(this)) return;
            notifyDisconnected();
            armConnectionTimeout(this);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            replaceTerminalBinding(this, true, false);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            replaceTerminalBinding(this, false, false);
        }
    }

    ResilientModelBrokerBinding(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        synchronized (this) {
            if (closed || activeConnection != null || binding) return;
        }
        scheduleRebind(true);
    }

    synchronized boolean isCurrent(IAiosModelService candidate) {
        return !closed && service == candidate;
    }

    void markReady(IAiosModelService candidate) {
        BrokerConnection connection;
        synchronized (this) {
            if (closed || service != candidate || activeConnection == null) return;
            ready = true;
            connection = activeConnection;
        }
        mainHandler.removeCallbacks(connection.timeout);
        rebindPolicy.connected();
    }

    void invalidate(IAiosModelService candidate) {
        BrokerConnection connection;
        synchronized (this) {
            if (closed || service != candidate) return;
            connection = activeConnection;
        }
        replaceTerminalBinding(connection, false, false);
    }

    @Override
    public void close() {
        BrokerConnection connection;
        boolean notify;
        synchronized (this) {
            if (closed) return;
            closed = true;
            notify = service != null;
            service = null;
            ready = false;
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

    private void bindBroker() {
        BrokerConnection connection = new BrokerConnection();
        synchronized (this) {
            if (closed || activeConnection != null || binding) return;
            activeConnection = connection;
            binding = true;
        }
        boolean didBind = false;
        try {
            Intent intent = new Intent(BROKER_ACTION).setPackage(BROKER_PACKAGE);
            didBind = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException error) {
            Log.e(TAG, "Model Broker bind failed", error);
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

    private boolean clearCurrent(BrokerConnection connection) {
        synchronized (this) {
            if (closed || activeConnection != connection) return false;
            service = null;
            ready = false;
            return true;
        }
    }

    private void replaceTerminalBinding(
            BrokerConnection connection, boolean immediate, boolean onlyIfNotReady) {
        if (connection == null) return;
        boolean notify;
        synchronized (this) {
            if (closed || activeConnection != connection || (onlyIfNotReady && ready)) {
                return;
            }
            notify = service != null;
            service = null;
            ready = false;
            activeConnection = null;
            binding = false;
        }
        mainHandler.removeCallbacks(connection.timeout);
        if (notify) notifyDisconnected();
        unbindQuietly(connection);
        scheduleRebind(immediate);
    }

    private void armConnectionTimeout(BrokerConnection connection) {
        synchronized (this) {
            if (closed || activeConnection != connection || ready) return;
        }
        mainHandler.removeCallbacks(connection.timeout);
        mainHandler.postDelayed(connection.timeout, CONNECT_TIMEOUT_MILLIS);
    }

    private void onConnectionTimedOut(BrokerConnection connection) {
        synchronized (this) {
            if (closed || activeConnection != connection || ready) return;
        }
        replaceTerminalBinding(connection, false, true);
    }

    private void scheduleRebind(boolean immediate) {
        long delay = rebindPolicy.reserve(immediate);
        if (delay != BrokerServiceRebindPolicy.NO_RETRY) {
            mainHandler.postDelayed(rebind, delay);
        }
    }

    private void notifyConnected(IAiosModelService candidate) {
        try {
            listener.onConnected(candidate);
        } catch (RuntimeException error) {
            Log.e(TAG, "Model Broker connection listener failed", error);
            invalidate(candidate);
        }
    }

    private void notifyDisconnected() {
        try {
            listener.onDisconnected();
        } catch (RuntimeException error) {
            Log.e(TAG, "Model Broker disconnection listener failed", error);
        }
    }

    private void unbindQuietly(BrokerConnection connection) {
        if (connection == null) return;
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            // A close or replacement can race bindService completion.
        }
    }
}

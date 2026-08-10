package com.aios.callintelligence;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import com.aios.context.ContextDocument;
import com.aios.context.ContextSnippet;
import com.aios.context.ConversationIdentity;
import com.aios.context.ICommunicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional, fail-open client for caller history and expiring call-summary indexing. */
final class CallCommunicationContextClient implements AutoCloseable {
    interface Listener {
        void onContextReady(
                String callId, Object requestIdentity, PreparedContext context);
        void onStatus(String callId, String detail);
    }

    static final class PreparedContext {
        final ConversationIdentity identity;
        final String priorContextJson;

        PreparedContext(ConversationIdentity identity, String priorContextJson) {
            this.identity = identity;
            this.priorContextJson = priorContextJson;
        }
    }

    private static final class ResolvedCall {
        final Object requestIdentity;
        final PreparedContext context;

        ResolvedCall(Object requestIdentity, PreparedContext context) {
            this.requestIdentity = requestIdentity;
            this.context = context;
        }
    }

    private static final String ACTION =
            "com.aios.context.COMMUNICATION_CONTEXT_SERVICE";
    private static final String PACKAGE = "com.aios.contextintelligence";
    private static final int MAX_CALL_ID_CHARS = 128;
    private static final int MAX_ADDRESS_CHARS = 256;
    private static final int MAX_ACTIVE_CALLS = 64;

    private final Context context;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "aios-call-context");
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    private ICommunicationContext service;
    private final CallRequestIdentityTracker activeRequests =
            new CallRequestIdentityTracker();
    private final Map<String, ResolvedCall> resolvedCalls = new HashMap<>();
    private boolean bound;
    private boolean closed;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (CallCommunicationContextClient.this) {
                if (!closed) service = ICommunicationContext.Stub.asInterface(binder);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            clearService();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            restartBinding();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            clearService();
        }
    };

    CallCommunicationContextClient(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    synchronized void start() {
        if (closed || bound) return;
        Intent intent = new Intent(ACTION).setPackage(PACKAGE);
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    boolean prepareCall(
            String callId,
            Object requestIdentity,
            String transientAddress,
            String countryIso,
            long nowEpochMillis) {
        ICommunicationContext candidate;
        synchronized (this) {
            candidate = service;
            if (closed || !validCallId(callId) || requestIdentity == null
                    || transientAddress == null
                    || transientAddress.isBlank()
                    || transientAddress.length() > MAX_ADDRESS_CHARS
                    || nowEpochMillis <= 0L) {
                return false;
            }
            if (candidate != null) {
                if (!activeRequests.tryStart(
                        callId, requestIdentity, MAX_ACTIVE_CALLS)) return false;
            }
        }
        if (candidate == null) {
            listener.onStatus(callId, "communication_context_unavailable");
            return false;
        }
        String address = transientAddress;
        String iso = countryIso == null ? "" : countryIso;
        worker.execute(() -> resolveAndQuery(
                candidate, callId, requestIdentity, address, iso, nowEpochMillis));
        return true;
    }

    void indexCallArtifact(
            String callId,
            PreparedContext prepared,
            String sourceId,
            long revision,
            long eventAtEpochMillis,
            long expiresAtEpochMillis,
            String text,
            long nowEpochMillis) {
        Object requestIdentity;
        synchronized (this) {
            if (closed || !validCallId(callId)
                    || sourceId == null || !sourceId.matches("[0-9a-f]{64}")
                    || revision <= 0L || eventAtEpochMillis <= 0L
                    || expiresAtEpochMillis <= eventAtEpochMillis || text == null
                    || text.isBlank() || text.length() > CallContextAccumulator.MAX_DOCUMENT_CHARS) {
                return;
            }
            requestIdentity = activeRequests.current(callId);
        }
        worker.execute(() -> {
            PreparedContext effective;
            ICommunicationContext candidate;
            synchronized (this) {
                ResolvedCall resolved = resolvedCalls.get(callId);
                PreparedContext matchingResolved = resolved != null
                        && resolved.requestIdentity == requestIdentity
                        ? resolved.context : null;
                if (matchingResolved != null) resolvedCalls.remove(callId);
                activeRequests.finish(callId, requestIdentity);
                effective = prepared == null ? matchingResolved : prepared;
                candidate = service;
            }
            if (effective == null || effective.identity == null) {
                listener.onStatus(callId, "call_context_identity_unavailable");
                return;
            }
            long observedNow = Math.max(nowEpochMillis, System.currentTimeMillis());
            if (expiresAtEpochMillis <= observedNow || candidate == null) {
                listener.onStatus(callId, "communication_context_unavailable");
                return;
            }
            try {
                candidate.upsert(new ContextDocument(
                        "call_artifact",
                        sourceId,
                        revision,
                        effective.identity,
                        eventAtEpochMillis,
                        expiresAtEpochMillis,
                        text));
                listener.onStatus(callId, "call_context_indexed");
            } catch (RemoteException | RuntimeException error) {
                listener.onStatus(callId, "call_context_index_failed");
            }
        });
    }

    void discardCall(String callId) {
        synchronized (this) {
            if (closed || !validCallId(callId)) return;
            Object discarded = activeRequests.remove(callId);
            ResolvedCall resolved = resolvedCalls.get(callId);
            if (resolved != null && resolved.requestIdentity == discarded) {
                resolvedCalls.remove(callId);
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            service = null;
            activeRequests.clear();
            resolvedCalls.clear();
            if (bound) {
                context.unbindService(connection);
                bound = false;
            }
        }
        worker.shutdownNow();
    }

    private void resolveAndQuery(
            ICommunicationContext candidate,
            String callId,
            Object requestIdentity,
            String address,
            String countryIso,
            long nowEpochMillis) {
        try {
            ConversationIdentity identity = candidate.resolveIdentity(address, countryIso);
            List<ContextSnippet> snippets = candidate.query(
                    identity, "", PriorContextFormatter.MAX_ITEMS, nowEpochMillis);
            ArrayList<PriorContextFormatter.Item> values = new ArrayList<>();
            if (snippets != null) {
                for (ContextSnippet snippet : snippets) {
                    if (snippet == null) continue;
                    values.add(new PriorContextFormatter.Item(
                            snippet.sourceType, snippet.eventAtEpochMillis, snippet.excerpt));
                }
            }
            PreparedContext prepared = new PreparedContext(
                    identity, PriorContextFormatter.format(values));
            synchronized (this) {
                if (closed || !activeRequests.isCurrent(callId, requestIdentity)) return;
                resolvedCalls.put(callId, new ResolvedCall(requestIdentity, prepared));
            }
            listener.onContextReady(callId, requestIdentity, prepared);
        } catch (RemoteException | RuntimeException error) {
            synchronized (this) {
                if (closed || !activeRequests.isCurrent(callId, requestIdentity)) return;
            }
            listener.onStatus(callId, "communication_context_query_failed");
        }
    }

    private synchronized void clearService() {
        service = null;
    }

    private void restartBinding() {
        synchronized (this) {
            service = null;
            if (closed || !bound) return;
            bound = false;
        }
        try {
            context.unbindService(connection);
        } catch (RuntimeException ignored) {
            // A dead binding may already have been removed by the framework.
        }
        start();
    }

    private static boolean validCallId(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_CALL_ID_CHARS;
    }
}

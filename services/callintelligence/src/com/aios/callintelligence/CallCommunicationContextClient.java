package com.aios.callintelligence;

import android.content.Context;
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

    private static final class PendingPrepare {
        final Object requestIdentity;
        final String address;
        final String countryIso;
        final long nowEpochMillis;

        PendingPrepare(
                Object requestIdentity,
                String address,
                String countryIso,
                long nowEpochMillis) {
            this.requestIdentity = requestIdentity;
            this.address = address;
            this.countryIso = countryIso;
            this.nowEpochMillis = nowEpochMillis;
        }
    }

    private static final class PendingIndex {
        final Object requestIdentity;
        final PreparedContext prepared;
        final String sourceId;
        final long revision;
        final long eventAtEpochMillis;
        final long expiresAtEpochMillis;
        final String expiryBootIdentity;
        final long createdAtElapsedRealtimeMillis;
        final long expiresAtElapsedRealtimeMillis;
        final String text;

        PendingIndex(
                Object requestIdentity,
                PreparedContext prepared,
                String sourceId,
                long revision,
                long eventAtEpochMillis,
                long expiresAtEpochMillis,
                String expiryBootIdentity,
                long createdAtElapsedRealtimeMillis,
                long expiresAtElapsedRealtimeMillis,
                String text) {
            this.requestIdentity = requestIdentity;
            this.prepared = prepared;
            this.sourceId = sourceId;
            this.revision = revision;
            this.eventAtEpochMillis = eventAtEpochMillis;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
            this.expiryBootIdentity = expiryBootIdentity;
            this.createdAtElapsedRealtimeMillis = createdAtElapsedRealtimeMillis;
            this.expiresAtElapsedRealtimeMillis = expiresAtElapsedRealtimeMillis;
            this.text = text;
        }
    }

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
    private final ResilientCommunicationContextBinding binding;
    private ICommunicationContext service;
    private final CallRequestIdentityTracker activeRequests =
            new CallRequestIdentityTracker();
    private final Map<String, PendingPrepare> pendingPrepares = new HashMap<>();
    private final Map<String, Object> preparing = new HashMap<>();
    private final Map<String, ResolvedCall> resolvedCalls = new HashMap<>();
    private final Map<String, PendingIndex> pendingIndexes = new HashMap<>();
    private final Map<String, PendingIndex> indexing = new HashMap<>();
    private boolean closed;

    CallCommunicationContextClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        binding = new ResilientCommunicationContextBinding(
                this.context,
                new ResilientCommunicationContextBinding.Listener() {
                    @Override
                    public void onConnected(ICommunicationContext connected) {
                        handleConnected(connected);
                    }

                    @Override
                    public void onDisconnected() {
                        synchronized (CallCommunicationContextClient.this) {
                            service = null;
                        }
                    }
                });
    }

    void start() {
        binding.start();
    }

    boolean prepareCall(
            String callId,
            Object requestIdentity,
            String transientAddress,
            String countryIso,
            long nowEpochMillis) {
        ICommunicationContext candidate;
        synchronized (this) {
            if (closed || !validCallId(callId) || requestIdentity == null
                    || transientAddress == null
                    || transientAddress.isBlank()
                    || transientAddress.length() > MAX_ADDRESS_CHARS
                    || nowEpochMillis <= 0L
                    || !activeRequests.tryStart(
                            callId, requestIdentity, MAX_ACTIVE_CALLS)) {
                return false;
            }
            pendingPrepares.put(callId, new PendingPrepare(
                    requestIdentity,
                    transientAddress,
                    countryIso == null ? "" : countryIso,
                    nowEpochMillis));
            candidate = service;
        }
        if (candidate == null) {
            listener.onStatus(callId, "communication_context_deferred");
        } else {
            submitPrepare(candidate, callId, requestIdentity);
        }
        return true;
    }

    void indexCallArtifact(
            String callId,
            PreparedContext prepared,
            String sourceId,
            long revision,
            long eventAtEpochMillis,
            long expiresAtEpochMillis,
            String expiryBootIdentity,
            long createdAtElapsedRealtimeMillis,
            long expiresAtElapsedRealtimeMillis,
            String text,
            long nowEpochMillis) {
        PendingIndex pending;
        ICommunicationContext candidate;
        String failure = null;
        synchronized (this) {
            if (closed || !validCallId(callId)
                    || sourceId == null || !sourceId.matches("[0-9a-f]{64}")
                    || revision <= 0L || eventAtEpochMillis <= 0L
                    || expiresAtEpochMillis <= eventAtEpochMillis
                    || expiryBootIdentity == null || expiryBootIdentity.isBlank()
                    || createdAtElapsedRealtimeMillis < 0L
                    || expiresAtElapsedRealtimeMillis <= 0L || text == null
                    || text.isBlank() || text.length() > CallContextAccumulator.MAX_DOCUMENT_CHARS) {
                return;
            }
            Object requestIdentity = activeRequests.current(callId);
            ResolvedCall resolved = resolvedCalls.get(callId);
            PreparedContext matchingResolved = resolved != null
                    && resolved.requestIdentity == requestIdentity
                    ? resolved.context : null;
            PreparedContext effective = prepared == null ? matchingResolved : prepared;
            pending = new PendingIndex(
                    requestIdentity,
                    effective,
                    sourceId,
                    revision,
                    eventAtEpochMillis,
                    expiresAtEpochMillis,
                    expiryBootIdentity,
                    createdAtElapsedRealtimeMillis,
                    expiresAtElapsedRealtimeMillis,
                    text);
            if (requestIdentity == null || effective == null) {
                finishCallLocked(callId, requestIdentity);
                failure = "call_context_identity_unavailable";
                candidate = null;
            } else if (isExpired(pending, Math.max(
                    nowEpochMillis, System.currentTimeMillis()))) {
                finishCallLocked(callId, requestIdentity);
                failure = "call_context_index_expired";
                candidate = null;
            } else {
                pendingIndexes.put(callId, pending);
                candidate = service;
            }
        }
        if (failure != null) {
            listener.onStatus(callId, failure);
        } else if (candidate == null) {
            listener.onStatus(callId, "call_context_index_deferred");
        } else {
            submitIndex(candidate, callId, pending);
        }
    }

    void discardCall(String callId) {
        synchronized (this) {
            if (closed || !validCallId(callId)) return;
            finishCallLocked(callId, activeRequests.current(callId));
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            service = null;
            activeRequests.clear();
            pendingPrepares.clear();
            preparing.clear();
            resolvedCalls.clear();
            pendingIndexes.clear();
            indexing.clear();
        }
        binding.close();
        worker.shutdownNow();
    }

    private void handleConnected(ICommunicationContext connected) {
        List<Map.Entry<String, PendingPrepare>> prepares;
        List<Map.Entry<String, PendingIndex>> indexes;
        synchronized (this) {
            if (closed || !binding.isCurrent(connected)) return;
            service = connected;
            prepares = new ArrayList<>(pendingPrepares.entrySet());
            indexes = new ArrayList<>(pendingIndexes.entrySet());
        }
        for (Map.Entry<String, PendingPrepare> entry : prepares) {
            submitPrepare(connected, entry.getKey(), entry.getValue().requestIdentity);
        }
        for (Map.Entry<String, PendingIndex> entry : indexes) {
            if (entry.getValue().prepared != null) {
                submitIndex(connected, entry.getKey(), entry.getValue());
            }
        }
    }

    private void submitPrepare(
            ICommunicationContext candidate, String callId, Object requestIdentity) {
        synchronized (this) {
            PendingPrepare pending = pendingPrepares.get(callId);
            if (closed || !binding.isCurrent(candidate) || pending == null
                    || pending.requestIdentity != requestIdentity
                    || !activeRequests.isCurrent(callId, requestIdentity)
                    || preparing.containsKey(callId)) {
                return;
            }
            preparing.put(callId, requestIdentity);
        }
        worker.execute(() -> resolveAndQuery(candidate, callId, requestIdentity));
    }

    private void resolveAndQuery(
            ICommunicationContext candidate, String callId, Object requestIdentity) {
        PendingPrepare pending;
        synchronized (this) {
            pending = pendingPrepares.get(callId);
            if (closed || pending == null || pending.requestIdentity != requestIdentity
                    || !activeRequests.isCurrent(callId, requestIdentity)) {
                preparing.remove(callId, requestIdentity);
                return;
            }
        }
        try {
            ConversationIdentity identity = candidate.resolveIdentity(
                    pending.address, pending.countryIso);
            List<ContextSnippet> snippets = candidate.query(
                    identity, "", PriorContextFormatter.MAX_ITEMS, pending.nowEpochMillis);
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
                preparing.remove(callId, requestIdentity);
                if (closed || !binding.isCurrent(candidate)
                        || !activeRequests.isCurrent(callId, requestIdentity)
                        || pendingPrepares.get(callId) != pending) {
                    retryPrepareIfConnectedLocked(callId, requestIdentity);
                    return;
                }
                pendingPrepares.remove(callId);
                resolvedCalls.put(callId, new ResolvedCall(requestIdentity, prepared));
            }
            listener.onContextReady(callId, requestIdentity, prepared);
        } catch (RemoteException error) {
            boolean report;
            synchronized (this) {
                preparing.remove(callId, requestIdentity);
                report = activeRequests.isCurrent(callId, requestIdentity)
                        && pendingPrepares.get(callId) == pending;
            }
            if (report) {
                listener.onStatus(callId, "communication_context_query_deferred");
            }
            binding.invalidate(candidate);
            retryPrepareIfConnected(callId, requestIdentity);
        } catch (RuntimeException error) {
            boolean report = false;
            boolean retry = false;
            synchronized (this) {
                preparing.remove(callId, requestIdentity);
                if (!binding.isCurrent(candidate)) {
                    retry = true;
                } else if (activeRequests.isCurrent(callId, requestIdentity)
                        && pendingPrepares.get(callId) == pending) {
                    pendingPrepares.remove(callId);
                    finishCallLocked(callId, requestIdentity);
                    report = true;
                }
            }
            if (report) {
                listener.onStatus(callId, "communication_context_query_failed");
            } else if (retry) {
                retryPrepareIfConnected(callId, requestIdentity);
            }
        }
    }

    private void submitIndex(
            ICommunicationContext candidate, String callId, PendingIndex pending) {
        synchronized (this) {
            if (closed || !binding.isCurrent(candidate)
                    || pending.prepared == null
                    || pendingIndexes.get(callId) != pending
                    || indexing.containsKey(callId)) {
                return;
            }
            indexing.put(callId, pending);
        }
        worker.execute(() -> performIndex(candidate, callId, pending));
    }

    private void performIndex(
            ICommunicationContext candidate, String callId, PendingIndex pending) {
        long observedNow = System.currentTimeMillis();
        if (isExpired(pending, observedNow)) {
            synchronized (this) {
                indexing.remove(callId, pending);
                if (pendingIndexes.get(callId) == pending) {
                    finishCallLocked(callId, pending.requestIdentity);
                }
            }
            listener.onStatus(callId, "call_context_index_expired");
            return;
        }
        try {
            candidate.upsert(new ContextDocument(
                    "call_artifact",
                    pending.sourceId,
                    pending.revision,
                    pending.prepared.identity,
                    pending.eventAtEpochMillis,
                    pending.expiresAtEpochMillis,
                    pending.expiryBootIdentity,
                    pending.createdAtElapsedRealtimeMillis,
                    pending.expiresAtElapsedRealtimeMillis,
                    pending.text));
            synchronized (this) {
                indexing.remove(callId, pending);
                if (pendingIndexes.get(callId) != pending) return;
                finishCallLocked(callId, pending.requestIdentity);
            }
            listener.onStatus(callId, "call_context_indexed");
        } catch (RemoteException error) {
            boolean report;
            synchronized (this) {
                indexing.remove(callId, pending);
                report = pendingIndexes.get(callId) == pending;
            }
            if (report) listener.onStatus(callId, "call_context_index_deferred");
            binding.invalidate(candidate);
            retryIndexIfConnected(callId, pending);
        } catch (RuntimeException error) {
            boolean report = false;
            boolean retry = false;
            synchronized (this) {
                indexing.remove(callId, pending);
                if (!binding.isCurrent(candidate)) {
                    retry = true;
                } else if (pendingIndexes.get(callId) == pending) {
                    finishCallLocked(callId, pending.requestIdentity);
                    report = true;
                }
            }
            if (report) {
                listener.onStatus(callId, "call_context_index_failed");
            } else if (retry) {
                retryIndexIfConnected(callId, pending);
            }
        }
    }

    private void retryPrepareIfConnected(String callId, Object requestIdentity) {
        ICommunicationContext candidate;
        synchronized (this) {
            candidate = service;
        }
        if (candidate != null) submitPrepare(candidate, callId, requestIdentity);
    }

    private void retryPrepareIfConnectedLocked(String callId, Object requestIdentity) {
        ICommunicationContext candidate = service;
        if (candidate != null) {
            worker.execute(() -> submitPrepare(candidate, callId, requestIdentity));
        }
    }

    private void retryIndexIfConnected(String callId, PendingIndex pending) {
        ICommunicationContext candidate;
        synchronized (this) {
            candidate = service;
        }
        if (candidate != null) submitIndex(candidate, callId, pending);
    }

    private boolean isExpired(PendingIndex pending, long nowEpochMillis) {
        RetentionClock.Snapshot now = RetentionClock.capture(context, nowEpochMillis);
        return CallArtifactRetention.isExpired(
                new CallArtifactRetention.Deadline(
                        pending.expiryBootIdentity,
                        pending.eventAtEpochMillis,
                        pending.expiresAtEpochMillis,
                        pending.createdAtElapsedRealtimeMillis,
                        pending.expiresAtElapsedRealtimeMillis),
                now.bootIdentity,
                now.epochMillis,
                now.elapsedRealtimeMillis);
    }

    private void finishCallLocked(String callId, Object requestIdentity) {
        if (requestIdentity != null) activeRequests.finish(callId, requestIdentity);
        pendingPrepares.remove(callId);
        preparing.remove(callId);
        resolvedCalls.remove(callId);
        pendingIndexes.remove(callId);
        indexing.remove(callId);
    }

    private static boolean validCallId(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_CALL_ID_CHARS;
    }
}

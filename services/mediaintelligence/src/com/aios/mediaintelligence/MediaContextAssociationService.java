package com.aios.mediaintelligence;

import android.app.Service;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.aios.context.ContextDocument;
import com.aios.context.ConversationIdentity;
import com.aios.context.ICommunicationContext;
import com.aios.media.IMediaContextAssociation;

import org.json.JSONException;

import java.io.IOException;
import java.util.List;

/**
 * Durable, signature-only bridge between an owner-selected MMS photo and its indexed media result.
 * Draft selection and carrier submission only stage bytes; publication requires carrier completion.
 */
public final class MediaContextAssociationService extends Service {
    static final String ACTION = "com.aios.media.MEDIA_CONTEXT_ASSOCIATION_SERVICE";
    private static final String ACTION_RECONCILE =
            "com.aios.media.RECONCILE_MEDIA_CONTEXT_ASSOCIATIONS";
    private static final String MESSAGING_PACKAGE = "com.aios.messaging";
    private static final String CONTEXT_ACTION =
            "com.aios.context.COMMUNICATION_CONTEXT_SERVICE";
    private static final String CONTEXT_PACKAGE = "com.aios.contextintelligence";
    private static final String SOURCE_TYPE = "media_metadata";
    private static final String TAG = "AiosMediaContext";
    private static final int PAGE_SIZE = 128;
    private static final long RETRY_MILLIS = 30_000L;
    private static final String PREFS = "media_context_association";
    private static final String STORE_INSTANCE = "store_instance";

    private HandlerThread thread;
    private Handler worker;
    private MediaJobStore store;
    private ICommunicationContext contextService;
    private boolean contextBound;
    private boolean shuttingDown;

    private final Runnable reconcileRunnable = this::reconcile;

    private final ServiceConnection contextConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            ICommunicationContext candidate = ICommunicationContext.Stub.asInterface(binder);
            if (worker != null) {
                worker.post(() -> {
                    contextService = candidate;
                    scheduleReconcile(0L);
                });
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (worker != null) worker.post(() -> contextService = null);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            if (worker != null) worker.post(MediaContextAssociationService.this::restartBinding);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            if (worker != null) worker.post(MediaContextAssociationService.this::restartBinding);
        }
    };

    private final IMediaContextAssociation.Stub binder = new IMediaContextAssociation.Stub() {
        @Override
        public void stageMmsPhoto(
                String associationToken,
                ParcelFileDescriptor photo,
                String mimeType,
                ConversationIdentity identity,
                long eventAtEpochMillis) {
            enforceMessagingCaller(true);
            if (photo == null || identity == null) {
                throw new IllegalArgumentException("photo and identity are required");
            }
            MediaAssociationPolicy.validateStage(
                    associationToken,
                    mimeType,
                    identity.conversationKey,
                    identity.contactKey,
                    identity.relatedConversationKeys,
                    eventAtEpochMillis);
            ParcelFileDescriptor owned;
            try {
                owned = ParcelFileDescriptor.dup(photo.getFileDescriptor());
            } catch (IOException error) {
                throw new IllegalArgumentException("selected photo descriptor is unavailable", error);
            } finally {
                try {
                    photo.close();
                } catch (IOException ignored) {
                    // The duplicated descriptor, if created, is independently owned.
                }
            }
            long identityToken = Binder.clearCallingIdentity();
            try {
                postOrClose(owned, () -> stage(
                        associationToken, owned, mimeType, identity, eventAtEpochMillis));
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
        }

        @Override
        public void completeMmsPhoto(
                String associationToken, String sourceId, long eventAtEpochMillis) {
            enforceMessagingCaller(true);
            MediaAssociationPolicy.validateToken(associationToken);
            MediaAssociationPolicy.validateSourceId(sourceId);
            if (eventAtEpochMillis <= 0L) {
                throw new IllegalArgumentException("invalid MMS completion time");
            }
            long identityToken = Binder.clearCallingIdentity();
            try {
                // Return only after carrier-completion admission is durable. Messaging keeps
                // its successful operation replayable until this Binder call succeeds.
                store.completeMmsPhoto(
                        associationToken, sourceId, eventAtEpochMillis, System.currentTimeMillis());
                scheduleReconcile(0L);
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
        }

        @Override
        public void cancelMmsPhoto(String associationToken) {
            enforceMessagingCaller(false);
            MediaAssociationPolicy.validateToken(associationToken);
            post(() -> {
                store.cancelMmsPhoto(associationToken);
                scheduleReconcile(0L);
            });
        }

        @Override
        public void deleteMmsPhoto(String sourceId) {
            enforceMessagingCaller(false);
            MediaAssociationPolicy.validateSourceId(sourceId);
            post(() -> {
                store.requestDeleteMmsPhoto(sourceId, System.currentTimeMillis());
                scheduleReconcile(0L);
            });
        }

        @Override
        public void clearMmsPhotos() {
            enforceMessagingCaller(false);
            post(() -> {
                store.requestClearMmsPhotos();
                scheduleReconcile(0L);
            });
        }
    };

    static void requestReconcile(Context context) {
        Intent intent = new Intent(context, MediaContextAssociationService.class)
                .setAction(ACTION_RECONCILE);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new MediaJobStore(this);
        initializeStoreLifecycle();
        thread = new HandlerThread("aios-media-context");
        thread.start();
        worker = new Handler(thread.getLooper());
        bindContext();
        scheduleReconcile(0L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        scheduleReconcile(0L);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return intent != null && ACTION.equals(intent.getAction()) ? binder : null;
    }

    @Override
    public void onDestroy() {
        shuttingDown = true;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (contextBound) {
            runCatchingUnbind();
            contextBound = false;
        }
        contextService = null;
        if (store != null) store.close();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    private void stage(
            String token,
            ParcelFileDescriptor photo,
            String mimeType,
            ConversationIdentity identity,
            long eventAtEpochMillis) {
        try (ParcelFileDescriptor.AutoCloseInputStream stream =
                     new ParcelFileDescriptor.AutoCloseInputStream(photo)) {
            MediaAssociationPolicy.validateStage(
                    token,
                    mimeType,
                    identity.conversationKey,
                    identity.contactKey,
                    identity.relatedConversationKeys,
                    eventAtEpochMillis);
            String digest = MediaAssociationPolicy.sha256(stream);
            store.stageMmsPhoto(
                    token, digest, identity, eventAtEpochMillis, System.currentTimeMillis());
            scheduleReconcile(0L);
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "selected photo could not be staged", error);
        }
    }

    private void initializeStoreLifecycle() {
        String instance = store.associationInstanceId();
        String previous = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(STORE_INSTANCE, null);
        if (!instance.equals(previous)) {
            store.requestClearMmsPhotos();
            boolean committed = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(STORE_INSTANCE, instance).commit();
            if (!committed) {
                throw new IllegalStateException("cannot persist media-context store instance");
            }
        }
    }

    private void reconcile() {
        if (shuttingDown) return;
        try {
            long now = System.currentTimeMillis();
            store.expireIncompleteAssociations(
                    Math.max(1L, now - MediaAssociationPolicy.INCOMPLETE_TTL_MILLIS));
            ICommunicationContext remote = contextService;
            if (remote == null) {
                bindContext();
                scheduleReconcile(RETRY_MILLIS);
                return;
            }
            if (store.clearMmsPhotosPending()) {
                long revision = store.nextAssociationRevision(now);
                long watermark = remote.deleteSourceType(SOURCE_TYPE, revision);
                store.completeAssociationClear(watermark);
            }

            String storeInstance = remote.getStoreInstanceId();
            if (storeInstance == null || !storeInstance.matches("[0-9a-f]{32}")) {
                throw new IllegalStateException("communication-context instance is invalid");
            }

            List<MediaJobStore.PendingDeletion> deletions =
                    store.associationDeletionBatch(PAGE_SIZE);
            for (MediaJobStore.PendingDeletion deletion : deletions) {
                long revision = store.nextAssociationRevision(System.currentTimeMillis());
                remote.deleteSource(SOURCE_TYPE, deletion.sourceId, revision);
                store.completeAssociationDeletion(deletion.token);
            }

            List<MediaJobStore.ReadyAssociation> ready =
                    store.readyAssociationBatch(storeInstance, PAGE_SIZE);
            for (MediaJobStore.ReadyAssociation association : ready) {
                publish(remote, storeInstance, association);
            }
            if (deletions.size() == PAGE_SIZE || ready.size() == PAGE_SIZE) {
                scheduleReconcile(0L);
            }
        } catch (RemoteException error) {
            Log.w(TAG, "communication-context service disconnected", error);
            restartBinding();
            scheduleReconcile(RETRY_MILLIS);
        } catch (RuntimeException error) {
            Log.w(TAG, "media-context reconciliation deferred", error);
            scheduleReconcile(RETRY_MILLIS);
        }
    }

    private void publish(
            ICommunicationContext remote,
            String storeInstance,
            MediaJobStore.ReadyAssociation association) throws RemoteException {
        try {
            MediaAssociationPolicy.validateSourceId(association.sourceId);
            MediaAssociationPolicy.validateIdentity(
                    association.conversationKey,
                    association.contactKey,
                    association.relatedKeys);
            String text = MediaContextProjection.project(association.resultJson);
            long revision = store.nextAssociationRevision(System.currentTimeMillis());
            remote.upsert(new ContextDocument(
                    SOURCE_TYPE,
                    association.sourceId,
                    revision,
                    new ConversationIdentity(
                            association.conversationKey,
                            association.contactKey,
                            association.relatedKeys),
                    association.eventAtEpochMillis,
                    0L,
                    text));
            store.markAssociationPublished(
                    association.token,
                    association.jobId,
                    association.mediaUri,
                    revision,
                    storeInstance);
        } catch (JSONException | IllegalArgumentException error) {
            Log.e(TAG, "discarding invalid media-context association", error);
            store.requestDeleteMmsPhoto(association.sourceId, System.currentTimeMillis());
        }
    }

    private void enforceMessagingCaller(boolean requireRole) {
        int uid = Binder.getCallingUid();
        PackageManager packages = getPackageManager();
        String[] names = packages.getPackagesForUid(uid);
        if (names == null || names.length != 1 || !MESSAGING_PACKAGE.equals(names[0])) {
            throw new SecurityException("media-context caller is not AIOS Messaging");
        }
        if (!requireRole) return;
        long token = Binder.clearCallingIdentity();
        try {
            Context messagingContext;
            try {
                messagingContext = createPackageContext(MESSAGING_PACKAGE, 0);
            } catch (PackageManager.NameNotFoundException error) {
                throw new SecurityException("AIOS Messaging is not installed", error);
            }
            RoleManager roles = messagingContext.getSystemService(RoleManager.class);
            if (roles == null || !roles.isRoleHeld(RoleManager.ROLE_SMS)) {
                throw new SecurityException("AIOS Messaging does not hold the SMS role");
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void post(Runnable operation) {
        Handler candidate = worker;
        if (candidate == null || shuttingDown || !candidate.post(() -> {
            try {
                operation.run();
            } catch (RuntimeException error) {
                Log.w(TAG, "media-association operation failed", error);
            }
        })) {
            throw new IllegalStateException("media-context service is unavailable");
        }
    }

    private void postOrClose(ParcelFileDescriptor descriptor, Runnable operation) {
        try {
            post(operation);
        } catch (RuntimeException error) {
            try {
                descriptor.close();
            } catch (IOException ignored) {
                // The service is already unavailable.
            }
            throw error;
        }
    }

    private void scheduleReconcile(long delayMillis) {
        Handler candidate = worker;
        if (candidate == null || shuttingDown) return;
        candidate.removeCallbacks(reconcileRunnable);
        if (delayMillis <= 0L) candidate.post(reconcileRunnable);
        else candidate.postDelayed(reconcileRunnable, delayMillis);
    }

    private void bindContext() {
        if (contextBound || shuttingDown) return;
        Intent intent = new Intent(CONTEXT_ACTION).setPackage(CONTEXT_PACKAGE);
        contextBound = bindService(intent, contextConnection, Context.BIND_AUTO_CREATE);
    }

    private void restartBinding() {
        contextService = null;
        if (contextBound) runCatchingUnbind();
        contextBound = false;
        bindContext();
    }

    private void runCatchingUnbind() {
        try {
            unbindService(contextConnection);
        } catch (RuntimeException ignored) {
            // A dead binding may already have been removed by the framework.
        }
    }
}

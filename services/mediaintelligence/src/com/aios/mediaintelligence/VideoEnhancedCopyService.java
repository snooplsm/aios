package com.aios.mediaintelligence;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Foreground owner-requested export of a verified, no-reencode enhanced MP4 copy. */
public final class VideoEnhancedCopyService extends Service {
    private static final String TAG = "AiosVideoExport";
    private static final String ACTION_CREATE =
            "com.aios.mediaintelligence.action.CREATE_ENHANCED_VIDEO";
    private static final String EXTRA_SOURCE = "source";
    private static final String CHANNEL_ID = "aios_video_export";
    private static final int NOTIFICATION_ID = 0x41494f53;
    private static final long SUPPRESSION_MILLIS = 24L * 60L * 60L * 1_000L;
    private static final Pattern VOLUME_NAME = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private ExecutorService executor;
    private NotificationManager notifications;
    private final AtomicInteger pendingExports = new AtomicInteger();

    static Intent request(Context context, Uri source) {
        return new Intent(context, VideoEnhancedCopyService.class)
                .setAction(ACTION_CREATE)
                .putExtra(EXTRA_SOURCE, source);
    }

    static boolean isCanonicalVideoUri(Uri uri) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                || !MediaStore.AUTHORITY.equals(uri.getAuthority())) {
            return false;
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 4 || !VOLUME_NAME.matcher(segments.get(0)).matches()
                || !"video".equals(segments.get(1)) || !"media".equals(segments.get(2))) {
            return false;
        }
        try {
            return Long.parseLong(segments.get(3)) > 0L;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "aios-video-export");
            thread.setDaemon(false);
            return thread;
        });
        notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID,
                "AI-enhanced video copies",
                NotificationManager.IMPORTANCE_LOW));
        executor.execute(() -> {
            try (MediaJobStore store = new MediaJobStore(this)) {
                VideoExportRecovery.recover(this, store);
            } catch (RuntimeException error) {
                Log.w(TAG, "cannot run enhanced-video startup recovery", error);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, progressNotification(0));
        Uri source = intent == null ? null : intent.getParcelableExtra(EXTRA_SOURCE, Uri.class);
        if (intent == null || !ACTION_CREATE.equals(intent.getAction())
                || !isCanonicalVideoUri(source)) {
            publishFailure();
            if (pendingExports.get() == 0) {
                stopForeground(STOP_FOREGROUND_DETACH);
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        pendingExports.incrementAndGet();
        executor.execute(() -> export(source, startId));
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTimeout(int startId, int foregroundServiceType) {
        Log.w(TAG, "enhanced-video foreground service timed out");
        publishFailure();
        stopSelf(startId);
    }

    @Override
    public void onDestroy() {
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    private void export(Uri source, int startId) {
        synchronized (VideoExportRecovery.LOCK) {
            exportLocked(source, startId);
        }
    }

    private void exportLocked(Uri source, int startId) {
        MediaJobStore store = new MediaJobStore(this);
        Uri output = null;
        String exportToken = UUID.randomUUID().toString();
        try {
            ContentResolver resolver = getContentResolver();
            SourceInfo sourceInfo = querySource(resolver, source);
            VideoEmbeddedMetadata.Data data =
                    store.videoExportData(source.toString(), sourceInfo.generation);
            if (data == null) {
                throw new IOException("video has not completed AIOS processing");
            }
            if (!data.sourceDigest.equals(MediaContent.sha256(resolver, source))
                    || MediaContent.generation(resolver, source) != sourceInfo.generation) {
                throw new IOException("source video changed after AIOS processing");
            }

            store.beginVideoExport(
                    exportToken,
                    source.toString(),
                    sourceInfo.generation,
                    sourceInfo.volumeName,
                    System.currentTimeMillis());
            output = insertPendingOutput(resolver, sourceInfo, exportToken);
            store.attachVideoExportOutput(exportToken, output.toString());
            long expires = Math.addExact(System.currentTimeMillis(), SUPPRESSION_MILLIS);
            store.beginOwnMutation(output.toString(), expires);
            try (ParcelFileDescriptor sourceDescriptor =
                         requireDescriptor(resolver, source, "r");
                 ParcelFileDescriptor outputDescriptor =
                         requireDescriptor(resolver, output, "rwt")) {
                VideoEnhancedCopyMuxer.create(
                        sourceDescriptor,
                        outputDescriptor,
                        data,
                        percent -> {
                            if (Thread.currentThread().isInterrupted()) {
                                throw new IllegalStateException("enhanced-video export cancelled");
                            }
                            notifications.notify(
                                    NOTIFICATION_ID, progressNotification(percent));
                        });
            }
            if (Thread.currentThread().isInterrupted()
                    || MediaContent.generation(resolver, source) != sourceInfo.generation) {
                throw new IOException("source video changed during export");
            }
            ContentValues publish = new ContentValues();
            publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
            publish.putNull(MediaStore.Video.VideoColumns.DESCRIPTION);
            if (resolver.update(output, publish, VideoExportRecovery.includePending()) != 1) {
                throw new IOException("cannot publish enhanced MP4");
            }
            long outputGeneration = MediaContent.generation(resolver, output);
            store.finishOwnMutation(output.toString(), outputGeneration, expires);
            store.clearVideoExportJournal(exportToken);
            Uri published = output;
            output = null;
            publishSuccess(published);
        } catch (Exception error) {
            Log.e(TAG, "cannot create enhanced MP4", error);
            boolean published = VideoExportRecovery.recoverToken(this, store, exportToken);
            if (published && output != null) {
                publishSuccess(output);
                output = null;
            } else {
                publishFailure();
            }
        } finally {
            store.close();
            if (pendingExports.decrementAndGet() == 0) {
                stopForeground(STOP_FOREGROUND_DETACH);
            }
            stopSelfResult(startId);
        }
    }

    private SourceInfo querySource(ContentResolver resolver, Uri source) throws IOException {
        String volumeName = source.getPathSegments().get(0);
        Set<String> mountedVolumes = MediaGenerationScanner.externalVolumes(this);
        String outputVolume = MediaStore.VOLUME_EXTERNAL.equals(volumeName)
                ? MediaStore.VOLUME_EXTERNAL_PRIMARY : volumeName;
        if (!mountedVolumes.contains(outputVolume)) {
            throw new FileNotFoundException("source MediaStore volume is unavailable");
        }
        try (Cursor cursor = resolver.query(
                source,
                new String[]{
                        MediaStore.MediaColumns.GENERATION_MODIFIED,
                        MediaStore.MediaColumns.IS_PENDING,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.DATE_TAKEN
                },
                null,
                null,
                null)) {
            if (cursor == null || !cursor.moveToFirst() || cursor.getInt(1) != 0
                    || !"video/mp4".equalsIgnoreCase(cursor.getString(2))) {
                throw new FileNotFoundException("source is not a completed MP4");
            }
            return new SourceInfo(
                    cursor.getLong(0),
                    outputVolume,
                    outputDisplayName(cursor.getString(3)),
                    cursor.isNull(4) ? null : cursor.getLong(4));
        } catch (RuntimeException error) {
            throw new IOException("cannot inspect source MP4", error);
        }
    }

    private Uri insertPendingOutput(
            ContentResolver resolver, SourceInfo source, String exportToken)
            throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, source.outputDisplayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        values.put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/AIOS");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        values.put(MediaStore.Video.VideoColumns.DESCRIPTION,
                VideoExportRecovery.marker(exportToken));
        if (source.dateTaken != null) {
            values.put(MediaStore.MediaColumns.DATE_TAKEN, source.dateTaken);
        }
        try {
            Uri result = resolver.insert(
                    MediaStore.Video.Media.getContentUri(source.volumeName), values);
            if (result == null) throw new IOException("MediaStore did not create an output");
            return result;
        } catch (RuntimeException error) {
            throw new IOException("cannot create pending enhanced MP4", error);
        }
    }

    private static ParcelFileDescriptor requireDescriptor(
            ContentResolver resolver, Uri uri, String mode) throws IOException {
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, mode);
        if (descriptor == null) throw new FileNotFoundException("MP4 descriptor unavailable");
        return descriptor;
    }

    private static String outputDisplayName(String sourceName) {
        String base = sourceName == null ? "Video" : sourceName.trim();
        if (base.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            base = base.substring(0, base.length() - 4).trim();
        }
        base = base.replaceAll("[\\x00-\\x1f/\\\\]", "_");
        while (base.endsWith(".") || base.endsWith(" ")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isBlank()) base = "Video";
        if (base.length() > 160) base = base.substring(0, 160).trim();
        return base + " - AIOS.mp4";
    }

    private Notification progressNotification(int percent) {
        int bounded = Math.max(0, Math.min(100, percent));
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Creating AI-enhanced video")
                .setContentText(bounded + "% complete")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, bounded, false)
                .build();
    }

    private void publishSuccess(Uri output) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(output, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri("AI-enhanced video", output));
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                view,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        notifications.notify(
                NOTIFICATION_ID,
                new Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                        .setContentTitle("AI-enhanced video ready")
                        .setContentText("Saved in Movies/AIOS")
                        .setContentIntent(contentIntent)
                        .setAutoCancel(true)
                        .build());
    }

    private void publishFailure() {
        notifications.notify(
                NOTIFICATION_ID,
                new Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setContentTitle("AI-enhanced copy failed")
                        .setContentText("The original video was not changed")
                        .setAutoCancel(true)
                        .build());
    }

    private static final class SourceInfo {
        final long generation;
        final String volumeName;
        final String outputDisplayName;
        final Long dateTaken;

        SourceInfo(
                long generation, String volumeName, String outputDisplayName, Long dateTaken) {
            this.generation = generation;
            this.volumeName = volumeName;
            this.outputDisplayName = outputDisplayName;
            this.dateTaken = dateTaken;
        }
    }
}

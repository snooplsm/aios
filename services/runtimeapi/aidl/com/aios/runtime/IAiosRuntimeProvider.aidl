package com.aios.runtime;

import android.os.ParcelFileDescriptor;
import com.aios.model.AudioStreamFormat;
import com.aios.model.IModelCallback;
import com.aios.model.ModelRequest;
import com.aios.runtime.RuntimeArtifact;

/** Private transport between Model Broker and an isolated runtime APK. */
interface IAiosRuntimeProvider {
    int getProviderApiVersion();
    String getRuntimeId();
    String getImplementationVersion();
    String[] getSupportedBackends();

    long createSession(
        in RuntimeArtifact artifact,
        in ModelRequest request,
        in IModelCallback callback
    );

    void submitText(long sessionId, String text, boolean endOfInput);

    void submitAudio(
        long sessionId,
        in ParcelFileDescriptor pcmStream,
        in AudioStreamFormat format,
        boolean endOfInput
    );

    void attachAudioOutput(
        long sessionId,
        in ParcelFileDescriptor pcmSink,
        in AudioStreamFormat format
    );

    void submitMedia(
        long sessionId,
        in ParcelFileDescriptor media,
        String mimeType,
        boolean endOfInput
    );

    void cancel(long sessionId);
}

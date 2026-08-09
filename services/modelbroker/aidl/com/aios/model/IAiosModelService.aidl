package com.aios.model;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import com.aios.model.AudioStreamFormat;
import com.aios.model.IModelCallback;
import com.aios.model.ModelCapability;
import com.aios.model.ModelRequest;

interface IAiosModelService {
    List<ModelCapability> listCapabilities();

    /**
     * Acquire or release call priority for a client-owned lifecycle token.
     * The broker automatically releases the lease if the client process dies.
     */
    void setCallActive(in IBinder lifecycleToken, boolean active);

    long createSession(in ModelRequest request, in IModelCallback callback);

    void submitText(long sessionId, String text, boolean endOfInput);

    void submitAudio(
        long sessionId,
        in ParcelFileDescriptor pcmStream,
        in AudioStreamFormat format,
        boolean endOfInput
    );

    /**
     * Attach the bounded writable end of a PCM pipe for a speech-synthesis
     * session. The caller owns the read end; the broker and runtime close this
     * sink on completion, cancellation, or failure.
     */
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

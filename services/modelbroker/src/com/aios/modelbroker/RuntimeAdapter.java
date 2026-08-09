package com.aios.modelbroker;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.aios.model.AudioStreamFormat;
import com.aios.model.IModelCallback;
import com.aios.model.ModelRequest;

import java.io.IOException;

/** A crash-isolated runtime transport selected only by verified policy. */
interface RuntimeAdapter extends AutoCloseable {
    interface Session extends AutoCloseable {
        void submitText(String text, boolean endOfInput) throws RemoteException;

        void submitAudio(
                ParcelFileDescriptor pcmStream,
                AudioStreamFormat format,
                boolean endOfInput) throws RemoteException;

        void attachAudioOutput(
                ParcelFileDescriptor pcmSink,
                AudioStreamFormat format) throws RemoteException;

        void submitMedia(
                ParcelFileDescriptor media,
                String mimeType,
                boolean endOfInput) throws RemoteException;

        @Override
        void close();
    }

    String runtimeId();

    boolean supportsBackend(String backend);

    Session open(
            VerifiedArtifact artifact,
            ModelRequest request,
            IModelCallback callback) throws IOException, RemoteException;

    void start();

    @Override
    void close();
}

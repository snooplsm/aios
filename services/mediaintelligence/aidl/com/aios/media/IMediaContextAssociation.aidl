package com.aios.media;

import android.os.ParcelFileDescriptor;
import com.aios.context.ConversationIdentity;

/** Signature-only lifecycle bridge from the SMS-role app to Media Intelligence. */
interface IMediaContextAssociation {
    void stageMmsPhoto(
            String associationToken,
            in ParcelFileDescriptor photo,
            String mimeType,
            in ConversationIdentity identity,
            long eventAtEpochMillis);
    void completeMmsPhoto(
            String associationToken,
            String sourceId,
            long eventAtEpochMillis);
    void cancelMmsPhoto(String associationToken);
    void deleteMmsPhoto(String sourceId);
    void clearMmsPhotos();
}

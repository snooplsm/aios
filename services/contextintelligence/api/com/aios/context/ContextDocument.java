package com.aios.context;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/** One revisioned source document submitted to the local communication index. */
public final class ContextDocument implements Parcelable {
    public final String sourceType;
    public final String sourceId;
    public final long revision;
    public final ConversationIdentity identity;
    public final long eventAtEpochMillis;
    public final long expiresAtEpochMillis;
    public final String expiryBootIdentity;
    public final long createdAtElapsedRealtimeMillis;
    public final long expiresAtElapsedRealtimeMillis;
    public final String text;

    public ContextDocument(
            String sourceType,
            String sourceId,
            long revision,
            ConversationIdentity identity,
            long eventAtEpochMillis,
            long expiresAtEpochMillis,
            String text) {
        this(
                sourceType,
                sourceId,
                revision,
                identity,
                eventAtEpochMillis,
                expiresAtEpochMillis,
                "",
                0L,
                0L,
                text);
    }

    public ContextDocument(
            String sourceType,
            String sourceId,
            long revision,
            ConversationIdentity identity,
            long eventAtEpochMillis,
            long expiresAtEpochMillis,
            String expiryBootIdentity,
            long createdAtElapsedRealtimeMillis,
            long expiresAtElapsedRealtimeMillis,
            String text) {
        this.sourceType = Objects.requireNonNull(sourceType);
        this.sourceId = Objects.requireNonNull(sourceId);
        this.revision = revision;
        this.identity = Objects.requireNonNull(identity);
        this.eventAtEpochMillis = eventAtEpochMillis;
        this.expiresAtEpochMillis = expiresAtEpochMillis;
        this.expiryBootIdentity = Objects.requireNonNull(expiryBootIdentity);
        this.createdAtElapsedRealtimeMillis = createdAtElapsedRealtimeMillis;
        this.expiresAtElapsedRealtimeMillis = expiresAtElapsedRealtimeMillis;
        this.text = Objects.requireNonNull(text);
    }

    private ContextDocument(Parcel source) {
        this(
                Objects.requireNonNull(source.readString()),
                Objects.requireNonNull(source.readString()),
                source.readLong(),
                Objects.requireNonNull(source.readTypedObject(ConversationIdentity.CREATOR)),
                source.readLong(),
                source.readLong(),
                Objects.requireNonNull(source.readString()),
                source.readLong(),
                source.readLong(),
                Objects.requireNonNull(source.readString()));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeString(sourceType);
        destination.writeString(sourceId);
        destination.writeLong(revision);
        destination.writeTypedObject(identity, flags);
        destination.writeLong(eventAtEpochMillis);
        destination.writeLong(expiresAtEpochMillis);
        destination.writeString(expiryBootIdentity);
        destination.writeLong(createdAtElapsedRealtimeMillis);
        destination.writeLong(expiresAtElapsedRealtimeMillis);
        destination.writeString(text);
    }

    public static final Creator<ContextDocument> CREATOR = new Creator<>() {
        @Override
        public ContextDocument createFromParcel(Parcel source) {
            return new ContextDocument(source);
        }

        @Override
        public ContextDocument[] newArray(int size) {
            return new ContextDocument[size];
        }
    };
}

package com.aios.context;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/** Bounded retrieval result. It intentionally excludes raw provider URIs. */
public final class ContextSnippet implements Parcelable {
    public final String sourceType;
    public final String sourceId;
    public final long revision;
    public final long eventAtEpochMillis;
    public final String excerpt;

    public ContextSnippet(
            String sourceType,
            String sourceId,
            long revision,
            long eventAtEpochMillis,
            String excerpt) {
        this.sourceType = Objects.requireNonNull(sourceType);
        this.sourceId = Objects.requireNonNull(sourceId);
        this.revision = revision;
        this.eventAtEpochMillis = eventAtEpochMillis;
        this.excerpt = Objects.requireNonNull(excerpt);
    }

    private ContextSnippet(Parcel source) {
        this(
                Objects.requireNonNull(source.readString()),
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
        destination.writeLong(eventAtEpochMillis);
        destination.writeString(excerpt);
    }

    public static final Creator<ContextSnippet> CREATOR = new Creator<>() {
        @Override
        public ContextSnippet createFromParcel(Parcel source) {
            return new ContextSnippet(source);
        }

        @Override
        public ContextSnippet[] newArray(int size) {
            return new ContextSnippet[size];
        }
    };
}

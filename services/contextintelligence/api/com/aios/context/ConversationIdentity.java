package com.aios.context;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/** Opaque per-install number identity plus an optional current-contact alias. */
public final class ConversationIdentity implements Parcelable {
    public final String conversationKey;
    public final String contactKey;
    public final String[] relatedConversationKeys;

    public ConversationIdentity(
            String conversationKey, String contactKey, String[] relatedConversationKeys) {
        this.conversationKey = Objects.requireNonNull(conversationKey);
        this.contactKey = Objects.requireNonNull(contactKey);
        this.relatedConversationKeys = Objects.requireNonNull(relatedConversationKeys).clone();
    }

    private ConversationIdentity(Parcel source) {
        this(
                Objects.requireNonNull(source.readString()),
                Objects.requireNonNull(source.readString()),
                Objects.requireNonNull(source.createStringArray()));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeString(conversationKey);
        destination.writeString(contactKey);
        destination.writeStringArray(relatedConversationKeys);
    }

    public static final Creator<ConversationIdentity> CREATOR = new Creator<>() {
        @Override
        public ConversationIdentity createFromParcel(Parcel source) {
            return new ConversationIdentity(source);
        }

        @Override
        public ConversationIdentity[] newArray(int size) {
            return new ConversationIdentity[size];
        }
    };
}

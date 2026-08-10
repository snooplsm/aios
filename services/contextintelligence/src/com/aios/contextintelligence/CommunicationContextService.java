package com.aios.contextintelligence;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.util.Base64;

import com.aios.context.ContextDocument;
import com.aios.context.ContextSnippet;
import com.aios.context.ConversationIdentity;
import com.aios.context.ICommunicationContext;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Signature-only boundary for communication identity, indexing, and retrieval. */
public final class CommunicationContextService extends Service {
    static final String ACTION = "com.aios.context.COMMUNICATION_CONTEXT_SERVICE";
    private static final String PREFS = "opaque_identity";
    private static final String SECRET = "hmac_secret";

    private ContextStore store;

    private final ICommunicationContext.Stub binder = new ICommunicationContext.Stub() {
        @Override
        public ConversationIdentity resolveIdentity(String rawAddress, String countryIso) {
            String caller = authorizedCaller();
            if (!ContextPolicy.isClient(caller)) throw new SecurityException("unknown client");
            long token = Binder.clearCallingIdentity();
            try {
                return resolve(rawAddress, countryIso);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void upsert(ContextDocument document) {
            String caller = authorizedCaller();
            if (document == null || document.identity == null) {
                throw new IllegalArgumentException("context document is required");
            }
            ContextPolicy.validateWrite(
                    caller,
                    document.sourceType,
                    document.sourceId,
                    document.revision,
                    document.identity.conversationKey,
                    document.identity.contactKey,
                    document.identity.relatedConversationKeys,
                    document.eventAtEpochMillis,
                    document.expiresAtEpochMillis,
                    document.text);
            long token = Binder.clearCallingIdentity();
            try {
                store.upsert(document);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void deleteSource(String sourceType, String sourceId, long revision) {
            String caller = authorizedCaller();
            ContextPolicy.validateDelete(caller, sourceType, sourceId, revision);
            long token = Binder.clearCallingIdentity();
            try {
                store.deleteSource(sourceType, sourceId, revision);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public List<ContextSnippet> query(
                ConversationIdentity identity,
                String query,
                int limit,
                long nowEpochMillis) {
            String caller = authorizedCaller();
            if (identity == null) throw new IllegalArgumentException("identity is required");
            ContextPolicy.validateQuery(
                    caller,
                    identity.conversationKey,
                    identity.contactKey,
                    identity.relatedConversationKeys,
                    query,
                    limit,
                    nowEpochMillis);
            long token = Binder.clearCallingIdentity();
            try {
                return store.query(identity, query, limit, nowEpochMillis);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void purgeExpired(long nowEpochMillis) {
            String caller = authorizedCaller();
            if (!ContextPolicy.isClient(caller)) throw new SecurityException("unknown client");
            long token = Binder.clearCallingIdentity();
            try {
                store.purgeExpired(nowEpochMillis);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        store = new ContextStore(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return intent != null && ACTION.equals(intent.getAction()) ? binder : null;
    }

    @Override
    public void onDestroy() {
        store.close();
        super.onDestroy();
    }

    private String authorizedCaller() {
        int uid = Binder.getCallingUid();
        PackageManager packages = getPackageManager();
        String[] names = packages.getPackagesForUid(uid);
        if (names == null || names.length != 1 || !ContextPolicy.isClient(names[0])) {
            throw new SecurityException("communication context caller is not authorized");
        }
        return names[0];
    }

    private ConversationIdentity resolve(String rawAddress, String countryIso) {
        if (rawAddress == null || rawAddress.isBlank()) {
            throw new IllegalArgumentException("phone address is required");
        }
        String normalized = normalize(rawAddress, countryIso);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("phone address cannot be normalized");
        }
        ContactMatch contact = contactMatch(rawAddress);
        String primary = opaque("number", normalized);
        Set<String> related = new LinkedHashSet<>();
        related.add(primary);
        if (contact != null) {
            for (String number : contactNumbers(contact.contactId)) {
                String member = normalize(number, countryIso);
                if (!member.isBlank()) related.add(opaque("number", member));
                if (related.size() == 32) break;
            }
        }
        return new ConversationIdentity(
                primary,
                contact == null ? "" : opaque("contact", contact.lookupKey),
                related.toArray(new String[0]));
    }

    private ContactMatch contactMatch(String rawAddress) {
        Uri uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(rawAddress));
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{
                        ContactsContract.PhoneLookup._ID,
                        ContactsContract.PhoneLookup.LOOKUP_KEY
                },
                null,
                null,
                null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            String lookupKey = cursor.getString(1);
            return lookupKey == null || lookupKey.isBlank()
                    ? null : new ContactMatch(cursor.getLong(0), lookupKey);
        } catch (SecurityException denied) {
            return null;
        }
    }

    private List<String> contactNumbers(long contactId) {
        java.util.ArrayList<String> numbers = new java.util.ArrayList<>();
        try (Cursor cursor = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?",
                new String[]{Long.toString(contactId)},
                null)) {
            while (cursor != null && cursor.moveToNext() && numbers.size() < 32) {
                String value = cursor.getString(0);
                if (value != null && !value.isBlank()) numbers.add(value);
            }
        } catch (SecurityException denied) {
            return List.of();
        }
        return numbers;
    }

    private static String normalize(String rawAddress, String countryIso) {
        String normalized = null;
        if (countryIso != null && !countryIso.isBlank()) {
            normalized = PhoneNumberUtils.formatNumberToE164(
                    rawAddress, countryIso.toUpperCase(java.util.Locale.ROOT));
        }
        return normalized == null || normalized.isBlank()
                ? PhoneNumberUtils.normalizeNumber(rawAddress) : normalized;
    }

    private static final class ContactMatch {
        final long contactId;
        final String lookupKey;

        ContactMatch(long contactId, String lookupKey) {
            this.contactId = contactId;
            this.lookupKey = lookupKey;
        }
    }

    private String opaque(String namespace, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret(), "HmacSHA256"));
            byte[] digest = mac.doFinal((namespace + "\u0000" + value)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : digest) hex.append(String.format("%02x", item & 0xff));
            return namespace + ":" + hex;
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HMAC is unavailable", impossible);
        }
    }

    private byte[] secret() {
        SharedPreferences preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encoded = preferences.getString(SECRET, null);
        if (encoded != null) {
            try {
                byte[] existing = Base64.decode(encoded, Base64.NO_WRAP);
                if (existing.length == 32) return existing;
            } catch (IllegalArgumentException ignored) {
                // Replace malformed local state before producing any identity.
            }
        }
        byte[] generated = new byte[32];
        new SecureRandom().nextBytes(generated);
        boolean committed = preferences.edit().putString(
                SECRET, Base64.encodeToString(generated, Base64.NO_WRAP)).commit();
        if (!committed) throw new IllegalStateException("cannot persist identity secret");
        return generated;
    }
}

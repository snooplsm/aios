# Messaging and communication context

AIOS Messaging is an original Kotlin/Jetpack Compose application with immutable
unidirectional state. It is a user-selectable SMS-role candidate, not a silent
replacement for the user's current messaging app. The manifest declares the
four Android role surfaces: `SENDTO`, respond-via-message, `SMS_DELIVER`, and MMS
WAP delivery.

The first transport milestone sends multipart SMS, writes accepted outbound SMS
to the Telephony provider, persists inbound SMS before acknowledging delivery,
supports respond-via-message, resolves contact display names, launches the phone
app with `ACTION_DIAL`, and uses the system Photo Picker for read-only attachment
selection. It deliberately fails closed for MMS. Selecting a photo creates a
draft, but Send explains that carrier-tested MMS is not admitted. The SMS-role
prompt also warns that this is not ready to be a daily messaging app. Incoming
MMS returns an error rather than pretending the PDU was persisted. MMS download,
provider round trip, attachments, APN behavior, multi-SIM routing, delivery
reports, and carrier tests are hard release gates.

## Conversation identity

`AiosContextIntelligence` is a separate platform-signed service. Authorized
clients pass a number transiently to `resolveIdentity`; the service normalizes it
with an ISO-aware E.164 attempt, then derives a per-install HMAC-SHA-256 key. The
database never stores the raw number or reversible contact lookup key.

When the number currently belongs to a contact, identity resolution also queries
up to 32 current phone numbers for that contact and returns their opaque number
keys. Retrieval uses only that freshly resolved set. Removing a number from a
contact, splitting contacts, or deleting a contact therefore changes the next
query immediately—there is no persisted alias that can keep stale numbers
linked. A number remains its own stable primary conversation even without a
contact.

## Retrieval boundary

The credential-encrypted SQLite index accepts these source owners only:

- AIOS Messaging: `sms` and later `mms`;
- AIOS Phone: durable, non-transcript `call_event` records;
- Call Intelligence: `call_artifact` records with an enforced maximum 24-hour
  expiry; and
- Media Intelligence: `media_metadata` for photos explicitly associated with a
  conversation.

The exported Binder service requires a signature permission and then checks the
exact calling package and source type. AIOS Messaging, Phone, and Call
Intelligence may query; Media Intelligence may publish but cannot read a person's
communication history. Results are capped at eight snippets and 512 characters
each. Source documents are capped at 4,096 characters. The first implementation
uses local FTS4 lexical retrieval; a later embedding index can replace ranking
without changing identity, authorization, retention, or deletion semantics.

Every source has a monotonic revision. Deletion removes the entry and writes a
tombstone, so a delayed stale indexing callback cannot resurrect it. SMS deletion
is wired to that API. Call-artifact expiry is purged during queries and after
boot. Media and call producers must wire their own upsert/delete lifecycle before
their context release gates can pass.

## Still required before daily use

- complete and carrier-test MMS send/download/provider persistence;
- expose explicit SIM selection when no single default SMS subscription exists;
- reconcile provider changes made outside AIOS Messaging;
- wire call event, expiring call artifact, and selected-photo metadata producers;
- add a user control to exclude a conversation or source from retrieval; and
- run SMS/MMS, reboot, restore, deletion, lock-screen notification, and emergency
  interaction tests on the Pixel hardware lane.

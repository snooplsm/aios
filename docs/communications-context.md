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

For an incoming call, AIOS Phone appends the presented number and country ISO to
the version-tolerant tail of `IncomingCallContext` only when the owner has enabled
processing and the call is not an emergency or emergency callback. Call
Intelligence passes that value directly to `resolveIdentity` on a background
worker; it is not logged, added to a map, or written to the call-artifact
directory. Retrieval never delays Telecom policy, pickup, or capture. If the
context service is absent or slow, the call continues without history.

An AI-handled call may receive the retrieved context before or after capture
starts. The receptionist prompt receives an identifier-free JSON array with at
most eight entries; each contains only source type, event time, and a 512-
character excerpt. Source IDs and opaque identity keys are excluded. The prompt
treats both prior context and caller speech as private untrusted data and forbids
quoting or disclosing history to the caller.

At normal call teardown, Call Intelligence indexes final English/Spanish
transcript segments, assistant replies, and validated risk events in a bounded
4,096-character `call_artifact` document. Its source ID is the same SHA-256 call
directory name, not a number, and its expiry is copied from the immutable local
artifact metadata. A context write is skipped if that expiry has already passed.
No separate context TTL can extend the 24-hour call-artifact lifetime.

Every source has a monotonic revision. Deletion removes the entry and writes a
tombstone, so a delayed stale indexing callback cannot resurrect it. SMS deletion
is wired to that API. Normal call teardown now publishes the expiring call
artifact. Call-artifact expiry is purged during queries and after boot. Durable
Phone call events are also wired, while selected-photo metadata still needs its
producer lifecycle before that context release gate can pass.

Media Intelligence now removes its authoritative private result when the
MediaStore source is deleted, including bounded restart reconciliation and
provider-database invalidation. The later selected-photo context producer must
reuse that lifecycle and emit its context tombstone before
`context.photo_metadata_lifecycle` can pass; draft selection alone still does
not publish conversation history.

AIOS Phone observes CallLog only while it holds `ROLE_DIALER` and reconciles at
most the newest 256 presented, non-emergency records. The phone number is used
only as a transient input to `resolveIdentity`; stored call-event text contains
the event kind, bounded duration, and video flag, but no number or contact name.
A private 256-entry ledger stores only source IDs and keyed HMAC fingerprints.
Provider deletions, rows aging out of the window, and loss of the dialer role all
produce ordered tombstones. A synchronously persisted global revision clock
prevents an older retry from winning after restart or call-log ID reuse.

Because call-log IDs continually increase, `call_event` deletions use one
source-level delete watermark in the context database rather than accumulating
an unbounded tombstone table. The next legitimate Phone write must have a newer
global revision; delayed writes at or below the watermark remain rejected. The
version-2 database migration folds any existing per-call tombstones into that
watermark without dropping current entries.

## Still required before daily use

- complete and carrier-test MMS send/download/provider persistence;
- expose explicit SIM selection when no single default SMS subscription exists;
- reconcile provider changes made outside AIOS Messaging;
- wire the selected-photo metadata producer;
- exercise call-event reconciliation across deletion, reboot, role loss, and
  clock changes on Pixel hardware before passing `context.call_source_lifecycle`;
- add a user control to exclude a conversation or source from retrieval; and
- run SMS/MMS, reboot, restore, deletion, lock-screen notification, and emergency
  interaction tests on the Pixel hardware lane.

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
selection. A debug-only AOSP transport now persists MMS PDUs and parts through
the Telephony provider, submits carrier send/download work through `SmsManager`,
and completes it through an explicit durable callback journal. Selected photos
are decoded with a bounded target size and repeatedly recompressed to the active
subscription's carrier limit. Incoming notification indications are deduplicated
by transaction ID before download, and a retrieved message is indexed only after
provider persistence completes.

The API-36 emulator lane now exercises the actual SMS role and production
Compose app. A host helper uses the emulator's authenticated gRPC modem endpoint
to inject a PDU; the test then proves the production `SMS_DELIVER` receiver wrote
one inbox row before the conversation appeared. It opens the same conversation
through `SENDTO`, drives **Send SMS**, verifies one sent row and the emulator's
one expected inbox loopback on the same thread/subscription, and performs exact
token cleanup plus role restoration. Future or invalid network PDU timestamps
are clamped to local receipt time so a sender cannot reorder the conversation;
legitimate older delayed timestamps are preserved. None of this is carrier,
MMS, dual-SIM, or physical Pixel release evidence.

The SMS-role prompt still warns that this is not ready to be a daily messaging
app. `user` builds do not admit MMS until Soong compilation and real carrier,
APN, roaming, multi-SIM, reboot, and provider round-trip tests pass. Delivery and
read reports beyond the mandatory retrieve acknowledgement remain later work.
The exact lifecycle and evidence boundary are in `mms-transport.md`.

Outgoing SMS and MMS use one composer-level SIM choice. Messaging lists active,
non-opportunistic subscriptions only after the owner grants phone-state access.
An active saved choice wins, followed by a valid system SMS default; exactly one
active subscription is selected automatically. Two active subscriptions with no
choice or default leave Send disabled. Opening an existing conversation adopts
the newest message's active subscription, and a newly received message updates
the open composer to reply on the SIM that received it. The chosen subscription
is passed explicitly to `SmsManager` and written to the Telephony provider; the
transport never silently switches an explicit send to another active SIM.

Messaging also treats the Telephony provider, not callback delivery, as the
authoritative context source. While it holds `ROLE_SMS`, descendant observers
trigger a complete reconciliation bounded to 128 provider rows per page. Each
pass snapshots an SMS and MMS high-water ID, resumes page-by-page, and deletes
context only after both snapshots finish. A private SQLite ledger stores only
source type, provider ID, a per-install HMAC fingerprint, and the last completed
sweep epoch; it never stores an address or message body. A persisted no-network
job repeats the pass after boot or an SMS-role change. Failed/draft SMS, failed
or in-flight MMS, and undownloaded MMS notifications are excluded.
The context service exposes a random, non-identifying store-instance token; a
new Messaging ledger or changed token forces a source reset and complete rebuild
instead of treating a missing remote database as already synchronized.

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

- AIOS Messaging: `sms` and `mms`;
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

Every source has a monotonic revision. Deletion removes the entry and advances a
source watermark, so a delayed stale indexing callback cannot resurrect it.
Direct message callbacks and provider reconciliation share one synchronously
persisted revision clock. Provider deletion, restore-time edits, application
restart, and SMS-role loss therefore converge without accumulating one durable
tombstone for every deleted message. Normal call teardown now publishes the
expiring call artifact. Call-artifact expiry is purged during queries and after
boot. Durable Phone call events and selected-photo metadata producers are now
wired; physical Pixel evidence is still required before their release gates pass.

Media Intelligence removes its authoritative private result when the MediaStore
source is deleted, including bounded restart reconciliation and provider-database
invalidation. For an outgoing selected-photo MMS, Messaging resolves the number
transiently, then stages a read-only descriptor with only the resulting opaque
identity and a random token. Draft selection alone does nothing, and staging at
Send still cannot publish. The durable MMS callback journal carries the token;
only a carrier-confirmed Sent transition commits `mms:<provider-id>`.

Media Intelligence hashes the descriptor within a 128 MiB bound and associates
it only when that digest maps to exactly one live, indexed local MediaStore job.
The index retains reviewed pre/post-XMP digest aliases for the same job, so
portable metadata timing does not break the match. Cloud-only picker items and
duplicate-byte ambiguity fail closed. The published document contains only the
bounded caption and tags, never a URI, digest, number, or contact key.

Deleting/trashing the canonical photo marks every resolved association for a
context tombstone before its private job disappears. Direct or externally
observed MMS deletion does the same by source ID. SMS-role loss requests a
durable bulk `media_metadata` watermark even if the context service is currently
unavailable. A changed context-store instance republishes only still-live,
carrier-completed associations. Carrier failure cancels staged state, and
incomplete stages expire after 24 hours.

AIOS Phone observes CallLog only while it holds `ROLE_DIALER` and reconciles at
most the newest 256 presented, non-emergency records. The phone number is used
only as a transient input to `resolveIdentity`; stored call-event text contains
the event kind, bounded duration, and video flag, but no number or contact name.
A private 256-entry ledger stores only source IDs and keyed HMAC fingerprints.
Provider deletions, rows aging out of the window, and loss of the dialer role all
produce ordered tombstones. A synchronously persisted global revision clock
prevents an older retry from winning after restart or call-log ID reuse.
The reconciler uses a generation-scoped Communication Context binding: service
death, null bindings, failed remote calls, and connections that stall for 15
seconds are replaced with bounded 1-to-60-second backoff. Stale callbacks cannot
displace a newer service, and the durable ledger replays only mutations that did
not complete. Losing the dialer role drains required tombstones and then stops
the binding; regaining it starts a fresh retry epoch.

Because call-log IDs continually increase, `call_event` deletions use one
source-level delete watermark in the context database rather than accumulating
an unbounded tombstone table. The next legitimate Phone write must have a newer
global revision; delayed writes at or below the watermark remain rejected. The
version-2 database migration folds any existing per-call tombstones into that
watermark without dropping current entries. The version-3 migration does the
same for legacy SMS/MMS tombstones before provider reconciliation begins.

The public-SDK `preview:callcontextcheck` lane stages the complete production
Communication Context service, API parcelables, AIDL, tests, resources, and real
manifest. It also verifies that every private app-data domain is excluded from
cloud backup and device transfer: opaque keys, source revisions, tombstones, and
retrieval documents are meaningful only for the originating installation. The
lane catches Android API/component/resource drift but does not prove the
platform signature grant, contacts provider behavior, or physical-device
lifecycle gates.

## Still required before daily use

- compile the AOSP-only MMS source with Soong and carrier-test send, download,
  provider persistence, APN/roaming, crash recovery, and duplicate suppression;
- exercise the selected-photo stage/complete/delete/role-loss matrix on Pixel
  hardware before passing `context.photo_metadata_lifecycle`;
- exercise call-event reconciliation across deletion, reboot, role loss, and
  clock changes on Pixel hardware before passing `context.call_source_lifecycle`;
- add a user control to exclude a conversation or source from retrieval; and
- run SMS/MMS, reboot, restore, deletion, lock-screen notification, and emergency
  interaction tests on the Pixel hardware lane.

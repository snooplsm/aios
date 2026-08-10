# MMS carrier transport

AIOS Messaging contains a real MMS transport for controlled testing on
debuggable AOSP builds. It is deliberately disabled on `user` builds until the
Pixel carrier gate has physical evidence. The public-SDK preview also substitutes
a fail-closed factory, because the production transport depends on platform APIs
and AOSP's internal MMS source library.

## Upstream boundary

The protocol implementation is not copied into AIOS. The Messaging Soong module
links `framework-mms-shared-srcs`, the Android 17 `frameworks/base` filegroup that
owns `com.google.android.mms`. Patch
`patches/0002-framework-mms-aios-visibility.patch` adds only the AIOS Messaging
module to that filegroup's visibility list at frameworks/base commit
`94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`. Updating AOSP therefore keeps the
codec and provider persister with their upstream owner; the patch verifier makes
any changed boundary explicit.

Shared UDF, repository, receiver, and UI code remains under `apps/messaging/src`
and compiles against the public SDK. AOSP-only code is isolated under
`apps/messaging/platform/src`. This prevents an emulator fixture from silently
becoming the production carrier implementation.

## Outbound photo path

1. Require the user-selected SMS role and the exact active, non-opportunistic
   subscription chosen in the shared SMS/MMS composer. There is no outgoing
   fallback to a different SIM.
2. Read maximum PDU size, image dimensions, and MMS behavior from that
   subscription's `SmsManager` carrier configuration.
3. Decode the Photo Picker URI with a bounded target size, flatten transparency
   onto white, and resize/recompress JPEG until it fits the carrier payload
   budget. The original photo is never modified.
4. Build a SMIL/text/JPEG `SendReq`, persist it to `Telephony.Mms.Outbox`, reload
   the provider copy, and compose the exact submitted PDU.
5. Store the PDU in a per-operation, app-private no-backup file exposed by a
   non-exported, URI-granting provider.
6. Durably advance `PREPARING -> PROVIDER_PERSISTED -> SUBMITTED` before calling
   `sendMultimediaMessage`. The explicit immutable callback moves an accepted
   row to Sent or marks the provider row Failed.

The Send action also creates a random media-association token. Messaging passes
the selected bytes once through a signature-only `ParcelFileDescriptor` API,
along with an opaque conversation identity; Media Intelligence stores only the
bounded SHA-256 digest and opaque keys. The MMS journal persists the same token.
This is staging, not publication. Only the carrier-confirmed Sent event completes
the association using `mms:<provider-id>` as its lifecycle source.

A process restart never automatically resubmits outbound MMS. A synchronous
failure before submission may remove its incomplete provider row; after the
state reaches `SUBMITTED`, the row is retained and marked Failed because the
request may already have crossed the process boundary.

## Incoming path

The SMS-role `WAP_PUSH_DELIVER` receiver validates the MMS MIME type and bounded
PDU, then parses a `NotificationInd`. It applies the carrier transaction-ID rule,
deduplicates current notification/retrieve rows, persists the notification to
the MMS Inbox, and journals the carrier download. A successful callback bounds
and parses the downloaded `RetrieveConf`, persists its parts to the provider,
retires the notification placeholder, emits the completed message, and submits
a best-effort `NotifyRespInd` acknowledgement.

Incoming or outgoing content is published to Communication Context only after
the corresponding provider completion event. The context source type is `mms`,
and deleting the source message emits its ordered tombstone.
For an outgoing selected photo, Media Intelligence separately publishes a
`media_metadata` caption/tag projection only when the staged digest maps to
exactly one indexed local MediaStore job. A cloud-only picker item or duplicate
byte-identical local items remain unassociated rather than guessing.

## Crash and callback rules

The app-private SQLite journal is the authority for callback tokens and enforces
forward-only transitions. `PREPARING` and `PROVIDER_PERSISTED` work is failed on
restart. `SUBMITTED` work is left pending for 24 hours so a delayed carrier
callback can complete it, then failed without resubmission. Terminal journal
rows and unreferenced PDU files are pruned after the same bound. Duplicate or
mismatched callbacks cannot move a terminal row or erase an active PDU.
Successful send/download rows remain until completion is acknowledged, and an
unreported terminal success is replayed after process restart. For an outgoing
photo, that acknowledgement is withheld until Media Intelligence has
synchronously persisted the carrier-completed association. Media association
completion and deletion are idempotent, so replay cannot create a second context
source.

This policy chooses a visible failed message over a possible duplicate send. It
does not claim exactly-once delivery from the carrier network.

## Pixel carrier admission

`messaging.mms_carrier_transport` remains `not_run`. At minimum, record all of
the following on the pinned Pixel 9a build for every supported carrier and for a
physical SIM and eSIM where available:

- send and receive photo-only and photo-plus-text messages in both directions;
- carrier-limit transcoding for landscape, portrait, transparent, and very large
  inputs, with the original digest unchanged;
- default-subscription selection and explicit failure when no valid default is
  available, including selecting and switching each active SIM in the composer;
- mobile-data off, airplane mode, roaming allowed/blocked, invalid APN, timeout,
  and HTTP/carrier rejection behavior;
- app-process death and device reboot before submission, after submission, and
  while downloading, with no duplicate outbound message;
- duplicate WAP pushes, duplicate callbacks, provider deletion, and role loss;
- correct Outbox/Sent/Failed/Inbox provider state, thread/address association,
  notification behavior, and Communication Context creation/deletion; and
- payload file and terminal-journal cleanup after the 24-hour bound.

Passing the host unit tests or public preview build is necessary but cannot pass
this gate. A release `user` build must remain disabled until Soong compilation
and this physical matrix are both recorded in release evidence.

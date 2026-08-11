# Model Broker lifecycle

## Startup

The broker is persistent but fail-closed. It loads the product catalog, verifies
the detached artifact manifest, and discovers exact allowlisted runtime
backends. `listCapabilities` marks a capability available only when catalog,
artifact, device-admission, and provider-identity checks all pass. Provider
known-answer tests remain a separate release qualification gate; startup does
not claim that evidence merely because a provider binds successfully.

## Session states

```text
CREATED -> QUEUED -> LOADING -> RUNNING -> DRAINING -> COMPLETED
    |         |         |          |           |
    +---------+---------+----------+-----------+-> CANCELLED / FAILED
```

- Each session belongs to the Binder calling UID and callback binder.
- Binder death cancels the session, closes its pipes, and releases its lease.
- Session IDs are opaque process-local handles, not persistent IDs or secrets.
- A caller cannot submit to, inspect, or cancel another UID's session.
- Deadlines use elapsed realtime, never wall-clock time.
- One broker-owned timer expires both queued and running sessions. Expiration
  closes descriptors and the runtime lease, reports `ERROR_DEADLINE_EXCEEDED`,
  and promotes eligible queued work.
- Chunk and terminal callback delivery is serialized per session so expiration,
  cancellation, and runtime completion cannot produce two terminal callbacks.

## Arbitration

Server policy assigns one of three classes:

1. `CALL_RX_REALTIME`: incoming call transcription; cannot be displaced by
   background work.
2. `CALL_INTERACTIVE`: uplink transcription, receptionist reasoning, and TTS.
3. `MEDIA_BACKGROUND`: photo/video work; preemptible at any model boundary.

A ringing or active call immediately prevents new media leases and requests
cancellation of existing media inference. If memory cannot hold RX ASR plus an
interactive model, the broker unloads the interactive model between turns. It
never swaps model pages heavily enough to jeopardize telephony.

Call-active state is a UID-owned Binder lease, not a sticky boolean. The call
pipeline supplies a process-local token; the broker links that token to death,
rejects cross-UID release, and automatically clears the final lease if the call
pipeline crashes. Request admission observes the lease synchronously, while
runtime cancellation and queued-media promotion are serialized on the broker's
main looper. This prevents a dead call process from blocking media indefinitely.
Individual RX/TX/agent sessions also block media for their own lifetime, but do
not mutate the persistent call-activity lease; completing the final foreground
session therefore promotes queued media when no lifecycle lease remains.

There is deliberately no fixed broker RAM ceiling. The catalog's resident-memory
figures are measurement metadata, not quotas. Android low-memory callbacks
preempt background media, and isolated runtime providers release idle model
engines at `TRIM_MEMORY_RUNNING_LOW`. Active RX/TX call work remains preferred;
the device benchmark rejects a candidate if this policy still causes paging,
LMKD kills, audio loss, UI jank, or unacceptable thermal throttling.

## Artifact activation

An artifact is eligible only if its ID exists in the product catalog, license was
accepted during the build, digest matches the signed build manifest, runtime and
ABI are compatible, and its smoke test passes. A resident-memory estimate
informs device benchmarking but is not a fixed admission ceiling. Failed
artifacts remain unavailable; there is no fallback to an unverified path or
network download.

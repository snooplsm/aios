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
- Finite deadlines use elapsed realtime, never wall-clock time. One broker-owned
  timer expires finite queued and running sessions, closes descriptors and the
  runtime lease, reports `ERROR_DEADLINE_EXCEEDED`, and promotes eligible work.
  Admission rejects an expired deadline or one more than five minutes ahead.
- `streaming_asr` alone may use the explicit `Long.MAX_VALUE` lifecycle mode.
  Those sessions end through PCM pipe EOF, explicit cancellation, callback
  Binder death, call/media preemption, or broker shutdown. A finite ASR request,
  such as a benchmark, remains deadline-enforced.
- Chunk and terminal callback delivery is serialized per session so expiration,
  cancellation, and runtime completion cannot produce two terminal callbacks.
- Finite and background-media sessions may emit at most 4,096 chunks and 4 MiB
  of aggregate text. Lifecycle-bound call ASR instead uses a source-timeline
  rate budget: 64 initial callbacks plus one per 100 ms of captured audio, with
  source time no more than ten seconds ahead of elapsed session time. This keeps
  a legitimate long call alive without permitting an isolated provider to flood
  one-way Binder callbacks at an unbounded rate.

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

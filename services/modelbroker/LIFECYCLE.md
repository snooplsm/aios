# Model Broker lifecycle

## Startup

The broker is persistent but fail-closed. It loads the product catalog, verifies
the detached artifact manifest, discovers runtime backends, and performs a tiny
known-answer smoke test. Only then does `listCapabilities` mark a capability
available.

## Session states

```text
CREATED -> QUEUED -> LOADING -> RUNNING -> DRAINING -> COMPLETED
    |         |         |          |           |
    +---------+---------+----------+-----------+-> CANCELLED / FAILED
```

- Each session belongs to the Binder calling UID and callback binder.
- Binder death cancels the session, closes its pipes, and releases its lease.
- Session IDs are unpredictable process-local handles, not persistent IDs.
- A caller cannot submit to, inspect, or cancel another UID's session.
- Deadlines use elapsed realtime, never wall-clock time.

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

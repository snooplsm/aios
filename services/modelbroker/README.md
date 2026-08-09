# Model Broker API

This directory starts with the Binder contract so the dialer, call pipeline, and
media pipeline can be developed against one stable boundary.

The API intentionally transfers inputs through pipes/file descriptors and returns
typed chunks. It never returns a path to weights. The eventual service checks the
calling UID and signature for every session, assigns priority from caller policy
rather than trusting the requested priority, and closes all descriptors on
cancellation or binder death.

Capabilities are semantic (`streaming_asr`, `text_generation`,
`image_understanding`, `speech_synthesis`). Model IDs are diagnostic data, not a
contract that callers may pin indefinitely.

Current startup code verifies the locally generated artifact manifest, confines
canonical paths to `/product/etc/aios/models`, recomputes exact size/SHA-256,
cross-checks runtime/capabilities/languages against the catalog, selects the
measured-RAM tier, and applies package/capability/workload quotas. Runtime adapters
remain unregistered until their exact native dependencies can be compiled and
smoke-tested in AOSP, so verified weights alone never make a capability active.

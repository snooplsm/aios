# Compose dialer decision record

Status: accepted for the research product.

AIOS will build an original Apache-2.0 Kotlin/Jetpack Compose dialer rather than
brand and perpetually rebase the AOSP Dialer UI. Compose substantially reduces
the cost of the owner-facing settings, transcript, risk, dial-pad, and in-call
surfaces. It does not reduce the Telecom correctness obligations.

The core is therefore UDF, not a UI-owned `Call` singleton. Framework callbacks
enter a main-thread identity registry, immutable snapshots enter `StateFlow`,
and typed actions are the sole path back to Telecom. Multi-call, conference,
endpoint, RTT, video-surface lifecycle, visual voicemail, emergency fallback,
notification, accessibility, and process-lifecycle behavior remain release-gated.

The AOSP Dialer stays installed as the system/emergency dialer during the
transition. AIOS Phone is selected only via the standard user role flow. This
keeps the new module additive and easy to carry across AOSP updates while
preserving a mature escape path during hardware validation.

# Privacy, consent, and safety boundary

This document is an engineering constraint, not legal advice.

## Call consent policy

The requested product processes and temporarily stores both sides of telephone
calls. Federal law contains a one-party-consent exception, but US state law is
not uniform. For example, California Penal Code section 632 applies an all-party
consent rule to defined confidential communications.

The research image does not play a mandatory spoken disclosure before listening.
That product choice is not a legal conclusion. Shipping rules require qualified
counsel and must address interstate calls, business use, sector rules,
biometric/voice data, children's data, and whether a jurisdiction-specific
notice or consent flow must be added.

Engineering defaults for the research image are:

- AI answering is opt-in during setup.
- The research build defaults both call processing and automatic answering off.
  Selecting an answer mode is insufficient: the service also requires local
  processing to be enabled and the caller-audio interaction transport to pass
  its device gate before it can authorize the Dialer to answer.
- A persistent on-device indicator is shown during processing.
- Stored artifacts expire within 24 hours.
- Dialer Binder death immediately stops orphaned audio capture and model work;
  a partial non-emergency artifact remains subject to its original 24-hour
  maximum, and is never extended by a client restart.
- An orderly dialer unbind must explicitly release every live presence
  assertion; the final release enforces the same stop rule as Binder death.
- Late model, transcript, prior-context, and caller-audio callbacks are bound to
  their originating session and are discarded after teardown or replacement.
- No cloud transfer, analytics payload, or training use contains call content.
- The assistant must not falsely claim to be the owner or another real person.
- Emergency calls and emergency callback mode never use optional AI processing.
  The dialer latches number-based and Telecom-provided emergency signals for
  both call directions. A late signal stops AI audio/capture and requests
  immediate deletion of any partial call artifact without ending the call.
- Before first unlock after reboot, only ordinary call controls and
  non-sensitive UI preferences are Direct Boot available. Assistant policy,
  contact/context state, call artifacts, model sessions, transcripts, audio, and
  reconciliation secrets remain credential-encrypted and unavailable.

## Access control

"Other apps can use the models" means authorized apps can call a stable,
signature-protected capability API. It does not mean arbitrary installed apps can
read model files, call transcripts, the photo index, or unmetered inference.
Future third-party access requires a user-visible permission, quotas, foreground
rules, and protection against using inference as a covert microphone or media
exfiltration channel.

The AIOS product image gives Messaging a revocable default phone-state grant;
sideloaded builds, or a grant the owner revokes, fall back to asking only after
the app holds the user-selected SMS role. Messaging uses that permission to
enumerate active subscription IDs, slot positions, and carrier display labels
for explicit SIM routing. It does not read or persist line phone numbers. Only
the owner's selected subscription ID is kept in app preferences; authoritative
per-message subscription IDs remain in the
Android Telephony provider.

Messaging's provider-reconciliation ledger contains only SMS/MMS source type,
provider row ID, a per-install HMAC fingerprint, and a sweep epoch. Addresses,
message bodies, contact names, and reusable unkeyed content hashes are excluded.
The ledger and communication index are cleared through monotonic source
watermarks when the owner moves the SMS role to another application.

## Media metadata

Portable metadata may leave the device when a photo is shared. Only deliberately
portable fields are embedded: semantic caption/tags, model/version, timestamp,
and confidence. Faces, identity guesses, precise private location inferences,
OCR secrets, embeddings, and internal prompts remain solely in the encrypted
index unless the user explicitly exports them.

Video indexing remains private and source-read-only. If the owner explicitly
confirms **Create AI-enhanced copy**, the new MP4 contains the reviewed caption,
tags, model provenance, source digest/generation, and timed transcript events in
AIOS application-metadata tracks. The original is unchanged, no ZIP/sidecar is
created, and the confirmation warns that generic players may not display the
custom subtitles. The enhanced copy is ordinary owner-visible media and follows
the owner's deletion/sharing choices rather than the 24-hour call-artifact rule.
Its crash journal is credential-encrypted and contains the source URI/generation,
random token, output volume/URI, and creation time only while publication is
incomplete; success or verified cleanup removes the row. The token marker is
visible only on the owner's pending MediaStore row and is cleared atomically when
that copy is published.

## Model licensing

An "open-weight" model is not necessarily OSI open source and does not inherit
the Apache-2.0 license of AIOS code. Supertonic 3 weights use OpenRAIL-M, which
includes use restrictions and redistribution conditions. Its Sherpa conversion
archive contains an MIT code license that is not a substitute for the weights'
license. The model packer therefore requires the separately pinned OpenRAIL-M
text, installs it with the weights, and assigns the generated Soong modules a
restricted license kind. Gemma terms and every future model license still need
qualified review before a public image or device is distributed.

## Sources

- US federal consent overview: https://www.congress.gov/crs-product/R41733
- California Penal Code section 632:
  https://leginfo.legislature.ca.gov/faces/codes_displaySection.xhtml?lawCode=PEN&sectionNum=632
- Supertonic 3 model license at the pinned revision:
  https://huggingface.co/Supertone/supertonic-3/blob/724fb5abbf5502583fb520898d45929e62f02c0b/LICENSE

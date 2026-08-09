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
- No cloud transfer, analytics payload, or training use contains call content.
- The assistant must not falsely claim to be the owner or another real person.
- Emergency calls and emergency callback mode never use the AI receptionist.

## Access control

"Other apps can use the models" means authorized apps can call a stable,
signature-protected capability API. It does not mean arbitrary installed apps can
read model files, call transcripts, the photo index, or unmetered inference.
Future third-party access requires a user-visible permission, quotas, foreground
rules, and protection against using inference as a covert microphone or media
exfiltration channel.

## Media metadata

Portable metadata may leave the device when a photo is shared. Only deliberately
portable fields are embedded: semantic caption/tags, model/version, timestamp,
and confidence. Faces, identity guesses, precise private location inferences,
OCR secrets, embeddings, and internal prompts remain solely in the encrypted
index unless the user explicitly exports them.

## Sources

- US federal consent overview: https://www.congress.gov/crs-product/R41733
- California Penal Code section 632:
  https://leginfo.legislature.ca.gov/faces/codes_displaySection.xhtml?lawCode=PEN&sectionNum=632

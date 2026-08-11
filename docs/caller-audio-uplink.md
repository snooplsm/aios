# Caller-audio uplink gate

## Safety invariant

AIOS must never claim that a caller heard synthesized speech merely because an
`AudioTrack` was created or a preferred device request succeeded. Automatic AI
answering is available only when all of the following are true:

- the owner enabled on-device call processing;
- English/Spanish text-generation and speech-synthesis runtimes are available
  through Model Broker;
- the device exposes the explicit `TYPE_TELEPHONY` output and the playing track
  reports that actual routed device;
- the device product was enabled with
  `ro.aios.call_uplink_validated=true` after physical carrier-call evidence.

The checked-in common product sets that immutable property to `false`. An
emulator, a loopback recording, an audio-policy XML route, or
`setPreferredDevice()` returning true is insufficient evidence.

## Data path

```text
AI receptionist response text
  -> signature-protected Model Broker session (speech_synthesis)
  -> provider API v2 writable PCM pipe (mono PCM16, bounded by backpressure)
  -> Call Intelligence read end
  -> streaming sample-rate conversion to 48 kHz stereo PCM16
  -> AudioTrack(USAGE_VOICE_COMMUNICATION, TYPE_TELEPHONY)
  -> modem/call uplink
```

The client owns no model path. Broker and provider cancellation closes the pipe;
call teardown cancels synthesis, closes the read descriptor, and stops the track.
AI capture and endpointed transcription begin immediately after the AI answers.
Two ASR sessions stay live; the third authorized call-agent slot is used first
for one strict Gemma receptionist reply/risk result and then, after release, for
TTS. Synthesis runs only for the greeting or a validated receptionist response,
and caller turns arriving while the assistant is busy are queued. Any missing dependency,
route mismatch, empty PCM stream, Binder failure, or call teardown fails closed
for speech delivery without ending ordinary telephony.

## Pixel admission test

Run this independently for every supported Pixel product and the exact release
image, modem/firmware set, carrier, and call transport being admitted. Record
timestamps and retain logs without call content.

1. Keep the product property false and confirm Auto AI answer and the manual AI
   action are unavailable.
2. Package a digest-verified bilingual TTS provider and verify that broker death,
   provider death, and an empty output pipe fail only the AI response path.
3. On real cellular calls to independently recorded remote endpoints, generate
   uniquely identified English and Spanish receptionist responses. Confirm each
   remote recording contains the complete response exactly once and no local-only
   speaker path was mistaken for uplink delivery.
4. Confirm service telemetry reports `caller_audio_route_verified` while the
   track is playing and that the routed device type is telephony.
5. Confirm stored PCM/transcript timestamps begin immediately after AI pickup and
   synthesis can overlap continued incoming transcription without blocking it.
6. While a response is playing, take over from the in-call UI. Repeat with a
   second caller turn queued and take over from the ongoing notification. Confirm
   the remote endpoint hears no remaining or queued AI speech, the carrier call
   remains connected, and both transcript directions continue. A repeated
   takeover must be harmless.
7. Hang up during synthesis and confirm no later PCM or TTS completion callback
   can affect the ended call.
8. Repeat with handset, speaker, wired headset where supported, Bluetooth, call
   waiting, VoLTE, and VoWiFi. Ordinary call audio and the emergency path must
   remain functional when Call Intelligence is killed.
9. Store the evidence under the release record and mark
   `call.caller_uplink_remote_audibility` and
   `call.ai_receptionist_dialog_round_trip` passed. Mark
   `call.owner_takeover_stops_ai_speech` passed only with the remote-audio and
   continuing-transcription evidence above. Only then may the exact device
   product override the property to true.

This gate proves an engineering transport property, not legal sufficiency. The
spoken text and regional policy still require qualified legal review before a
shipping image.

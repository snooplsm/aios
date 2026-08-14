# Pixel 9a Phone-to-model binding smoke - 2026-08-14

This non-admission physical-device smoke verifies that one matched AIOS Phone,
Call Intelligence, Model Broker, and authorization-policy set starts on the
Pixel 9a. It was captured after replacing a stale Phone APK whose older Binder
projection crashed while decoding the current call-assistant policy.

`ACTION_DIAL` cold-launched `com.aios.phone/.ui.MainActivity` successfully in
650 ms. AIOS Phone remained alive, remained the Android dialer role holder, and
had an active service binding to Call Intelligence. Call Intelligence remained
alive with active bindings to Model Broker. A focused log captured after the
replacement contained no `FATAL EXCEPTION` or `AndroidRuntime` crash report.

The replacement Phone build passed all 53 `aios_phone_host_tests`, including a
regression test proving malformed or missing Binder policy strings fail closed.
The APK installed on the device matched the staged SHA-256 digest after reboot.

This smoke proves process startup and the Phone -> Call Intelligence -> Model
Broker binding chain only. It does not prove carrier calling, live audio,
semantic compaction during a real conversation, emergency behavior, or any
physical release/admission gate.

See `call-binding-summary.json` for the exact deployed artifact hashes and
bounded result fields. The record intentionally excludes the device serial,
telephone data, call content, and raw logs.

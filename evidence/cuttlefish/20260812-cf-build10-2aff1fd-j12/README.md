# Android 17 Cuttlefish build 10

This directory contains privacy-minimized evidence for AIOS revision
`2aff1fd02bf8b952e2494657d03fd583fa73d61b` and the Android manifest identified
by `soong-build-evidence.json`. The full resolved manifest and Soong log remain
in external evidence storage; their SHA-256 digests are bound by the checked-in
build record.

The build record proves the Android 17 Cuttlefish product built from immutable
sources and binds every expected AIOS product artifact plus `product.img` and
`system.img`. The guest was launched from these images with `--noresume`; the
first-boot record binds it back to this build and verifies the six privileged
packages and three core service actions. This virtual evidence is explicitly
ineligible for physical-device or model-performance gates.

The clean guest also made AIOS Phone the dialer role holder, granted its
requested `ADD_VOICEMAIL` permission through that role, produced host/guest
digest matches for Phone, Call Intelligence, and Model Broker, and held stable
process IDs and service bindings with no filtered fatal or permission errors.
Those runtime observations are smoke-test context, not additional release-gate
claims in the two machine-readable records.

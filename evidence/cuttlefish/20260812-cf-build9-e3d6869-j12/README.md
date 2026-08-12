# Android 17 Cuttlefish build 9

This directory contains privacy-minimized evidence for the AIOS revision and
Android manifest identified by `soong-build-evidence.json`. The full resolved
manifest and 8 MB Soong log remain in external evidence storage; their SHA-256
digests are bound by the checked-in build record.

The build record proves the Android 17 Cuttlefish product built from immutable
sources and binds every expected AIOS product artifact plus `product.img` and
`system.img`. The first-boot record binds the running guest back to that build,
verifies the six privileged packages and three core service actions, and is
explicitly ineligible for physical-device or model-performance gates.

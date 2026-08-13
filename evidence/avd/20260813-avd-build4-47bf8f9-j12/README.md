# Android 17 Goldfish AVD build 4

This directory contains privacy-minimized build evidence for AIOS revision
`47bf8f99e7e7738e54842494e7fb28bb06f620b1` and the immutable Android manifest
identified by `soong-build-evidence.json`. The full resolved manifest and Soong
log remain in external evidence storage; their SHA-256 digests are bound by the
checked-in build record.

The build record proves that the Android 17 x86-64 Goldfish product built
successfully and binds all expected AIOS privileged applications, policy files,
`product.img`, and `system.img`. The wrapper's Goldfish-only 2 GiB dynamic
partition group is reflected in this completed build; it does not modify ARM64
GSI or physical Pixel partition geometry.

This is virtual-device build evidence. It cannot satisfy Pixel 9a boot,
telephony, accelerator, inference-performance, or factory-restore gates. A
separate first-boot record is required before the AVD first-boot gate can pass.

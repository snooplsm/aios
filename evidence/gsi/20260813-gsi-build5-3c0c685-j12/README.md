# Android 17 ARM64 GSI build 5

This directory contains privacy-minimized build and AVB evidence for AIOS
revision `3c0c685d39c63b6a3cb0d1d4f0d14a83228ad657` and the immutable Android 17
manifest bound by `soong-build-evidence.json`. The full resolved manifest,
installed-files inventory, build log, images, model weights, and runtime packs
remain in external evidence storage; their SHA-256 identities are bound by the
checked-in records. No model weight or proprietary Pixel binary is committed.

The successful `aios_gsi_arm64-aosp_current-userdebug` build produced a
4,639,948,800-byte `system.img` and 65,536-byte `vbmeta.img`. The installed-file
record binds all expected privileged AIOS apps and policy files, four
Pixel-9a-tier model declarations with 15 installed model-pack files, and the
three platform-resigned LiteRT-LM, whisper.cpp, and sherpa-onnx providers.

`avb-verification.json` binds an explicit `vbmeta -> system` chain verification
using AOSP's GSI RSA-2048 public key and rollback-index location 1. AVB verified
the vbmeta signature, chain descriptor, system footer, SHA-256 dm-verity
hashtree, and pvmfw hash. A read-only five-pass `e2fsck` also completed with no
filesystem errors.

This proves a deployable generic ARM64 system-image build, not a Pixel 9a boot.
It does not prove device partition capacity, DSU support, vendor compatibility,
radio/IMS behavior, call-audio capture, accelerator selection, inference
latency, or factory restore. Those gates remain open until physical-device
inventory and testing bind results to these exact image hashes.

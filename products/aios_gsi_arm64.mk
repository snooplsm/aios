# ARM64 Generic System Image target for Treble devices. Android's GSI board
# configuration redirects product- and system_ext-specific modules into
# /system/product and /system/system_ext inside the single system.img. That
# keeps the normal AIOS product modules additive while making the complete
# privileged product deployable without a Pixel-specific device tree.

$(call inherit-product, device/generic/common/gsi_arm64.mk)
$(call inherit-product, vendor/aios/products/aios_common.mk)

# Android 17 requires the selected Soong filesystem module to enumerate every
# product package that lands in system.img. The transactional build/make patch
# provides a thin wrapper around AOSP's android_gsi_defaults in the only
# namespace allowed to consume its private build.prop modules. The stable AIOS
# policy dependency carries common packages and optional generated pack anchors
# through normal transitive required-module packaging specs.
PRODUCT_SOONG_DEFINED_SYSTEM_IMAGE := aios_gsi_system_image
ifneq ($(wildcard vendor/aios/generated/modelpack/Android.bp),)
$(call soong_config_set_bool,aios,model_pack,true)
endif
ifneq ($(wildcard vendor/aios/generated/runtimepack/litert_lm/Android.bp),)
$(call soong_config_set_bool,aios,runtime_litert_lm,true)
endif
ifneq ($(wildcard vendor/aios/generated/runtimepack/sherpa_onnx_tts/Android.bp),)
$(call soong_config_set_bool,aios,runtime_sherpa_onnx_tts,true)
endif
ifneq ($(wildcard vendor/aios/generated/runtimepack/whisper_cpp/Android.bp),)
$(call soong_config_set_bool,aios,runtime_whisper_cpp,true)
endif

# AOSP's compliance GSI defaults to a 3 GiB dynamic-partition group. The
# catalog-pinned Pixel 9a model payloads alone occupy about 2.79 GB, so that
# envelope cannot contain both Android and the required on-device AI stack.
# Expand only this generated GSI container to 6 GiB plus the standard 8 MiB
# super metadata allowance. These values do not resize a Pixel partition;
# DSU/fastboot preflight still measures the exact image and device capacity.
BOARD_GSI_DYNAMIC_PARTITIONS_SIZE := 6442450944
BOARD_SUPER_PARTITION_SIZE := 6450839552

# AIOS intentionally adds product packages to the GSI system image. Preserve
# path checks for individual modules but allow this wrapper to extend the
# upstream release product.
PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS := relaxed

PRODUCT_NAME := aios_gsi_arm64
PRODUCT_DEVICE := generic_arm64
PRODUCT_BRAND := AIOS
PRODUCT_MODEL := AIOS ARM64 Generic System Image
PRODUCT_MANUFACTURER := AIOS

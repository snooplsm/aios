# ARM64 Generic System Image target for Treble devices. Android's GSI board
# configuration redirects product- and system_ext-specific modules into
# /system/product and /system/system_ext inside the single system.img. That
# keeps the normal AIOS product modules additive while making the complete
# privileged product deployable without a Pixel-specific device tree.

$(call inherit-product, device/generic/common/gsi_arm64.mk)
$(call inherit-product, vendor/aios/products/aios_common.mk)

# AIOS intentionally adds product packages to the GSI system image. Preserve
# path checks for individual modules but allow this wrapper to extend the
# upstream release product.
PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS := relaxed

PRODUCT_NAME := aios_gsi_arm64
PRODUCT_DEVICE := generic_arm64
PRODUCT_BRAND := AIOS
PRODUCT_MODEL := AIOS ARM64 Generic System Image
PRODUCT_MANUFACTURER := AIOS

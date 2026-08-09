# Android-latest integration target. Cuttlefish is present in the official
# Android 17 manifest; unlike Pixel device trees, it can continuously prove
# that the additive AIOS product and services still compile after AOSP updates.

$(call inherit-product, device/google/cuttlefish/vsoc_x86_64/phone/aosp_cf.mk)
$(call inherit-product, vendor/aios/products/aios_common.mk)

PRODUCT_NAME := aios_cf_x86_64_phone
PRODUCT_DEVICE := vsoc_x86_64
PRODUCT_BRAND := AIOS
PRODUCT_MODEL := AIOS Cuttlefish integration target
PRODUCT_MANUFACTURER := AIOS

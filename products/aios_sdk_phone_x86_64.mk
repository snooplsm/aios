# Standard Android Emulator phone target. This produces a Goldfish-backed
# x86-64 system image that can be launched by the Android Emulator; it is not a
# simulation of Pixel 9a silicon, modem firmware, or accelerator behavior.

$(call inherit-product, $(SRC_TARGET_DIR)/product/sdk_phone_x86_64.mk)
$(call inherit-product, vendor/aios/products/aios_common.mk)

# sdk_phone_x86_64 enables this only when it is the exact TARGET_PRODUCT. Keep
# the upstream SDK-product behavior after wrapping it with the AIOS name.
PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS := relaxed

PRODUCT_NAME := aios_sdk_phone_x86_64
PRODUCT_DEVICE := emulator_x86_64
PRODUCT_BRAND := AIOS
PRODUCT_MODEL := AIOS Android Emulator integration target
PRODUCT_MANUFACTURER := AIOS

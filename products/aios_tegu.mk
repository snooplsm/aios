# Pixel 9a (tegu) product wrapper. The upstream device product owns hardware
# configuration; AIOS contributes only additive product packages and identity.

$(call inherit-product, device/google/tegu/aosp_tegu.mk)
$(call inherit-product, vendor/aios/products/aios_common.mk)

PRODUCT_NAME := aios_tegu
PRODUCT_DEVICE := tegu
PRODUCT_BRAND := AIOS
PRODUCT_MODEL := AIOS on Pixel 9a
PRODUCT_MANUFACTURER := AIOS

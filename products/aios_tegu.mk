# Pixel 9a (tegu) product wrapper. The pinned GrapheneOS adevtool release
# generates the complete hardware product at vendor/google_devices/tegu/tegu.mk;
# AIOS contributes additive product packages and identity after generation.

$(call inherit-product, vendor/google_devices/tegu/tegu.mk)
$(call inherit-product, vendor/aios/products/aios_common.mk)

# Visual-only rebranding for the upstream device support surface. Package
# identities and implementation namespaces deliberately remain unchanged.
PRODUCT_PACKAGES += \
    AiosFrameworkBrandingOverlay \
    AiosSettingsBrandingOverlay \
    AiosSetupWizardBrandingOverlay

PRODUCT_NAME := aios_tegu
PRODUCT_DEVICE := tegu
PRODUCT_BRAND := AIOS
PRODUCT_MODEL := AIOS on Pixel 9a
PRODUCT_MANUFACTURER := AIOS

PRODUCT_MAKEFILES := \
    $(LOCAL_DIR)/products/aios_tegu.mk \
    $(LOCAL_DIR)/products/aios_cf_x86_64_phone.mk \
    $(LOCAL_DIR)/products/aios_sdk_phone_x86_64.mk \
    $(LOCAL_DIR)/products/aios_gsi_arm64.mk

COMMON_LUNCH_CHOICES := \
    aios_tegu-aosp_current-userdebug \
    aios_cf_x86_64_phone-aosp_current-userdebug \
    aios_sdk_phone_x86_64-aosp_current-userdebug \
    aios_gsi_arm64-aosp_current-userdebug

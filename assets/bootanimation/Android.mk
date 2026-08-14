LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := aios_bootanimation
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := bootanimation.zip
LOCAL_SRC_FILES := bootanimation.zip
LOCAL_MODULE_PATH := $(TARGET_OUT_PRODUCT)/media
LOCAL_LICENSE_KINDS := SPDX-license-identifier-Apache-2.0
LOCAL_LICENSE_CONDITIONS := notice
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/../../LICENSE
include $(BUILD_PREBUILT)

# Common product additions. Keep this file additive so device products remain
# owned by their upstream AOSP projects.

# Developer options and authenticated ADB default on only for eng/userdebug.
# Override with AIOS_ENABLE_DEVELOPER_DEFAULTS=false for a quieter debug image.
# A production user build defaults off and rejects an attempted force-enable.
AIOS_ENABLE_DEVELOPER_DEFAULTS ?= $(if $(filter userdebug eng,$(TARGET_BUILD_VARIANT)),true,false)
ifeq ($(AIOS_ENABLE_DEVELOPER_DEFAULTS),true)
ifeq ($(filter userdebug eng,$(TARGET_BUILD_VARIANT)),)
$(error AIOS_ENABLE_DEVELOPER_DEFAULTS=true is forbidden for a production user build)
endif
PRODUCT_PACKAGES += AiosDeveloperDefaults
PRODUCT_PRODUCT_PROPERTIES += ro.aios.developer_defaults=true
else
PRODUCT_PRODUCT_PROPERTIES += ro.aios.developer_defaults=false
endif

# A credential-bearing local RRO opts debug images into one-shot provisioning.
# The generated directory is gitignored, so Wi-Fi secrets never enter source.
AIOS_ENABLE_INSTANT_PROVISIONING ?= $(if $(and $(filter userdebug eng,$(TARGET_BUILD_VARIANT)),$(wildcard vendor/aios/generated/debugprovisioning/Android.bp)),true,false)
ifeq ($(AIOS_ENABLE_INSTANT_PROVISIONING),true)
ifeq ($(filter userdebug eng,$(TARGET_BUILD_VARIANT)),)
$(error AIOS_ENABLE_INSTANT_PROVISIONING=true is forbidden for a production user build)
endif
ifeq ($(wildcard vendor/aios/generated/debugprovisioning/Android.bp),)
$(error AIOS_ENABLE_INSTANT_PROVISIONING=true requires the local generated debug-provisioning overlay)
endif
PRODUCT_PACKAGES += AiosDebugProvisioningOverlay
PRODUCT_PRODUCT_PROPERTIES += ro.aios.instant_provisioning=true
else
PRODUCT_PRODUCT_PROPERTIES += ro.aios.instant_provisioning=false
endif

# Original AIOS artwork is optional and additive to the upstream device product.
AIOS_ENABLE_BOOT_ANIMATION ?= true
ifeq ($(AIOS_ENABLE_BOOT_ANIMATION),true)
PRODUCT_PACKAGES += aios_bootanimation
endif

PRODUCT_PACKAGES += \
    AiosContextIntelligence \
    AiosMessaging \
    AiosPhone \
    AiosFrameworkDefaultsOverlay \
    AiosCallIntelligence \
    AiosMediaIntelligence \
    AiosModelBroker \
    aios_authorized_clients \
    aios_model_admission \
    aios_model_catalog \
    aios_runtime_catalog \
    aios_product_policy \
    default-permissions-aios \
    privapp-permissions-aios

# Never ship the benchmark client or instrumentation on a production user
# image. PRODUCT_PACKAGES_DEBUG is installed only for eng/userdebug builds.
PRODUCT_PACKAGES_DEBUG += \
    AiosModelBenchmark \
    AiosModelBenchmarkTests

PRODUCT_PRODUCT_PROPERTIES += \
    ro.aios.version=0.1-dev \
    ro.aios.model_policy=/product/etc/aios/model_catalog.json \
    ro.aios.model_admission=/product/etc/aios/model_admission.json \
    ro.aios.runtime_policy=/product/etc/aios/runtime_catalog.json \
    ro.aios.authorized_clients=/product/etc/aios/authorized_clients.json \
    ro.aios.product_policy=/product/etc/aios/product_policy.json \
    ro.aios.call_uplink_validated=false

# Generated only after local license acceptance and model digesting. The missing
# file is intentional for model-free bring-up images; Model Broker fails closed.
-include vendor/aios/generated/modelpack/aios_model_pack.mk

# Generated only after an exact, dependency-verified runtime-provider build.
-include $(wildcard vendor/aios/generated/runtimepack/*/aios_runtime_pack.mk)

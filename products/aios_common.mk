# Common product additions. Keep this file additive so device products remain
# owned by their upstream AOSP projects.

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

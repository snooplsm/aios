cmake_minimum_required(VERSION 3.22.1)
project(aios_whisper_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

set(WHISPER_CPP_DIR
    "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/whisper.cpp"
    CACHE PATH "Exact whisper.cpp source checkout")
if(NOT EXISTS "${WHISPER_CPP_DIR}/include/whisper.h")
    message(FATAL_ERROR "Pinned whisper.cpp source is absent: ${WHISPER_CPP_DIR}")
endif()

if(ANDROID_ABI STREQUAL "x86_64")
    # The x86 ABI exists only in the QEMU debug variant. AGP selects CMake's
    # Debug configuration for that APK, so explicitly optimize whisper/ggml as
    # well as the JNI shim or the emulator cannot keep up with call cadence.
    add_compile_options(-O3)
endif()

add_subdirectory("${WHISPER_CPP_DIR}" whisper-cpp EXCLUDE_FROM_ALL)

# The expensive work lives in whisper/ggml, not in the JNI shim. Keep the
# minimum Pixel generation explicit so a dependency update cannot silently
# build the kernels with generic arm64 flags again.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(AIOS_ARM64_COMPUTE_TARGETS whisper ggml-base ggml-cpu)
    foreach(compute_target IN LISTS AIOS_ARM64_COMPUTE_TARGETS)
        if(NOT TARGET "${compute_target}")
            message(FATAL_ERROR "Pinned whisper.cpp is missing target: ${compute_target}")
        endif()
        target_compile_options("${compute_target}" PRIVATE
            -O3
            -march=armv8.2-a+fp16)
    endforeach()
endif()

add_library(aios_whisper_jni SHARED aios_whisper_jni.cpp)
target_include_directories(aios_whisper_jni PRIVATE "${WHISPER_CPP_DIR}/include")
target_compile_options(aios_whisper_jni PRIVATE
    -O3
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(aios_whisper_jni PRIVATE -march=armv8.2-a+fp16)
elseif(NOT ANDROID_ABI STREQUAL "x86_64")
    message(FATAL_ERROR "Unsupported AIOS whisper.cpp ABI: ${ANDROID_ABI}")
endif()
target_link_options(aios_whisper_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(aios_whisper_jni PRIVATE whisper log android)

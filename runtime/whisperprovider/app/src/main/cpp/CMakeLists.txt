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

add_subdirectory("${WHISPER_CPP_DIR}" whisper-cpp EXCLUDE_FROM_ALL)

add_library(aios_whisper_jni SHARED aios_whisper_jni.cpp)
target_include_directories(aios_whisper_jni PRIVATE "${WHISPER_CPP_DIR}/include")
target_compile_options(aios_whisper_jni PRIVATE
    -O3
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    -march=armv8.2-a+fp16)
target_link_options(aios_whisper_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(aios_whisper_jni PRIVATE whisper log android)

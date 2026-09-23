cmake_minimum_required(VERSION 3.22.1)

project("ail0l-llama" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# --------------------------------------------------------------------------
# Pull llama.cpp at configure time (needs git + network on build machine).
# Vendored ai_chat.cpp below is kept in sync with llama.cpp master.
# --------------------------------------------------------------------------
include(FetchContent)
set(LLAMA_CPP_GIT_TAG "master" CACHE STRING "llama.cpp revision used for the native build")
message(STATUS "[ail0l-llama] Fetching llama.cpp @ ${LLAMA_CPP_GIT_TAG}")

FetchContent_Declare(
    llamacpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        ${LLAMA_CPP_GIT_TAG}
    GIT_SHALLOW    TRUE
    GIT_PROGRESS   TRUE
)

FetchContent_GetProperties(llamacpp)
if(NOT llamacpp_POPULATED)
    FetchContent_Populate(llamacpp)
endif()

set(LLAMA_SRC ${llamacpp_SOURCE_DIR})
message(STATUS "[ail0l-llama] llama.cpp source: ${LLAMA_SRC}")

# --------------------------------------------------------------------------
# Android-specific backend tuning (mirrors the official llama.android binding)
# --------------------------------------------------------------------------
if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a" OR ANDROID_ABI STREQUAL "x86")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

# llama.cpp static libraries
add_subdirectory(${LLAMA_SRC} build-llama)

# --------------------------------------------------------------------------
# JNI wrapper library
# --------------------------------------------------------------------------
add_library(ail0l-llama SHARED
        ai_chat.cpp)

target_compile_definitions(ail0l-llama PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(ail0l-llama PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(ail0l-llama
        llama
        llama-common
        android
        log)

install(TARGETS ail0l-llama LIBRARY DESTINATION lib)
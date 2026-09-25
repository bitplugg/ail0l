cmake_minimum_required(VERSION 3.22.1)

project("aiia-llama" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

include(FetchContent)
set(LLAMA_CPP_GIT_TAG "e6ab7c1a41054a888ada952eab4c886444c2f5ad" CACHE STRING "llama.cpp revision used for the native build")
message(STATUS "[aiia-llama] Fetching llama.cpp @ ${LLAMA_CPP_GIT_TAG}")

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
message(STATUS "[aiia-llama] llama.cpp source: ${LLAMA_SRC}")

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

set(LLAMA_BUILD_MTMD ON CACHE BOOL "Build the multimodal mtmd library" FORCE)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(aiia-llama SHARED
        ai_chat.cpp)

target_compile_definitions(aiia-llama PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(aiia-llama PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src
        ${LLAMA_SRC}/tools/mtmd)

target_link_libraries(aiia-llama
        llama
        llama-common
        mtmd
        android
        log)

install(TARGETS aiia-llama LIBRARY DESTINATION lib)
